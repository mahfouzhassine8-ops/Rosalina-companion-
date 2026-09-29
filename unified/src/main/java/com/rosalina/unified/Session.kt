package com.rosalina.unified

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RosalinaApp:Application(){override fun onCreate(){super.onCreate();CrashJournal.install(this);if(getProcessName()==packageName)Session.get(this)}}
internal class Session private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:Session?=null
        fun get(context:Context):Session=instance ?:synchronized(this){instance ?:Session(context.applicationContext).also{instance=it}}
        const val DEFAULT_SYSTEM="You are Rosalina, a private on-device assistant optimized for text chat and live voice. Be helpful, practical, direct, conversational, and clear. Do not claim internet access or capabilities that are not available in the current app."
    }
    val prefs=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE)
    val models=ModelStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val thermal=ThermalManager(context)
    private val journal=RenderJournal(context)
    private val lease=EngineLease()
    private val chat=EngineRpc(context,ChatService::class.java)
    private val listen=EngineRpc(context,ListenService::class.java)
    private val speech=EngineRpc(context,SpeechService::class.java)
    private val platformSpeech=PlatformSpeech(context)
    private val expressive=EngineRpc(context,ExpressiveSpeechService::class.java)
    val voiceV3Models=VoiceV3Models(context)
    val onlineVoiceSettings=OnlineVoiceSettings(context)
    private val onlineVoice=OnlineVoice(context,onlineVoiceSettings)
    private val auditions=VoiceAuditions(prefs)
    @Volatile private var onlineFailed=false
    @Volatile private var expressiveFailed=false
    @Volatile private var voiceFallbackReason="Voice V3 has not been accepted on this phone"
    @Volatile private var nativeSpeechCrashed=false
    private val learner=LiveLearner(prefs)
    private val transcriptFile=File(context.filesDir,"conversation.json")
    private val turns=mutableListOf<Pair<String,String>>()
    @Volatile private var snapshot:List<Pair<String,String>> = emptyList()
    private val historyReady=CompletableDeferred<Unit>()
    private val mutable=MutableStateFlow(TaskState(result=prefs.getString("result","").orEmpty(),stage=if(prefs.getString("active-id","").isNullOrBlank())"Ready" else "Previous work was interrupted. No task restarted."))
    val state:StateFlow<TaskState> = mutable.asStateFlow()
    val presentation=CompanionRuntime { mutable.update{it.copy(revision=it.revision+1)} }
    private fun now()=SystemClock.elapsedRealtime()
    @Volatile private var request:TaskRequest?=null
    @Volatile private var activeJob:Job?=null
    @Volatile private var taskService:GenerationService?=null
    @Volatile private var stopReason=""
    @Volatile var finishListening=false
    @Volatile private var voiceActive=false
    @Volatile private var voiceInterrupt=false
    @Volatile private var capture:VoiceCapture?=null
    @Volatile private var chatNeedsReset=false
    @Volatile private var warmSystem:String?=null
    private var speechMetrics=""
    private var playbackMetrics=""
    @Volatile private var liveMetrics=""
    @Volatile private var liveWarmMetrics=""
    @Volatile private var voiceStartJob:Job?=null
    private var photoImportJob:Job?=null
    private var chatMetrics=""
    @Volatile private var lastChatFirstTextMs=0L
    private var probeLog=""
    private val deviceFacts by lazy{DeviceFacts.describe(context)}
    init {
        prefs.edit().remove("active-id").apply()
        scope.launch(Dispatchers.IO) {
            runCatching{val a=JSONArray(transcriptFile.readText());synchronized(this@Session){for(i in 0 until a.length()){val o=a.getJSONObject(i);turns.add(o.getString("role") to o.getString("text"))};snapshot=turns.toList()}}
            historyReady.complete(Unit);mutable.update{it.copy(revision=it.revision+1)};runCatching{refreshResources()}
        }
    }
    fun transcript():List<Pair<String,String>> = snapshot
    /** Invoked only when Chat is visible. Uses the same lease/FGS as every major engine. */
    fun prepareChat() {
        if(state.value.busy || state.value.quarantined || models.path(ModelKey.CHAT)==null)return
        if(chat.pid>0 && warmSystem==systemPrompt())return
        val profile=runCatching{capability()}.getOrNull()
        if(profile!=null && !profile.allowPrewarm){notice("${profile.label} · chat loads when you send a message");return}
        begin(TaskRequest(kind=TaskKind.CHAT,modelKey="prepare"))
    }
    private suspend fun warmChat(r:TaskRequest) {
        val res=thermal.read()
        require(!res.low){"Chat preparation deferred: Android reports low memory"}
        chatNeedsReset=true
        val result=chat.call(Bundle().apply{putString("operation","prepare");putString("model",models.requirePath(ModelKey.CHAT).path);putString("system",systemPrompt())}){event->
            if(event.getString("type")=="stage")update(r.id){it.copy(stage=event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="Qwen · preparing preserved CPU engine")}
        }
        check(result.getBoolean("prepared")){"Chat model did not confirm preparation"}
        chatNeedsReset=false;warmSystem=systemPrompt()
        chatMetrics="Prepared chat on opening; model setup=${result.getLong("modelSetupMs")} ms; already warm=${result.getBoolean("warmModel")}"
    }
    @Synchronized private fun addTurn(role:String,text:String) {
        turns.add(role to text);snapshot=turns.toList()
        val a=JSONArray();turns.forEach{(r,t)->a.put(JSONObject().put("role",r).put("text",t))};atomicText(transcriptFile,a.toString())
        mutable.update{it.copy(revision=it.revision+1)}
    }
    @Synchronized fun clearConversation() {
        if(state.value.busy || state.value.quarantined)return
        val id="clear-${UUID.randomUUID()}";if(!lease.acquire(id))return
        mutable.update{it.copy(id=id,busy=true,stage="Clearing conversation")}
        val job=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var failure:Throwable?=null
            try{historyReady.await();chat.shutdown();warmSystem=null;currentCoroutineContext().ensureActive();synchronized(this@Session){atomicText(transcriptFile,"[]");turns.clear();snapshot=emptyList()}}
            catch(t:Throwable){failure=t}
            finally{withContext(NonCancellable+Dispatchers.Main.immediate){activeJob=null;if(lease.release(id))mutable.update{it.copy(busy=false,stopping=false,answer="",revision=it.revision+1,stage=if(failure==null)"Conversation cleared" else failure?.message ?:"Clear stopped",error=failure?.toString().orEmpty(),quarantined=failure is WorkerQuarantined)}}}
        };activeJob=job;job.start()
    }
    private fun update(id:String,change:(TaskState)->TaskState) {
        mutable.update{old->if(old.id!=id)old else {
            var next=change(old);if(next.pid>0)next=next.copy(lastPid=next.pid)
            if(old.stopping && next.busy)next=next.copy(stopping=true,stage="Stopping…",percent=null,eta="")
            next
        }}
    }
    fun notice(text:String){mutable.update{if(it.busy)it else it.copy(stage=text)}}
    private fun systemPrompt():String=CompanionPersonality.system(prefs.getString("system",DEFAULT_SYSTEM) ?:DEFAULT_SYSTEM,prefs.getBoolean("companion-flirty",false))
    fun voiceV3Summary():String {
        val approved=voiceV3Models.fingerprint().isNotBlank() && prefs.getString("voice-v3-approved-pack","")==voiceV3Models.fingerprint()
        return voiceV3Models.summary()+"; phone acceptance=$approved; requested primary=${prefs.getBoolean("voice-v3-primary",false)}; fallback=$voiceFallbackReason"
    }
    fun voiceAuditionSummary()=auditions.summary()
    fun setVoiceV3Approved(accepted:Boolean) {
        if(state.value.busy)return
        val hash=voiceV3Models.fingerprint()
        check(!accepted || (hash.isNotBlank() && voiceV3Models.directory()!=null && auditions.canAccept(hash))){"Complete a successful audition of this exact Chatterbox pack before confirming phone acceptance"}
        prefs.edit().putString("voice-v3-approved-pack",if(accepted)hash else "").putBoolean("voice-v3-primary",accepted).apply()
        expressiveFailed=false
        notice(if(accepted)"Candidate enabled by your device acceptance; compatibility fallback retained" else "Using the protected Android compatibility voice")
    }
    suspend fun installedSystemVoices()=platformSpeech.installedVoiceNames()
    fun selectSystemVoice(name:String){if(!state.value.busy)prefs.edit().putString("voice-system-name",name).apply()}
    fun auditionVoiceV3(text:String,delivery:String="",style:String?=null) { if(!state.value.busy)begin(TaskRequest(kind=TaskKind.CHAT,modelKey="voice-v3-test",prompt=text.take(400),uri=delivery,voiceStyle=style)) }
    fun auditionOnlineVoice(text:String,delivery:String="",style:String?=null) {if(!state.value.busy && onlineVoiceSettings.enabled())begin(TaskRequest(kind=TaskKind.CHAT,modelKey="online-voice-test",prompt=text.take(400),uri=delivery,voiceStyle=style))}
    fun toggleMicrophoneMute() {
        val input=capture ?:return
        runCatching{input.setMuted(!input.isMuted)}.onSuccess {
            presentation.microphone(state.value.id,input.recordingActive,input.isMuted,now())
            mutable.update{it.copy(stage=if(input.isMuted)"Microphone muted" else "Listening · LIVE",revision=it.revision+1)}
        }.onFailure{stop("Microphone could not change state: ${it.message}")}
    }
    private fun interruptSpeech() {
        val id=state.value.id
        presentation.interrupt(id,now())
        speech.interruptNow();expressive.interruptNow();platformSpeech.stop();onlineVoice.stop()
        capture?.outputActive=false
    }
    private fun capabilityMode()=prefs.getString("phone-capability","adaptive") ?:"adaptive"
    private fun capability(resources:Resources=thermal.read())=PhoneCapabilityPolicy.choose(resources,capabilityMode())
    fun phoneCapabilitySummary():String=runCatching{capability().summary()}.getOrDefault("Phone capability · unavailable")
    fun liveLearningSummary()=learner.snapshot().summary()
    fun resetLiveLearning(){learner.reset();notice("Adaptive Live learning reset")}
    private fun usePlatformSpeech()=SpeechCompatibility.preferPlatform(Build.MANUFACTURER,Build.VERSION.SDK_INT,nativeSpeechCrashed || prefs.getBoolean("native-tts-crashed",false))
    private fun markNativeSpeechCrash(){nativeSpeechCrashed=true;prefs.edit().putBoolean("native-tts-crashed",true).apply()}
    fun testTone(){if(!state.value.busy)begin(TaskRequest(kind=TaskKind.CHAT,modelKey="tone-test",prompt="speaker tone"))}
    fun testVoiceText(text:String,delivery:String="",style:String?=null,systemVoice:String?=null){if(!state.value.busy)begin(TaskRequest(kind=TaskKind.CHAT,modelKey="voice-test",prompt=text.take(400),uri=delivery,voiceStyle=style,systemVoice=systemVoice))}
    fun testVoice(){if(!state.value.busy)begin(TaskRequest(kind=TaskKind.CHAT,modelKey="voice-test",prompt="Rosalina speaker test. If you can hear this, local text to speech is working."))}
    fun refreshResources(){val r=thermal.read();mutable.update{it.copy(thermal=r.thermal,thermalAt=r.measuredAt,availableBytes=r.available,totalBytes=r.total)}}
    @Synchronized fun begin(r:TaskRequest):Boolean {
        if(r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE)){notice("Photo and video tools are inactive in Chat + Live Focus");return false}
        if(state.value.quarantined){notice("Force-stop Rosalina before starting another worker");return false}
        if(!lease.acquire(r.id))return false
        request=r;stopReason="";finishListening=false;voiceInterrupt=false;probeLog="";onlineFailed=false;expressiveFailed=false
        presentation.begin(r.id,now())
        mutable.value=TaskState(id=r.id,kind=r.kind,busy=true,stage="Preparing",result=state.value.result,revision=state.value.revision,eta=if(r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE))"ETA calibrating…" else "")
        prefs.edit().putString("active-id",r.id).apply()
        try{ContextCompat.startForegroundService(context,Intent(context,GenerationService::class.java).putExtra("task",r.id))}
        catch(t:Throwable){lease.release(r.id);request=null;prefs.edit().remove("active-id").apply();mutable.update{it.copy(busy=false,error=t.toString(),stage="Android could not start foreground work")};return false}
        return true
    }
    fun currentRequest()=request
    fun stop(reason:String="Stopped by you") {
        voiceStartJob?.cancel();voiceStartJob=null
        if(!state.value.busy)return
        runCatching{capture?.setMuted(true)}
        capture?.let{presentation.microphone(state.value.id,it.recordingActive,it.isMuted,now())}
        stopReason=reason;mutable.update{it.copy(stopping=true,stage="Stopping…",percent=null,eta="")}
        interruptSpeech();activeJob?.cancel(CancellationException(reason))
    }
    fun interruptAndListen() {
        if(voiceActive){voiceInterrupt=true;return}
        if(voiceStartJob?.isActive==true)return
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                if(state.value.busy) {
                    // Cancel only the current task, not this queued transition.
                    stopReason="Switching to Live";mutable.update{it.copy(stopping=true,stage="Stopping…",percent=null,eta="")}
                    interruptSpeech();activeJob?.cancel(CancellationException(stopReason))
                    withTimeoutOrNull(8000){state.first{!it.busy}}
                }
                ensureActive()
                if(!state.value.busy)begin(TaskRequest(kind=TaskKind.VOICE))
            } finally { if(voiceStartJob===currentCoroutineContext()[Job])voiceStartJob=null }
        }
        voiceStartJob=job;job.start()
    }
    fun launchPending(service:GenerationService,taskId:String) {
        val r=request ?:run{service.finishTask(taskId);return}
        if(r.id!=taskId || activeJob?.isActive==true)return
        taskService=service
        if(state.value.stopping){activeJob=scope.launch{finish(r,service,CancellationException(stopReason))};return}
        val job=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var failure:Throwable?=null;val start=SystemClock.elapsedRealtime()
            val ticker=scope.launch(Dispatchers.IO) {
                var savedAt=0L
                while(isActive && state.value.id==r.id && state.value.busy) {
                    val res=try{thermal.read()}catch(t:Throwable){
                        if(t is CancellationException)throw t
                        update(r.id){it.copy(error="Resource monitor: ${t.stackTraceToString()}")}
                        stop("Resource monitoring failed; work stopped safely");break
                    }
                    update(r.id){it.copy(elapsedMs=SystemClock.elapsedRealtime()-start,availableBytes=res.available,totalBytes=res.total,thermal=res.thermal,thermalAt=res.measuredAt)}
                    if(expressive.pid>0 && (res.low || res.thermal>=2)){voiceFallbackReason="Candidate stopped for memory/thermal pressure";expressive.interruptNow()}
                    if(r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE) && ThermalPolicy.blocks(res.thermal)){stop("Stopped safely: Android reported ${ThermalPolicy.label(res.thermal)} heat");break}
                    if(SystemClock.elapsedRealtime()-savedAt>=10000){runCatching{atomicText(File(context.filesDir,"last-diagnostics.txt"),diagnostics())};savedAt=SystemClock.elapsedRealtime()}
                    delay(1000)
                }
            }
            try {
                historyReady.await()
                coroutineScope { when(r.kind) {
                    TaskKind.IMPORT->{chat.shutdown();warmSystem=null;listen.shutdown();speech.shutdown();expressive.shutdown()
                        if(r.modelKey=="VOICE_V3")voiceV3Models.importPack(Uri.parse(r.uri)){text,p->update(r.id){it.copy(stage=text,percent=p)}}
                        else models.import(ModelKey.valueOf(r.modelKey),Uri.parse(r.uri)){text,p->update(r.id){it.copy(stage=text,percent=p)}}}
                    TaskKind.CHAT->when(r.modelKey){"prepare"->warmChat(r);"tone-test"->performToneTest(r);"voice-test"->performVoiceTest(r);"voice-v3-test"->performVoiceV3Test(r);"online-voice-test"->performOnlineVoiceTest(r);else->performChat(r,r.prompt,prefs.getBoolean("spoken-replies",false))}
                    TaskKind.VOICE->performVoice(r)
                    else->render(r)
                } }
            }catch(t:Throwable){failure=t}
            finally {
                withContext(NonCancellable) {
                    ticker.cancelAndJoin();platformSpeech.stop();onlineVoice.stop();capture?.close();capture=null;voiceActive=false
                    presentation.end(r.id,now())
                    var finalFailure=failure
                    try{listen.shutdown()}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    try{speech.shutdown()}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    try{expressive.shutdown()}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    if(chatNeedsReset || r.kind !in listOf(TaskKind.CHAT,TaskKind.VOICE) || runCatching{thermal.read().low}.getOrDefault(true)) {
                        try{chat.shutdown();warmSystem=null;chatNeedsReset=false}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    }
                    runCatching{journal.finish(finalFailure)}
                    finish(r,service,finalFailure)
                }
            }
        };activeJob=job;job.start()
    }
    private suspend fun finish(r:TaskRequest,service:GenerationService,failure:Throwable?)=withContext(NonCancellable+Dispatchers.Main.immediate) {
        if(lease.current()!=r.id)return@withContext
        val old=state.value
        val message=when{failure is CancellationException->stopReason.ifBlank{"Stopped"};failure!=null->failure.message ?:failure.javaClass.simpleName;r.modelKey=="prepare"->"Ready · chat model prepared";old.result.isNotBlank() && old.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE)->"Ready · saved privately";else->"Ready"}
        var done=old.copy(busy=false,stopping=false,stage=message,percent=null,step=0,total=0,eta="",pid=0,voiceStage="",workHint="",avatarEnergy=0f,error=if(failure!=null && failure !is CancellationException)failure.stackTraceToString()else old.error,quarantined=old.quarantined || failure is WorkerQuarantined)
        try {
            try{service.finishTask(r.id)}catch(t:Throwable){done=done.copy(error=(done.error+"\nForeground cleanup: "+t.stackTraceToString()).takeLast(24000),stage="Task ended; foreground cleanup failed")}
            withContext(Dispatchers.IO) {
                if(failure!=null && failure !is CancellationException)CrashJournal.recordTaskFailure(context,r.kind.name,failure)
                runCatching{atomicText(File(context.filesDir,"last-diagnostics.txt"),diagnostics(done))}
            }
        } finally {
            taskService=null;request=null;activeJob=null;prefs.edit().remove("active-id").apply()
            lease.release(r.id);mutable.value=done
        }
    }
    private suspend fun setTaskMode(r:TaskRequest,playback:Boolean=false)=withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive();taskService?.setMode(r.id,r.kind,microphone=voiceActive,playback=playback)
    }
    private fun voiceExpression(userText:String,spokenText:String,styleKey:String?=null):VoiceExpression {
        val intended=PerformanceDirector.decide(userText,spokenText,prefs.getBoolean("companion-flirty",false),prefs.getInt("voice-pace",100)/100f)
        val style=NaturalVoiceStyle.fromKey(styleKey ?:prefs.getString("voice-natural-style","natural"))
        val p=style.apply(intended)
        return VoiceExpression(style.label,energy=p.volume,pace=p.pace,intensity=p.intensity,performance=p)
    }
    private fun Bundle.putExpression(expression:VoiceExpression) {
        putString("voiceProfile",expression.name)
        putFloat("pitchSemitones",expression.pitchSemitones)
        putFloat("breathiness",expression.breathiness)
        putFloat("tone",expression.tone)
        putFloat("rasp",expression.rasp)
        putFloat("energy",expression.energy)
        putFloat("pace",expression.pace)
        putFloat("voiceIntensity",expression.intensity)
    }
    private suspend fun speakPlatform(r:TaskRequest,text:String,expression:VoiceExpression):PlatformSpeechResult {
        // Preparation is not playback. Only the platform's playback callbacks drive Speaking.
        val performance=expression.performance ?:PerformanceState(expression=RosalinaExpression.SPEAKING)
        val face=performance.expression
        val utterance=UUID.randomUUID().toString()
        presentation.preparingSpeech(r.id,utterance,face,"Android compatibility TTS",now(),performance)
        return try {
            platformSpeech.speak(text,expression.pace,requestUtterance=utterance,volume=performance.volume,voiceName=r.systemVoice,observe={event->
                val owns=state.value.id==r.id && presentation.snapshot.taskId==r.id && presentation.snapshot.utteranceId==event.utteranceId
                if(owns)when(event.event){
                    "preparing"->Unit
                    "interrupt"->presentation.interrupt(r.id,now())
                    "start"->{presentation.playback(r.id,event.utteranceId,true,now());capture?.outputActive=true;update(r.id){it.copy(voiceStage="Speaking · compatibility voice")}}
                    "energy"->{presentation.energy(r.id,event.utteranceId,event.energy,event.source,event.mouth);update(r.id){it.copy(avatarEnergy=presentation.snapshot.energy)}}
                    "stop"->{presentation.playback(r.id,event.utteranceId,false,now());capture?.outputActive=false;update(r.id){it.copy(voiceStage="",avatarEnergy=0f)}}
                }
            }).also{result->
                playbackMetrics="Android compatibility TTS; engine=${result.engine}; voice=${result.voice}; offline=${result.offline}; first playback callback=${result.firstAudioMs} ms; playback=${result.playbackMs} ms; lip=${result.lipInput}; media volume=${result.mediaVolume}/${result.mediaMax}; muted=${result.mediaMuted}; fallback=$voiceFallbackReason"
            }
        } finally {if(state.value.id==r.id)capture?.outputActive=false;update(r.id){it.copy(voiceStage="",avatarEnergy=0f)}}
    }
    private fun candidateEligible():Boolean {
        val res=thermal.read();val hash=voiceV3Models.fingerprint()
        val approved=hash.isNotBlank() && prefs.getString("voice-v3-approved-pack","")==hash
        val result=VoiceV3Policy.eligible(prefs.getBoolean("voice-v3-primary",false),approved,voiceV3Models.directory()!=null,expressiveFailed,res.thermal,res.low)
        if(!result)voiceFallbackReason=when {
            expressiveFailed->"Candidate failed earlier in this session; no automatic retry"
            !approved->"Exact candidate pack not phone-approved"
            !prefs.getBoolean("voice-v3-primary",false)->"Protected compatibility voice selected"
            res.low->"Android low-memory condition"
            res.thermal>=2->"Voice effects reduced for Android thermal status ${res.thermal}"
            else->"Candidate pack unavailable"
        }
        return result
    }
    private suspend fun speakCandidate(r:TaskRequest,text:String,expression:VoiceExpression,trial:Boolean=false):Boolean {
        val utterance=UUID.randomUUID().toString();var started=false
        val thermalBefore=thermal.read().thermal
        val performance=expression.performance ?:PerformanceState(expression=RosalinaExpression.SPEAKING)
        val face=performance.expression
        presentation.preparingSpeech(r.id,utterance,face,"Chatterbox Turbo candidate",now(),performance)
        try {
            // Kokoro is never loaded alongside the new candidate. Platform TTS remains an idle fallback.
            speech.shutdown()
            val firstPlayback=CompletableDeferred<Unit>()
            val result=coroutineScope {
                val attempt=async{expressive.call(Bundle().apply{
                putString("operation","speak");putString("text",PerformanceDirector.localText(text,performance));putFloat("gain",performance.volume);putFloat("pace",performance.pace)
            }){event->if(presentation.snapshot.taskId==r.id && presentation.snapshot.utteranceId==utterance)when(event.getString("type")){
                "stage"->update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
                "playback"->{val playing=event.getString("text")=="start";if(playing){started=true;firstPlayback.complete(Unit)}
                    presentation.playback(r.id,utterance,playing,now());capture?.outputActive=playing
                    if(playing)capture?.outputRoute=event.getInt("route",-1)
                    update(r.id){it.copy(voiceStage=if(playing)"Speaking · local candidate" else "",avatarEnergy=if(playing)it.avatarEnergy else 0f)}}
                "energy"->{presentation.energy(r.id,utterance,event.getFloat("energy"),"PCM / playback-head articulation estimate",MouthPose(event.getFloat("mouthOpen"),event.getFloat("mouthWide"),event.getFloat("mouthRound")));update(r.id){it.copy(avatarEnergy=presentation.snapshot.energy)}}
            }}}
                try {
                    withTimeout(if(trial)30_000L else 8_000L) {
                        select<Unit>{firstPlayback.onAwait{};attempt.onAwait{}}
                    }
                    withTimeout(65_000){attempt.await()}
                }finally{attempt.cancelAndJoin()}
            }
            voiceFallbackReason=if(trial)"Candidate audition only; not promoted" else "None · phone-approved local candidate active"
            playbackMetrics="${result.getString("engine")}; offline=true; initialization=${result.getLong("setupMs")} ms; peak sampled PSS=${result.getLong("peakPssKb")} KiB; thermal=${result.getInt("thermalBefore")}→${result.getInt("thermalAfter")}; first audio=${result.getLong("firstAudioMs")} ms; synthesis=${result.getLong("synthesisMs")} ms; played frames=${result.getLong("playedFrames")}; audio=${result.getLong("audioMs")} ms; PSS=${result.getLong("pssKb")} KiB; route=${result.getInt("route",-1)}; underruns=${result.getInt("underruns")}; trial=$trial"
            if(trial)auditions.record("local",voiceV3Models.fingerprint(),true,result.getLong("setupMs"),result.getLong("firstAudioMs"),result.getLong("playbackMs"),result.getLong("peakPssKb"),"isolated voice process",thermalBefore,thermal.read().thermal)
            return true
        } catch(t:Throwable) {
            if(trial)auditions.record("local",voiceV3Models.fingerprint(),false,null,null,null,null,"isolated voice process",thermalBefore,thermal.read().thermal,if(started)"interrupted after playback" else "no playback")
            if(t is CancellationException && t !is TimeoutCancellationException)throw t
            currentCoroutineContext().ensureActive()
            expressiveFailed=true;voiceFallbackReason="${t.javaClass.simpleName}: ${t.message}"
            CrashJournal.recordTaskFailure(context,"VOICE_V3",t)
            expressive.shutdown()
            if(trial)throw IllegalStateException("Local voice audition failed; compatibility voice remains available: ${t.message}",t)
            // Retry only a clause whose playback never started. Do not repeat partially spoken audio.
            if(started){playbackMetrics="Candidate stopped after playback began; current clause not replayed. Next clause uses Android TTS. $voiceFallbackReason";return true}
            return false
        } finally {
            presentation.playback(r.id,utterance,false,now());capture?.outputActive=false
            update(r.id){it.copy(voiceStage="",avatarEnergy=0f)}
        }
    }
    private suspend fun performVoiceV3Test(r:TaskRequest) {
        val res=thermal.read();require(!res.low && res.thermal<=1){"Let the phone cool before comparing the candidate voice"}
        setTaskMode(r,playback=true);voiceV3Models.requireDirectory()
        expressiveFailed=false
        speakCandidate(r,r.prompt,voiceExpression(r.uri,r.prompt,r.voiceStyle),trial=true)
        update(r.id){it.copy(stage="Candidate audition complete · phone quality acceptance still required")}
    }
    private suspend fun performToneTest(r:TaskRequest) {
        setTaskMode(r,playback=true)
        update(r.id){it.copy(stage="Testing Android media speaker",voiceStage="440 Hz tone")}
        val result=speech.call(Bundle().apply{putString("operation","tone")}){}
        playbackMetrics="Android speaker tone; route=${result.getString("routeLabel")}(${result.getInt("route",-1)}); volume=${result.getInt("streamVolume",-1)}/${result.getInt("streamMax",-1)}; muted=${result.getBoolean("streamMuted")}; encoding=${result.getString("encoding")}; usage=${result.getString("usage")}"
        update(r.id){it.copy(stage="Speaker tone completed",voiceStage="")}
    }
    private suspend fun performVoiceTest(r:TaskRequest) {
        setTaskMode(r,playback=true)
        val text=r.prompt.ifBlank{"Rosalina speaker test."};val before=thermal.read().thermal
        val initialized=now();var initializationMs:Long?=null
        try {
            platformSpeech.prepare();initializationMs=now()-initialized
            val result=speakPlatform(r,text,voiceExpression(r.uri,text,r.voiceStyle))
            auditions.record("Android baseline","",true,initializationMs,result.firstAudioMs,result.playbackMs,null,"system TTS process PSS unavailable",before,thermal.read().thermal)
            update(r.id){it.copy(stage="Baseline audition completed",voiceStage="",avatarEnergy=0f)}
        } catch(t:Throwable){auditions.record("Android baseline","",false,initializationMs,null,null,null,"system TTS process PSS unavailable",before,thermal.read().thermal,"not completed");throw t}
    }
    private suspend fun speakOnline(r:TaskRequest,text:String,expression:VoiceExpression,trial:Boolean=false):Boolean {
        val performance=expression.performance ?:PerformanceState(expression=RosalinaExpression.SPEAKING)
        val utterance=UUID.randomUUID().toString();var started=false;val before=thermal.read().thermal
        presentation.preparingSpeech(r.id,utterance,performance.expression,"Optional online voice",now(),performance)
        try {
            val result=onlineVoice.speak(text,performance){kind,label,event->
                if(presentation.snapshot.taskId==r.id && presentation.snapshot.utteranceId==utterance)when(kind){
                    "stage"->update(r.id){it.copy(voiceStage=label)}
                    "playback"->{val playing=label=="start";if(playing)started=true;presentation.playback(r.id,utterance,playing,now());capture?.outputActive=playing;capture?.outputRoute=event?.getInt("route",-1) ?:-1;update(r.id){it.copy(voiceStage=if(playing)"Speaking · optional online voice" else "",avatarEnergy=0f)}}
                    "energy"->if(event!=null){presentation.energy(r.id,utterance,event.getFloat("energy"),"Online PCM / playback-head estimate",MouthPose(event.getFloat("mouthOpen"),event.getFloat("mouthWide"),event.getFloat("mouthRound")));update(r.id){it.copy(avatarEnergy=presentation.snapshot.energy)}}
                }
            }
            playbackMetrics="${result.getString("engine")}; first playback=${result.getLong("firstAudioMs")} ms; playback=${result.getLong("playbackMs")} ms; response text sent with explicit consent; no microphone upload"
            if(trial)auditions.record("online","",true,result.getLong("setupMs"),result.getLong("firstAudioMs"),result.getLong("playbackMs"),null,"main-process peak not sampled",before,thermal.read().thermal)
            return true
        }catch(t:Throwable){
            if(trial)auditions.record("online","",false,null,null,null,null,"main-process peak not sampled",before,thermal.read().thermal,"provider/player failed")
            if(t is CancellationException)throw t
            currentCoroutineContext().ensureActive();onlineFailed=true;onlineVoice.stop()
            voiceFallbackReason="Online voice ${t.javaClass.simpleName}; local fallback selected"
            if(trial)throw IllegalStateException("Online audition failed. Protected local speech remains available.")
            // Never replay already spoken content; the next clause returns to local output.
            return started
        }finally{presentation.playback(r.id,utterance,false,now());capture?.outputActive=false;update(r.id){it.copy(voiceStage="",avatarEnergy=0f)}}
    }
    private suspend fun performOnlineVoiceTest(r:TaskRequest) {
        check(onlineVoiceSettings.enabled()){"Explicitly enable the configured provider before an online audition"}
        setTaskMode(r,playback=true);speakOnline(r,r.prompt,voiceExpression(r.uri,r.prompt,r.voiceStyle),trial=true)
    }
    private suspend fun performChat(r:TaskRequest,prompt:String,readAloud:Boolean)=coroutineScope {
        require(prompt.isNotBlank() && prompt.length<=8000){"Use a prompt between 1 and 8,000 characters"}
        setTaskMode(r,readAloud)
        val model=models.requirePath(ModelKey.CHAT)
        presentation.processing(r.id,true,now())
        update(r.id){it.copy(answer="",voiceStage="",stage="Preparing response")};addTurn("You",prompt)
        val queue=Channel<String>(64);var shortened=false
        val liveSpeech=voiceActive && prefs.getBoolean("live-voice",true)
        val phone=capability()
        val liveTuning=LiveVoiceTuning(endpointMs=maxOf(prefs.getInt("live-endpoint-ms",820),phone.endpointFloorMs),clauseChars=maxOf(prefs.getInt("live-clause-chars",120),phone.clauseChars))
        fun cutSpoken(value:String)=if(liveSpeech)LiveSpeechChunker.cut(value,liveTuning)else SpeechText.cut(value)
        fun enqueue(text:String){if(text.isNotBlank() && !shortened && !queue.trySend(text).isSuccess){shortened=true;update(r.id){it.copy(voiceStage="Spoken reply shortened · full reply remains in Chat")}}}
        val speechJob=if(readAloud)launch {
            var unavailable=false
            for(text in queue) {
                currentCoroutineContext().ensureActive();if(unavailable)continue
                val expression=voiceExpression(prompt,text)
                val utterance=UUID.randomUUID().toString();var nativePlaybackStarted=false
                try {
                    if(onlineVoiceSettings.enabled() && !onlineFailed && speakOnline(r,text,expression))continue
                    if(candidateEligible()){
                        if(speakCandidate(r,text,expression))continue
                    }else if(expressive.pid>0){expressive.shutdown()}
                    if(usePlatformSpeech()){speakPlatform(r,text,expression);continue}
                    presentation.preparingSpeech(r.id,utterance,(expression.performance ?:PerformanceState()).expression,"Kokoro native",now(),expression.performance ?:PerformanceState())
                    try {
                        val result=speech.call(Bundle().apply{
                            putString("operation","speak");putString("text",text);putInt("speaker",prefs.getInt("speaker",3));putBoolean("conversation",voiceActive);putExpression(expression)
                        }){event->
                            if(presentation.snapshot.taskId==r.id && presentation.snapshot.utteranceId==utterance)when(event.getString("type")) {
                                "stage"->update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
                                "playback"->{val playing=event.getString("text")=="start";if(playing)nativePlaybackStarted=true
                                    presentation.playback(r.id,utterance,playing,now());capture?.outputRoute=event.getInt("route",-1);capture?.outputActive=playing
                                    update(r.id){it.copy(voiceStage=if(playing)"Speaking · ${expression.name}" else "",avatarEnergy=0f)}}
                            }
                        }
                        playbackMetrics="Kokoro Voice V2; ${result.getString("voiceProfile")}; first audio ${result.getLong("firstAudioMs")} ms; audio ${result.getLong("audioMs")} ms; route=${result.getString("routeLabel")}(${result.getInt("route",-1)})"
                    } catch(e:CancellationException){throw e}
                    catch(t:Throwable){
                        if(t is WorkerQuarantined)throw t
                        if(t.message?.contains("SpeechService process exited")==true){
                            markNativeSpeechCrash();voiceFallbackReason="Native speech process exited";speech.shutdown()
                            if(!nativePlaybackStarted)speakPlatform(r,text,expression)
                            else playbackMetrics="Native speech failed after playback began; incomplete clause not replayed. Subsequent clauses use compatibility voice."
                        }else throw t
                    }
                } catch(e:CancellationException){throw e}
                catch(t:Throwable){
                    // Speech errors must not cancel the working text-generation coroutine.
                    unavailable=true;CrashJournal.recordTaskFailure(context,"SPEECH_OUTPUT",t)
                    playbackMetrics="Speech unavailable for this reply: ${t.message}; text preserved"
                    update(r.id){it.copy(voiceStage="Voice unavailable · text preserved",avatarEnergy=0f,error="Speech: ${t.stackTraceToString()}",quarantined=it.quarantined || t is WorkerQuarantined)}
                } finally {presentation.playback(r.id,utterance,false,now());capture?.outputActive=false}
            }
        }else null
        val answer=StringBuilder();var spoken=0;var lastSpeechScan=0L
        chatNeedsReset=true
        try {
            val requested=prefs.getInt("max-tokens",1024);val responseLimit=if(liveSpeech)minOf(learner.responseLimit(requested),phone.liveTokenCap)else minOf(requested,phone.chatTokenCap)
            val result=chat.call(Bundle().apply{putString("model",model.path);putString("prompt",prompt);putString("system",systemPrompt());putInt("maxTokens",responseLimit)}){event->
                when(event.getString("type")) {
                    "stage"->update(r.id){it.copy(stage=event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="Qwen · preserved CPU engine")}
                    "token"->{
                        answer.append(event.getString("text").orEmpty());update(r.id){it.copy(answer=answer.toString(),pid=event.getInt("pid"))}
                        val now=SystemClock.elapsedRealtime()
                        if(readAloud && now-lastSpeechScan>=100 && !shortened) {
                            lastSpeechScan=now;val visible=SpeechText.spoken(answer.toString())
                            if(spoken<=visible.length){var cut=cutSpoken(visible.substring(spoken));while(cut>0){enqueue(visible.substring(spoken,spoken+cut).trim());spoken+=cut;cut=cutSpoken(visible.substring(spoken))}}
                        }
                    }
                }
            }
            chatNeedsReset=false;warmSystem=systemPrompt();lastChatFirstTextMs=result.getLong("firstTextMs")
            chatMetrics="${phone.summary()}; response cap=$responseLimit; Chat warm model=${result.getBoolean("warmModel")}; clean recovery=${result.getBoolean("recovered")}; setup=${result.getLong("modelSetupMs")} ms; first text=${result.getLong("firstTextMs")} ms; response=${result.getLong("responseMs")} ms; characters=${result.getInt("characters")}; emitted text pieces=${result.getInt("textPieces")} (not native token count); chat PSS=${result.getLong("chatPssKb")} KiB"
            if(readAloud){val visible=SpeechText.spoken(answer.toString());val span=if(liveSpeech)220 else 500;while(spoken<visible.length){val end=minOf(visible.length,spoken+span);enqueue(visible.substring(spoken,end).trim());spoken=end}}
        } finally {queue.close();val visible=SpeechText.visible(answer.toString());if(visible.isNotBlank())addTurn("Rosalina",visible)}
        speechJob?.join();presentation.processing(r.id,false,now());update(r.id){it.copy(answer="",voiceStage="",avatarEnergy=0f)}
    }
    private suspend fun performVoice(r:TaskRequest) {
        if(prefs.getBoolean("live-voice",true))performLiveVoice(r)else performClassicVoice(r)
    }
    private suspend fun performClassicVoice(r:TaskRequest) {
        require(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Microphone permission is required"}
        models.requirePath(ModelKey.STT);models.requirePath(ModelKey.CHAT)
        if(!usePlatformSpeech())models.requirePath(ModelKey.TTS)
        voiceActive=true
        val input=VoiceCapture(context,prefs.getBoolean("hands-free",true));capture=input
        var pending:VoiceRecording?=null;var previousOutput=""
        fun manual():Boolean {val value=voiceInterrupt;voiceInterrupt=false;return value}
        fun finished():Boolean {val value=finishListening;finishListening=false;return value}
        try {
            input.start();presentation.microphone(r.id,input.recordingActive,input.isMuted,now());speechMetrics=input.describe()
            while(currentCoroutineContext().isActive) {
                if(pending==null) {
                    presentation.listen(r.id,now())
                    update(r.id){it.copy(stage="Listening · tap Mic when done",voiceStage="",answer="",workHint=input.describe())}
                    pending=input.capture(::manual,::finished,{true}){} ?:return
                }
                val recording=pending!!;pending=null
                presentation.processing(r.id,true,now())
                update(r.id){it.copy(stage="Transcribing on device",backend="Whisper tiny.en · CPU",voiceStage="")}
                val transcript=try {
                    val result=listen.call(Bundle().apply{putString("operation","transcribe");putString("pcm",recording.file.path)}){}
                    speechMetrics="${input.describe()}\nWhisper ${result.getLong("inferenceMs")} ms for ${result.getLong("audioMs")} ms audio"
                    result.getString("transcript").orEmpty()
                }finally{recording.file.delete()}
                if(recording.duringSpeakerOutput && EchoText.resemblesOutput(transcript,previousOutput)) {
                    update(r.id){it.copy(stage="Speaker echo ignored · listening again")};continue
                }
                supervisorScope {
                    val interrupted=AtomicBoolean(false)
                    val responseDone=AtomicBoolean(false)
                    val recorded=AtomicReference<VoiceRecording?>(null)
                    val turnProfile=capability();val response=async{performChat(r,transcript,true)}
                    val next=if(turnProfile.overlapListening)async {
                        input.capture(::manual,::finished,{responseDone.get()}) {
                            interrupted.set(true);response.cancel(CancellationException("User voice interruption"));interruptSpeech()
                            update(r.id){it.copy(stage="Listening · interrupted",voiceStage="")}
                        }?.also{recorded.set(it)}
                    } else null
                    val interruptWatch=if(next==null)launch {while(isActive && !responseDone.get()){if(voiceInterrupt){voiceInterrupt=false;interrupted.set(true);interruptSpeech();response.cancel(CancellationException("User requested interruption"))};delay(60)}}else null
                    try {
                        try{response.await()}catch(e:CancellationException){currentCoroutineContext().ensureActive();if(!interrupted.get())throw e}
                        finally {
                            withContext(NonCancellable) {
                                speech.shutdown();input.outputActive=false
                                if(chatNeedsReset){chat.shutdown();warmSystem=null;chatNeedsReset=false}
                            }
                        }
                        previousOutput=snapshot.lastOrNull{it.first=="Rosalina"}?.second.orEmpty()
                        responseDone.set(true);interruptWatch?.cancelAndJoin()
                        update(r.id){it.copy(stage="Listening · speak or tap Stop to finish",answer="",voiceStage="")}
                        presentation.listen(r.id,now())
                        pending=next?.await() ?: input.capture(::manual,::finished,{true}){}
                        recorded.set(null)
                    } finally {
                        withContext(NonCancellable){response.cancelAndJoin();next?.cancelAndJoin();interruptWatch?.cancelAndJoin();recorded.getAndSet(null)?.file?.delete()}
                    }
                }
                if(pending==null)return
            }
        } finally {pending?.file?.delete();input.close();capture=null;voiceActive=false}
    }

    private suspend fun prepareLiveVoice(r:TaskRequest)=coroutineScope {
        val res=thermal.read();val phone=capability(res)
        require(!res.low && res.available>=3_500_000_000L){"Live Voice needs more free RAM. Close other large apps or use text Chat."}
        val system=systemPrompt() ?:DEFAULT_SYSTEM
        val model=models.requirePath(ModelKey.CHAT)
        val started=SystemClock.elapsedRealtime()
        update(r.id){it.copy(stage="Listening · LIVE · warming Qwen",backend="${phone.label} · staged warmup")}
        chatNeedsReset=true
        val qwen=chat.call(Bundle().apply{putString("operation","prepare");putString("model",model.path);putString("system",system)}){event->
            if(event.getString("type")=="stage")update(r.id){it.copy(stage="Listening · LIVE · "+event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="${phone.label} · Qwen")}
        }
        currentCoroutineContext().ensureActive();delay(120)
        update(r.id){it.copy(stage="Listening · LIVE · warming Whisper",voiceStage="Listening engine")}
        val stt=listen.call(Bundle().apply{putString("operation","prepare")}){event->if(event.getString("type")=="stage")update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}}
        currentCoroutineContext().ensureActive();delay(80)
        update(r.id){it.copy(stage="Listening · LIVE · warming voice",voiceStage="Rosalina voice")}
        val voiceSetup=if(usePlatformSpeech())platformSpeech.prepare() else {
            val tts=speech.call(Bundle().apply{putString("operation","prepare")}){event->if(event.getString("type")=="stage")update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}}
            check(tts.getBoolean("prepared")){"Voice engine did not confirm preparation"}
            "Kokoro setup="+tts.getLong("elapsedMs")+" ms"
        }
        check(stt.getBoolean("prepared") && qwen.getBoolean("prepared")){"Live engines did not confirm preparation"}
        chatNeedsReset=false;warmSystem=system
        liveWarmMetrics="${phone.summary()}; staged warmup "+(SystemClock.elapsedRealtime()-started)+" ms; Qwen setup="+qwen.getLong("modelSetupMs")+" ms; Whisper setup="+stt.getLong("setupMs")+" ms / PSS="+stt.getLong("pssKb")+" KiB; voice="+voiceSetup
        liveMetrics=liveWarmMetrics
        update(r.id){it.copy(stage="Listening · LIVE · speak naturally",voiceStage="",backend="${phone.label} · Live ready")}
    }

    private suspend fun performLiveVoice(r:TaskRequest) = coroutineScope {
        require(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Microphone permission is required"}
        models.requirePath(ModelKey.STT);models.requirePath(ModelKey.CHAT);if(!usePlatformSpeech())models.requirePath(ModelKey.TTS)
        voiceActive=true
        val startProfile=capability();val endpoint=maxOf(prefs.getInt("live-endpoint-ms",820),startProfile.endpointFloorMs).coerceIn(550,1400)
        val input=VoiceCapture(context,prefs.getBoolean("hands-free",true),endpoint);capture=input
        var pending:VoiceRecording?=null;var previousOutput=""
        fun manual():Boolean {val value=voiceInterrupt;voiceInterrupt=false;return value}
        fun finished():Boolean {val value=finishListening;finishListening=false;return value}
        var warm:Deferred<Unit>?=null
        try {
            input.start();presentation.microphone(r.id,input.recordingActive,input.isMuted,now())
            val startup=async { withTimeout(300_000) { prepareLiveVoice(r) } };warm=startup
            speechMetrics=input.describe()+"\nLive endpoint="+endpoint+" ms"
            while(currentCoroutineContext().isActive) {
                if(pending==null) {
                    presentation.listen(r.id,now())
                    update(r.id){it.copy(stage="Listening · LIVE · speak naturally",voiceStage="",answer="",workHint=input.describe())}
                    pending=input.capture(::manual,::finished,{true}){} ?:return@coroutineScope
                }
                startup.await()
                val recording=pending!!;pending=null
                presentation.processing(r.id,true,now())
                update(r.id){it.copy(stage="Understanding you · LIVE",backend="Whisper tiny.en · warm :listen process",voiceStage="")}
                val transcript=try {
                    val result=listen.call(Bundle().apply{putString("operation","transcribe");putString("pcm",recording.file.path)}){}
                    speechMetrics=input.describe()+"\nLive Whisper "+result.getLong("inferenceMs")+" ms for "+result.getLong("audioMs")+" ms audio · PSS "+result.getLong("pssKb")+" KiB"
                    result.getString("transcript").orEmpty()
                }finally{recording.file.delete()}

                if(recording.duringSpeakerOutput && EchoText.resemblesOutput(transcript,previousOutput)) {
                    update(r.id){it.copy(stage="Listening · LIVE · speaker echo ignored")};continue
                }

                supervisorScope {
                    val interrupted=AtomicBoolean(false)
                    val responseDone=AtomicBoolean(false)
                    val recorded=AtomicReference<VoiceRecording?>(null)
                    val turnProfile=capability()
                    val response=async{performChat(r,transcript,true)}
                    val next=if(turnProfile.overlapListening)async {
                        input.capture(::manual,::finished,{responseDone.get()}) {
                            interrupted.set(true)
                            interruptSpeech();response.cancel(CancellationException("User voice interruption"))
                            update(r.id){it.copy(stage="Listening · LIVE · interrupted",voiceStage="",avatarEnergy=0f)}
                        }?.also{recorded.set(it)}
                    } else null
                    val interruptWatch=if(next==null)launch {
                        while(isActive && !responseDone.get()) {
                            if(voiceInterrupt) {
                                voiceInterrupt=false;interrupted.set(true);interruptSpeech()
                                response.cancel(CancellationException("User requested interruption"))
                            }
                            delay(60)
                        }
                    } else null
                    try {
                        try{response.await()}catch(e:CancellationException){currentCoroutineContext().ensureActive();if(!interrupted.get())throw e}
                        finally {
                            withContext(NonCancellable) {
                                input.outputActive=false
                                if(chatNeedsReset){chat.shutdown();warmSystem=null;chatNeedsReset=false}
                            }
                        }
                        learner.recordTurn(interrupted.get(),lastChatFirstTextMs)
                        liveMetrics=liveWarmMetrics+"\n"+learner.snapshot().summary()+"\n"+OnlineEnhancements.state(context,prefs).summary()
                        previousOutput=snapshot.lastOrNull{it.first=="Rosalina"}?.second.orEmpty()
                        responseDone.set(true);interruptWatch?.cancelAndJoin()
                        update(r.id){it.copy(stage="Listening · LIVE · your turn",answer="",voiceStage="",backend="${turnProfile.label} · Live ready")}
                        presentation.listen(r.id,now())
                        pending=next?.await() ?: input.capture(::manual,::finished,{true}){}
                        recorded.set(null)
                    } finally {
                        withContext(NonCancellable){response.cancelAndJoin();next?.cancelAndJoin();interruptWatch?.cancelAndJoin();recorded.getAndSet(null)?.file?.delete()}
                    }
                }
                if(pending==null)return@coroutineScope
            }
        } finally {
            // Do not release input/task ownership until startup children have terminated.
            withContext(NonCancellable) { warm?.cancelAndJoin() }
            pending?.file?.delete();input.close();capture=null;voiceActive=false
        }
    }

    private suspend fun render(r:TaskRequest) {
        setTaskMode(r);update(r.id){it.copy(kind=r.kind)}
        chat.shutdown();warmSystem=null;listen.shutdown();speech.shutdown();chatNeedsReset=false
        val resources=thermal.read()
        require(!resources.low){"Android reports low memory. Available RAM: ${String.format("%.2f",resources.available/1e9)} GB. Close other large applications."}
        require(!ThermalPolicy.blocks(resources.thermal)){"Android reports ${ThermalPolicy.label(resources.thermal)} heat. Cool the phone before retrying."}
        require(r.prompt.isNotBlank()){"Describe what Rosalina should make"}
        val video=r.kind==TaskKind.ANIMATE;val edit=r.kind==TaskKind.EDIT
        val w=if(video)r.width else r.profile.width;val h=if(video)r.height else r.profile.height
        require(r.seconds in listOf(6,8,10));require(r.strength in .1f.. .9f)
        val steps=if(video)12 else r.profile.steps
        val threads=if(video)when(resources.thermal){0->4;1->3;else->2}else if(resources.thermal>=2)minOf(r.profile.threads,2)else r.profile.threads
        val dir=File(context.filesDir,"jobs/${r.id}").apply{mkdirs()};var quarantined=false
        journal.begin(r,resources)
        try {
            File(dir,"prompt.txt").writeText(r.prompt);File(dir,"negative.txt").writeText("")
            val input=if(video || edit){require(r.photo.isNotBlank()){"Choose a photo first"};prepareRgb(File(r.photo),File(dir,"input.rgb"),w,h);File(dir,"input.rgb").path}else "-"
            val output=File(dir,if(video)"output.rvf" else "output.rimg")
            val args=if(video)listOf(models.requirePath(ModelKey.VIDEO).path,models.requirePath(ModelKey.VIDEO_TEXT).path,decoder().path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,input,output.path,w.toString(),h.toString(),(r.seconds*8+1).toString(),steps.toString(),r.seed.toString(),threads.toString())
                else listOf(models.requirePath(ModelKey.IMAGE).path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,input,output.path,w.toString(),h.toString(),steps.toString(),r.seed.toString(),r.strength.toString(),threads.toString())
            val engine=if(video)"motion" else "image"
            fun executable(backend:String)=File(context.applicationInfo.nativeLibraryDir,"librosalina-$engine${if(backend=="cpu")"" else "-vulkan"}.so")
            var selected=if(r.backend=="cpu")"cpu" else "vulkan"
            if(selected=="vulkan") {
                val checkDir=File(dir,"probe").apply{mkdirs()}
                try {
                    val report=withTimeout(20000){NativeWorker(thermal).run(listOf(executable("vulkan").path,"--probe"),checkDir,1){_,_,_,_,pid,tail,_->probeLog=tail;update(r.id){it.copy(stage="Checking GPU computation",pid=pid,logTail=tail,backend="Vulkan · checking actual compute")}}}
                    check(report.contains("@@PROBE PASS")){"GPU did not complete its compute check"};probeLog=report
                } catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();selected="cpu";probeLog+="\nGPU check timed out; CPU fallback selected."}
                catch(e:CancellationException){throw e}
                catch(e:WorkerQuarantined){throw e}
                catch(t:Throwable){if(ThermalPolicy.blocks(thermal.read().thermal))throw t;selected="cpu";probeLog+="\nGPU check failed: ${t.message}; CPU fallback selected."}
            }
            var sampled=false
            suspend fun runBackend(backend:String) {
                update(r.id){it.copy(stage="Preparing",backend=if(backend=="cpu")"CPU" else "Vulkan · compute checked, loading model",percent=null)}
                NativeWorker(thermal).run(listOf(executable(backend).path)+args,dir,steps){stage,p,step,total,pid,tail,res->
                    if(step>0)sampled=true
                    val assigned=Regex("@@BACKEND ([^\\r\\n]+)").findAll(tail).lastOrNull()?.groupValues?.get(1) ?:state.value.backend
                    journal.observe(stage,assigned,res)
                    update(r.id){it.copy(stage=stage,percent=p,step=step,total=total,pid=pid,logTail=tail,lastStage=stage,lastStep=if(step>0)step else it.lastStep,lastTotal=if(total>0)total else it.lastTotal,lastPercent=p ?:it.lastPercent,backend=assigned,workHint=res.control,availableBytes=res.available,totalBytes=res.total,thermal=res.thermal,thermalAt=res.measuredAt)}
                }
            }
            try{runBackend(selected)}catch(e:CancellationException){throw e}catch(e:WorkerQuarantined){throw e}catch(t:Throwable){
                if(selected=="vulkan" && !sampled && !ThermalPolicy.blocks(thermal.read().thermal) && BackendFallback.shouldRetryCpu(t.message.orEmpty(),state.value.logTail,state.value.stage)) {
                    probeLog+="\nVulkan model setup failed at ${state.value.stage}: ${t.message}\nCPU fallback allowed by classified backend failure.\n${state.value.logTail}"
                    output.delete();update(r.id){it.copy(stage="Vulkan backend failure · retrying CPU once")};runBackend("cpu")
                }else throw t
            }
            currentCoroutineContext().ensureActive()
            update(r.id){it.copy(stage=if(video)"Saving MP4" else "Saving image",percent=null,pid=0,workHint="")}
            val results=File(context.filesDir,"results").apply{mkdirs()}
            val result=File(results,"Rosalina-${System.currentTimeMillis()}.${if(video)"mp4" else "png"}");val part=File(result.path+".part")
            try {
                if(video){val spec=MotionSpec(r.seconds,w,h,steps,r.seed);Mp4Encoder.encode(output,part,spec){p->update(r.id){it.copy(stage="Saving MP4 · encoding frames",percent=p)}};verifyVideo(part,spec)}
                else saveImage(output,part,w,h)
                currentCoroutineContext().ensureActive();check(part.renameTo(result)){"Could not finalize result"}
            }finally{part.delete()}
            atomicText(File(result.path+".json"),JSONObject().put("request",r.toString()).put("backend",state.value.backend).put("nativeSource","3f8527a46c54ecf4cb4ed6003da8e8982283c73c").put("device",Build.MODEL).put("elapsedMs",state.value.elapsedMs).toString(2))
            prefs.edit().putString("result",result.path).apply();update(r.id){it.copy(result=result.path,stage="Ready · saved privately",percent=null)}
        }catch(e:WorkerQuarantined){quarantined=true;throw e}finally{if(!quarantined)dir.deleteRecursively()}
    }
    private suspend fun prepareRgb(photo:File,target:File,w:Int,h:Int) {
        currentCoroutineContext().ensureActive()
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(photo.path,options)
        require(options.outWidth>0 && options.outHeight>0){"Selected photo cannot be decoded"}
        options.inJustDecodeBounds=false;options.inSampleSize=maxOf(1,minOf(options.outWidth/w,options.outHeight/h))
        val source=BitmapFactory.decodeFile(photo.path,options) ?:error("Selected photo cannot be decoded")
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val scale=maxOf(w.toFloat()/source.width,h.toFloat()/source.height);val x=(w-source.width*scale)/2;val y=(h-source.height*scale)/2
            Canvas(bitmap).drawBitmap(source,null,RectF(x,y,x+source.width*scale,y+source.height*scale),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
            FileOutputStream(target).buffered().use{out->for(p in pixels){out.write((p shr 16)and 255);out.write((p shr 8)and 255);out.write(p and 255)}}
        }finally{source.recycle();bitmap.recycle()}
    }
    private fun saveImage(raw:File,target:File,w:Int,h:Int) {
        require(raw.length()==16L+w.toLong()*h*3){"Image output is incomplete"}
        val bytes=raw.readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x474d4952 && b.int==w && b.int==h && b.int==3){"Unexpected native image header"}
        val pixels=IntArray(w*h){i->val p=16+i*3;Color.rgb(bytes[p].toInt()and 255,bytes[p+1].toInt()and 255,bytes[p+2].toInt()and 255)}
        val image=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        try{FileOutputStream(target).use{check(image.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{image.recycle()}
    }
    private fun verifyVideo(file:File,spec:MotionSpec) {
        val e=MediaExtractor()
        try {
            e.setDataSource(file.path);val track=(0 until e.trackCount).firstOrNull{e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true} ?:error("Generated MP4 has no video track")
            e.selectTrack(track);var count=0;var previous=-1L
            while(e.sampleTime>=0){val now=e.sampleTime;require(now>previous){"MP4 timestamps are not increasing"};previous=now;count++;if(!e.advance())break}
            require(count==spec.exportFrames){"MP4 frame count mismatch: $count instead of ${spec.exportFrames}"}
        }finally{e.release()}
    }
    private suspend fun decoder():File {
        val expected="b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325"
        val file=File(context.filesDir,"models/decoder/taew2_2.safetensors");file.parentFile?.mkdirs()
        fun digest(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{input->val b=ByteArray(262144);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};return hex(md.digest())}
        if(file.exists() && digest(file)==expected)return file
        val part=File(file.path+".part")
        try{context.assets.open("taew2_2.safetensors").use{input->FileOutputStream(part).use{input.copyTo(it);it.fd.sync()}};require(digest(part)==expected){"Bundled decoder checksum mismatch"};check(part.renameTo(file))}finally{part.delete()}
        return file
    }
    fun importPhoto(uri:Uri) {
        photoImportJob?.cancel()
        photoImportJob=scope.launch(Dispatchers.IO) {
            var file:File?=null;var committed=false
            try {
                val source=ImageDecoder.createSource(context.contentResolver,uri)
                val bitmap=ImageDecoder.decodeBitmap(source){decoder,info,_->
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val maximum=maxOf(info.size.width,info.size.height)
                    if(maximum>2048)decoder.setTargetSize((info.size.width*2048/maximum).coerceAtLeast(1),(info.size.height*2048/maximum).coerceAtLeast(1))
                }
                val output=File(context.filesDir,"photos/${UUID.randomUUID()}.png");file=output;output.parentFile?.mkdirs()
                try{FileOutputStream(output).use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{bitmap.recycle()}
                ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    ensureActive();prefs.edit().putString("photo",output.path).apply();committed=true;notice("Photo selected")
                }
            }catch(e:CancellationException){throw e}
            catch(t:Throwable){notice("Photo import failed: ${t.message}")}
            finally{if(!committed)file?.delete()}
        }
    }
    fun diagnostics(s:TaskState=state.value,includeExits:Boolean=false):String {
        val prior=if(s.id.isBlank())runCatching{File(context.filesDir,"last-diagnostics.txt").readText().takeLast(50000)}.getOrDefault("")else ""
        return "ROSALINA UNIFIED CANDIDATE\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage: ${context.packageName}\n$deviceFacts\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.SDK_INT}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nPresentation: ${presentation.summary()}\nVoice V3: ${voiceV3Summary()}\nAuditions: ${voiceAuditionSummary()}\nAvatar: ${AvatarAsset.status}; state=${AvatarStateResolver.resolve(s)}\nUI transition: ${prefs.getString("last-ui-transition","none")}\nCurrent RAM total: ${s.totalBytes}; available: ${s.availableBytes}\nCurrent thermal: ${s.thermal} ${ThermalPolicy.label(s.thermal)}; sampled elapsedRealtime=${s.thermalAt}\nPhone capability: ${runCatching{capability().summary()}.getOrDefault("unavailable")}\n${thermal.describe()}\nTask: ${s.id} ${s.kind}; stage: ${s.stage}; PID: ${s.pid}; last PID: ${s.lastPid}\nLast native stage: ${s.lastStage}; last sampling: ${s.lastStep}/${s.lastTotal}\nBackend: ${s.backend}\nElapsed: ${s.elapsedMs} ms\nWork pacing: ${s.workHint}\n$chatMetrics\nInput: $speechMetrics\nOutput: $playbackMetrics\n$liveMetrics\n${learner.snapshot().summary()}\n${onlineVoiceSettings.summary()}\n${models.diagnostic()}\nError: ${s.error}\nGPU compute check:\n$probeLog\nNative log tail:\n${s.logTail}\n${journal.describe()}\n${if(includeExits)CrashJournal.describe(context) else ""}\nSamsung output acceptance: candidate; not established by CI\n${if(prior.isBlank())"" else "Previous recorded diagnostics:\n$prior"}"
    }
}
