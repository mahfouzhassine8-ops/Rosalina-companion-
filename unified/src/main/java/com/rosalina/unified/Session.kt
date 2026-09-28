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
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RosalinaApp:Application(){override fun onCreate(){super.onCreate();if(getProcessName()==packageName)Session.get(this)}}
internal class Session private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:Session?=null
        fun get(context:Context):Session=instance ?:synchronized(this){instance ?:Session(context.applicationContext).also{instance=it}}
        const val DEFAULT_SYSTEM="You are Rosalina, a private on-device assistant. Be helpful, practical, direct, and clear. Do not claim internet access. The application can route explicit create, edit and animate requests to its local engines."
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
    private val learner=LiveLearner(prefs)
    private val transcriptFile=File(context.filesDir,"conversation.json")
    private val turns=mutableListOf<Pair<String,String>>()
    @Volatile private var snapshot:List<Pair<String,String>> = emptyList()
    private val historyReady=CompletableDeferred<Unit>()
    private val mutable=MutableStateFlow(TaskState(result=prefs.getString("result","").orEmpty(),stage=if(prefs.getString("active-id","").isNullOrBlank())"Ready" else "Previous work was interrupted. No task restarted."))
    val state:StateFlow<TaskState> = mutable.asStateFlow()
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
    private var liveMetrics=""
    private var chatMetrics=""
    @Volatile private var lastChatFirstTextMs=0L
    private var probeLog=""
    private val deviceFacts by lazy{DeviceFacts.describe(context)}
    init {
        prefs.edit().remove("active-id").apply()
        scope.launch(Dispatchers.IO) {
            runCatching{val a=JSONArray(transcriptFile.readText());synchronized(this@Session){for(i in 0 until a.length()){val o=a.getJSONObject(i);turns.add(o.getString("role") to o.getString("text"))};snapshot=turns.toList()}}
            historyReady.complete(Unit);mutable.update{it.copy(revision=it.revision+1)};refreshResources()
        }
    }
    fun transcript():List<Pair<String,String>> = snapshot
    /** Invoked only when Chat is visible. Uses the same lease/FGS as every major engine. */
    fun prepareChat() {
        if(state.value.busy || state.value.quarantined || models.path(ModelKey.CHAT)==null)return
        if(chat.pid>0 && warmSystem==prefs.getString("system",DEFAULT_SYSTEM))return
        if(ThermalPolicy.blocks(state.value.thermal))return
        begin(TaskRequest(kind=TaskKind.CHAT,modelKey="prepare"))
    }
    private suspend fun warmChat(r:TaskRequest) {
        val res=thermal.read()
        require(!res.low && !ThermalPolicy.blocks(res.thermal)){"Chat preparation deferred: Android reports memory or thermal pressure"}
        chatNeedsReset=true
        val result=chat.call(Bundle().apply{putString("operation","prepare");putString("model",models.requirePath(ModelKey.CHAT).path);putString("system",prefs.getString("system",DEFAULT_SYSTEM))}){event->
            if(event.getString("type")=="stage")update(r.id){it.copy(stage=event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="Qwen · preparing preserved CPU engine")}
        }
        check(result.getBoolean("prepared")){"Chat model did not confirm preparation"}
        chatNeedsReset=false;warmSystem=prefs.getString("system",DEFAULT_SYSTEM)
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
    fun liveLearningSummary()=learner.snapshot().summary()
    fun resetLiveLearning(){learner.reset();notice("Adaptive Live learning reset")}
    fun refreshResources(){val r=thermal.read();mutable.update{it.copy(thermal=r.thermal,thermalAt=r.measuredAt,availableBytes=r.available,totalBytes=r.total)}}
    @Synchronized fun begin(r:TaskRequest):Boolean {
        if(state.value.quarantined){notice("Force-stop Rosalina before starting another worker");return false}
        if(!lease.acquire(r.id))return false
        request=r;stopReason="";finishListening=false;voiceInterrupt=false;probeLog=""
        mutable.value=TaskState(id=r.id,kind=r.kind,busy=true,stage="Preparing",result=state.value.result,revision=state.value.revision,eta=if(r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE))"ETA calibrating…" else "")
        prefs.edit().putString("active-id",r.id).apply()
        try{ContextCompat.startForegroundService(context,Intent(context,GenerationService::class.java).putExtra("task",r.id))}
        catch(t:Throwable){lease.release(r.id);request=null;prefs.edit().remove("active-id").apply();mutable.update{it.copy(busy=false,error=t.toString(),stage="Android could not start foreground work")};return false}
        return true
    }
    fun currentRequest()=request
    fun stop(reason:String="Stopped by you") {
        if(!state.value.busy)return
        stopReason=reason;mutable.update{it.copy(stopping=true,stage="Stopping…",percent=null,eta="")}
        speech.interruptNow();activeJob?.cancel(CancellationException(reason))
    }
    fun interruptAndListen() {
        if(voiceActive){voiceInterrupt=true;return}
        scope.launch {
            if(state.value.busy){stop();withTimeoutOrNull(8000){activeJob?.join()}}
            if(!state.value.busy)begin(TaskRequest(kind=TaskKind.VOICE))
        }
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
                    val res=thermal.read();update(r.id){it.copy(elapsedMs=SystemClock.elapsedRealtime()-start,availableBytes=res.available,totalBytes=res.total,thermal=res.thermal,thermalAt=res.measuredAt)}
                    if(ThermalPolicy.blocks(res.thermal) && r.kind!=TaskKind.IMPORT){stop("Stopped safely: Android reported ${ThermalPolicy.label(res.thermal)} heat");break}
                    if(SystemClock.elapsedRealtime()-savedAt>=10000){runCatching{atomicText(File(context.filesDir,"last-diagnostics.txt"),diagnostics())};savedAt=SystemClock.elapsedRealtime()}
                    delay(1000)
                }
            }
            try {
                historyReady.await()
                when(r.kind) {
                    TaskKind.IMPORT->{chat.shutdown();warmSystem=null;listen.shutdown();speech.shutdown();models.import(ModelKey.valueOf(r.modelKey),Uri.parse(r.uri)){text,p->update(r.id){it.copy(stage=text,percent=p)}}}
                    TaskKind.CHAT->if(r.modelKey=="prepare")warmChat(r)else performChat(r,r.prompt,prefs.getBoolean("spoken-replies",false))
                    TaskKind.VOICE->performVoice(r)
                    else->render(r)
                }
            }catch(t:Throwable){failure=t}
            finally {
                withContext(NonCancellable) {
                    ticker.cancelAndJoin();capture?.close();capture=null;voiceActive=false
                    var finalFailure=failure
                    try{listen.shutdown()}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    try{speech.shutdown()}catch(t:Throwable){finalFailure=if(t is WorkerQuarantined)t else finalFailure ?:t}
                    if(chatNeedsReset || r.kind !in listOf(TaskKind.CHAT,TaskKind.VOICE) || thermal.read().low) {
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
        val done=old.copy(busy=false,stopping=false,stage=message,percent=null,step=0,total=0,eta="",pid=0,voiceStage="",workHint="",avatarEnergy=0f,error=if(failure!=null && failure !is CancellationException)failure.stackTraceToString()else old.error,quarantined=old.quarantined || failure is WorkerQuarantined)
        service.finishTask(r.id)
        val report=diagnostics(done);withContext(Dispatchers.IO){runCatching{atomicText(File(context.filesDir,"last-diagnostics.txt"),report)}}
        taskService=null;request=null;activeJob=null;prefs.edit().remove("active-id").apply();lease.release(r.id);mutable.value=done
    }
    private suspend fun setTaskMode(r:TaskRequest,playback:Boolean=false)=withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive();taskService?.setMode(r.id,r.kind,microphone=voiceActive,playback=playback)
    }
    private fun voiceExpression(userText:String,spokenText:String):VoiceExpression =
        VoiceExpressionResolver.resolve(
            mode=prefs.getString("voice-style","adaptive") ?: "adaptive",
            intensity=prefs.getInt("voice-expression",75)/100f,
            pitchTrim=prefs.getInt("voice-pitch",0)/10f,
            breathTrim=prefs.getInt("voice-breath",0)/100f,
            toneTrim=prefs.getInt("voice-tone",0)/100f,
            raspTrim=prefs.getInt("voice-rasp",0)/100f,
            energyTrim=prefs.getInt("voice-energy",0)/100f,
            pace=prefs.getInt("voice-pace",100)/100f,
            userText=userText,
            spokenText=spokenText,
            realismGuard=prefs.getBoolean("voice-realism",true)
        )
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
    private suspend fun performChat(r:TaskRequest,prompt:String,readAloud:Boolean)=coroutineScope {
        require(prompt.isNotBlank() && prompt.length<=8000){"Use a prompt between 1 and 8,000 characters"}
        setTaskMode(r,readAloud)
        val model=models.requirePath(ModelKey.CHAT)
        update(r.id){it.copy(answer="",voiceStage="",stage="Preparing response")};addTurn("You",prompt)
        val queue=Channel<String>(64);var shortened=false
        val liveSpeech=voiceActive && prefs.getBoolean("live-voice",true)
        val liveTuning=LiveVoiceTuning(endpointMs=prefs.getInt("live-endpoint-ms",820),clauseChars=prefs.getInt("live-clause-chars",120))
        fun cutSpoken(value:String)=if(liveSpeech)LiveSpeechChunker.cut(value,liveTuning)else SpeechText.cut(value)
        fun enqueue(text:String){if(text.isNotBlank() && !shortened && !queue.trySend(text).isSuccess){shortened=true;update(r.id){it.copy(voiceStage="Spoken reply shortened · full reply remains in Chat")}}}
        val speechJob=if(readAloud)launch {
            var unavailable=false
            for(text in queue) {
                if(unavailable)continue
                var attempt=0
                var completed=false
                while(!completed && attempt<2 && currentCoroutineContext().isActive) {
                    try {
                        attempt++
                        val expression=voiceExpression(prompt,text)
                        val result=speech.call(Bundle().apply{
                            putString("operation","speak");putString("text",text);putInt("speaker",prefs.getInt("speaker",3));putBoolean("conversation",voiceActive)
                            putExpression(expression)
                        }){event->
                            when(event.getString("type")) {
                                "stage"->update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
                                "playback"->{capture?.outputRoute=event.getInt("route",-1);capture?.outputActive=event.getString("text")=="start"}
                                "avatar"->update(r.id){it.copy(avatarEnergy=event.getFloat("energy",0f).coerceIn(0f,1f))}
                            }
                        }
                        val wasInterrupted=result.getBoolean("interrupted")
                        speechMetrics="Kokoro Voice V2; ${result.getString("voiceProfile")}; pitch path=${if(result.getBoolean("pitchApplied"))"Android pitch-preserving playback" else "neutral fallback"}; first audio ${result.getLong("firstAudioMs")} ms; audio ${result.getLong("audioMs")} ms; elapsed ${result.getLong("elapsedMs")} ms; interrupted=$wasInterrupted; restart attempts=${attempt-1}"
                        if(wasInterrupted){unavailable=true;update(r.id){it.copy(voiceStage="",avatarEnergy=0f)}}
                        completed=true
                    }catch(e:CancellationException){throw e}
                    catch(t:Throwable){
                        if(!currentCoroutineContext().isActive || state.value.stopping)throw CancellationException("Speech stopped").apply{initCause(t)}
                        val processExit=t.message?.contains("SpeechService process exited")==true
                        if(liveSpeech && processExit && attempt<2) {
                            speechMetrics="Speech process exited · restarting once"
                            update(r.id){it.copy(voiceStage="Restarting Rosalina voice")}
                            try{speech.shutdown()}catch(_:Throwable){}
                            delay(80)
                        } else {
                            unavailable=true;completed=true
                            update(r.id){it.copy(voiceStage="Voice unavailable · ${t.message}",avatarEnergy=0f,error="Speech: ${t.stackTraceToString()}")}
                            try{speech.shutdown()}catch(_:Throwable){}
                        }
                    } finally {capture?.outputActive=false}
                }
            }
        }else null
        val answer=StringBuilder();var spoken=0;var lastSpeechScan=0L
        chatNeedsReset=true
        try {
            val responseLimit=if(liveSpeech)learner.responseLimit(prefs.getInt("max-tokens",1024))else prefs.getInt("max-tokens",1024)
            val result=chat.call(Bundle().apply{putString("model",model.path);putString("prompt",prompt);putString("system",prefs.getString("system",DEFAULT_SYSTEM));putInt("maxTokens",responseLimit)}){event->
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
            chatNeedsReset=false;warmSystem=prefs.getString("system",DEFAULT_SYSTEM);lastChatFirstTextMs=result.getLong("firstTextMs")
            chatMetrics="Chat warm model=${result.getBoolean("warmModel")}; clean recovery=${result.getBoolean("recovered")}; setup=${result.getLong("modelSetupMs")} ms; first text=${result.getLong("firstTextMs")} ms; response=${result.getLong("responseMs")} ms; characters=${result.getInt("characters")}; emitted text pieces=${result.getInt("textPieces")} (not native token count); chat PSS=${result.getLong("chatPssKb")} KiB"
            if(readAloud){val visible=SpeechText.spoken(answer.toString());val span=if(liveSpeech)220 else 500;while(spoken<visible.length){val end=minOf(visible.length,spoken+span);enqueue(visible.substring(spoken,end).trim());spoken=end}}
        } finally {queue.close();val visible=SpeechText.visible(answer.toString());if(visible.isNotBlank())addTurn("Rosalina",visible)}
        speechJob?.join();update(r.id){it.copy(answer="",voiceStage="",avatarEnergy=0f)}
    }
    private suspend fun performVoice(r:TaskRequest) {
        if(prefs.getBoolean("live-voice",true))performLiveVoice(r)else performClassicVoice(r)
    }
    private suspend fun performClassicVoice(r:TaskRequest) {
        require(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Microphone permission is required"}
        models.requirePath(ModelKey.STT);models.requirePath(ModelKey.TTS);models.requirePath(ModelKey.CHAT)
        speech.shutdown();voiceActive=true
        val input=VoiceCapture(context,prefs.getBoolean("hands-free",true));capture=input
        var pending:VoiceRecording?=null;var previousOutput=""
        fun manual():Boolean {val value=voiceInterrupt;voiceInterrupt=false;return value}
        fun finished():Boolean {val value=finishListening;finishListening=false;return value}
        try {
            input.start();speechMetrics=input.describe()
            while(currentCoroutineContext().isActive) {
                if(pending==null) {
                    update(r.id){it.copy(stage="Listening · tap Mic when done",voiceStage="",answer="",workHint=input.describe())}
                    pending=input.capture(::manual,::finished,{true}){} ?:return
                }
                val recording=pending!!;pending=null
                update(r.id){it.copy(stage="Transcribing on device",backend="Whisper tiny.en · CPU",voiceStage="")}
                val transcript=try {
                    val result=speech.call(Bundle().apply{putString("operation","transcribe");putString("pcm",recording.file.path)}){}
                    speechMetrics="${input.describe()}\nWhisper ${result.getLong("inferenceMs")} ms for ${result.getLong("audioMs")} ms audio"
                    result.getString("transcript").orEmpty()
                }finally{recording.file.delete();speech.shutdown()}
                if(recording.duringSpeakerOutput && EchoText.resemblesOutput(transcript,previousOutput)) {
                    update(r.id){it.copy(stage="Speaker echo ignored · listening again")};continue
                }
                val routed=Route.kind(transcript)
                if(routed!=TaskKind.CHAT) {
                    addTurn("You",transcript);input.close();capture=null;voiceActive=false
                    val aspect=prefs.getString("aspect","256×256").orEmpty().split('×')
                    render(r.copy(kind=routed,prompt=transcript,photo=prefs.getString("photo","").orEmpty(),seconds=Route.seconds(transcript),width=aspect.getOrNull(0)?.toIntOrNull() ?:256,height=aspect.getOrNull(1)?.toIntOrNull() ?:256,backend=prefs.getString("render-backend","auto") ?:"auto",profile=if(prefs.getBoolean("standard",false))RenderProfile.Standard else RenderProfile.Draft));return
                }
                supervisorScope {
                    val interrupted=AtomicBoolean(false)
                    val responseDone=AtomicBoolean(false)
                    val recorded=AtomicReference<VoiceRecording?>(null)
                    val response=async{performChat(r,transcript,true)}
                    val next=async {
                        input.capture(::manual,::finished,{responseDone.get()}) {
                            interrupted.set(true);response.cancel(CancellationException("User voice interruption"));speech.interruptNow()
                            update(r.id){it.copy(stage="Listening · interrupted",voiceStage="")}
                        }?.also{recorded.set(it)}
                    }
                    try {
                        try{response.await()}catch(e:CancellationException){currentCoroutineContext().ensureActive();if(!interrupted.get())throw e}
                        finally {
                            withContext(NonCancellable) {
                                speech.shutdown();input.outputActive=false
                                if(chatNeedsReset){chat.shutdown();warmSystem=null;chatNeedsReset=false}
                            }
                        }
                        previousOutput=snapshot.lastOrNull{it.first=="Rosalina"}?.second.orEmpty()
                        responseDone.set(true)
                        update(r.id){it.copy(stage="Listening · speak or tap Stop to finish",answer="",voiceStage="")}
                        pending=next.await();recorded.set(null)
                    } finally {
                        withContext(NonCancellable){response.cancelAndJoin();next.cancelAndJoin();recorded.getAndSet(null)?.file?.delete()}
                    }
                }
                if(pending==null)return
            }
        } finally {pending?.file?.delete();input.close();capture=null;voiceActive=false}
    }

    private suspend fun prepareLiveVoice(r:TaskRequest)=coroutineScope {
        val res=thermal.read()
        require(!res.low && res.available>=3_500_000_000L){"Live Voice needs more free RAM. Close other large apps or turn off Live conversation mode to use Classic Voice."}
        val system=prefs.getString("system",DEFAULT_SYSTEM) ?:DEFAULT_SYSTEM
        val model=models.requirePath(ModelKey.CHAT)
        val started=SystemClock.elapsedRealtime()
        update(r.id){it.copy(stage="Listening · LIVE · warming local voice",backend="Live coordinator · Qwen + Whisper + Kokoro")}

        val listenWarm=async {
            listen.call(Bundle().apply{putString("operation","prepare")}){event->
                if(event.getString("type")=="stage")update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
            }
        }
        val speechWarm=async {
            speech.call(Bundle().apply{putString("operation","prepare")}){event->
                if(event.getString("type")=="stage")update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
            }
        }
        val chatWarm=async {
            chat.call(Bundle().apply{
                putString("operation","prepare");putString("model",model.path);putString("system",system)
            }){event->
                if(event.getString("type")=="stage")update(r.id){it.copy(stage="Listening · LIVE · "+event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="Live coordinator · Qwen + Whisper + Kokoro")}
            }
        }
        val stt=listenWarm.await();val tts=speechWarm.await();val qwen=chatWarm.await()
        warmSystem=system
        liveMetrics="Live Voice = cascaded local full-duplex coordinator (not audio-native); warmup "+(SystemClock.elapsedRealtime()-started)+" ms; Qwen setup="+qwen.getLong("modelSetupMs")+" ms; Whisper setup="+stt.getLong("setupMs")+" ms / PSS="+stt.getLong("pssKb")+" KiB; voice setup="+tts.getLong("elapsedMs")+" ms"
        update(r.id){it.copy(stage="Listening · LIVE · speak naturally",voiceStage="",backend="Live coordinator · all engines warm")}
    }

    private suspend fun performLiveVoice(r:TaskRequest) {
        require(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Microphone permission is required"}
        models.requirePath(ModelKey.STT);models.requirePath(ModelKey.TTS);models.requirePath(ModelKey.CHAT)
        voiceActive=true
        val endpoint=prefs.getInt("live-endpoint-ms",820).coerceIn(550,1400)
        val input=VoiceCapture(context,prefs.getBoolean("hands-free",true),endpoint);capture=input
        var pending:VoiceRecording?=null;var previousOutput=""
        fun manual():Boolean {val value=voiceInterrupt;voiceInterrupt=false;return value}
        fun finished():Boolean {val value=finishListening;finishListening=false;return value}
        try {
            input.start()
            val warm=CoroutineScope(currentCoroutineContext()).async{prepareLiveVoice(r)}
            speechMetrics=input.describe()+"\nLive endpoint="+endpoint+" ms"
            while(currentCoroutineContext().isActive) {
                if(pending==null) {
                    update(r.id){it.copy(stage="Listening · LIVE · speak naturally",voiceStage="",answer="",workHint=input.describe())}
                    pending=input.capture(::manual,::finished,{true}){} ?:return
                }
                warm.await()
                val recording=pending!!;pending=null
                update(r.id){it.copy(stage="Understanding you · LIVE",backend="Whisper tiny.en · warm :listen process",voiceStage="")}
                val transcript=try {
                    val result=listen.call(Bundle().apply{putString("operation","transcribe");putString("pcm",recording.file.path)}){}
                    speechMetrics=input.describe()+"\nLive Whisper "+result.getLong("inferenceMs")+" ms for "+result.getLong("audioMs")+" ms audio · PSS "+result.getLong("pssKb")+" KiB"
                    result.getString("transcript").orEmpty()
                }finally{recording.file.delete()}

                if(recording.duringSpeakerOutput && EchoText.resemblesOutput(transcript,previousOutput)) {
                    update(r.id){it.copy(stage="Listening · LIVE · speaker echo ignored")};continue
                }
                val routed=Route.kind(transcript)
                if(routed!=TaskKind.CHAT) {
                    addTurn("You",transcript);input.close();capture=null;voiceActive=false
                    val aspect=prefs.getString("aspect","256×256").orEmpty().split('×')
                    render(r.copy(kind=routed,prompt=transcript,photo=prefs.getString("photo","").orEmpty(),seconds=Route.seconds(transcript),width=aspect.getOrNull(0)?.toIntOrNull() ?:256,height=aspect.getOrNull(1)?.toIntOrNull() ?:256,backend=prefs.getString("render-backend","auto") ?:"auto",profile=if(prefs.getBoolean("standard",false))RenderProfile.Standard else RenderProfile.Draft));return
                }

                supervisorScope {
                    val interrupted=AtomicBoolean(false)
                    val responseDone=AtomicBoolean(false)
                    val recorded=AtomicReference<VoiceRecording?>(null)
                    val response=async{performChat(r,transcript,true)}
                    val next=async {
                        input.capture(::manual,::finished,{responseDone.get()}) {
                            interrupted.set(true)
                            speech.interruptNow()
                            update(r.id){it.copy(stage="Listening · LIVE · interrupted · finishing thought silently",voiceStage="",avatarEnergy=0f)}
                        }?.also{recorded.set(it)}
                    }
                    try {
                        try{response.await()}catch(e:CancellationException){currentCoroutineContext().ensureActive();if(!interrupted.get())throw e}
                        finally {
                            withContext(NonCancellable) {
                                input.outputActive=false
                                if(chatNeedsReset){chat.shutdown();warmSystem=null;chatNeedsReset=false}
                            }
                        }
                        learner.recordTurn(interrupted.get(),lastChatFirstTextMs)
                        liveMetrics=liveMetrics+"\n"+learner.snapshot().summary()+"\n"+OnlineEnhancements.state(context,prefs).summary()
                        previousOutput=snapshot.lastOrNull{it.first=="Rosalina"}?.second.orEmpty()
                        responseDone.set(true)
                        update(r.id){it.copy(stage="Listening · LIVE · your turn",answer="",voiceStage="",backend="Live coordinator · warm")}
                        pending=next.await();recorded.set(null)
                    } finally {
                        withContext(NonCancellable){response.cancelAndJoin();next.cancelAndJoin();recorded.getAndSet(null)?.file?.delete()}
                    }
                }
                if(pending==null)return
            }
        } finally {pending?.file?.delete();input.close();capture=null;voiceActive=false}
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
        scope.launch(Dispatchers.IO) {
            try {
                val source=ImageDecoder.createSource(context.contentResolver,uri)
                val bitmap=ImageDecoder.decodeBitmap(source){decoder,info,_->decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;val max=maxOf(info.size.width,info.size.height);if(max>2048)decoder.setTargetSize(info.size.width*2048/max,info.size.height*2048/max)}
                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.png");file.parentFile?.mkdirs()
                try{FileOutputStream(file).use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{bitmap.recycle()}
                prefs.edit().putString("photo",file.path).apply();notice("Photo selected")
            }catch(t:Throwable){notice("Photo import failed: ${t.message}")}
        }
    }
    fun diagnostics(s:TaskState=state.value):String {
        val prior=if(s.id.isBlank())runCatching{File(context.filesDir,"last-diagnostics.txt").readText().takeLast(50000)}.getOrDefault("")else ""
        return "ROSALINA UNIFIED CANDIDATE\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage: ${context.packageName}\n$deviceFacts\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.SDK_INT}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nCurrent RAM total: ${s.totalBytes}; available: ${s.availableBytes}\nCurrent thermal: ${s.thermal} ${ThermalPolicy.label(s.thermal)}; sampled elapsedRealtime=${s.thermalAt}\n${thermal.describe()}\nTask: ${s.id} ${s.kind}; stage: ${s.stage}; PID: ${s.pid}; last PID: ${s.lastPid}\nLast native stage: ${s.lastStage}; last sampling: ${s.lastStep}/${s.lastTotal}\nBackend: ${s.backend}\nElapsed: ${s.elapsedMs} ms\nWork pacing: ${s.workHint}\n$chatMetrics\n$speechMetrics\n$liveMetrics\n${learner.snapshot().summary()}\n${OnlineEnhancements.state(context,prefs).summary()}\n${models.diagnostic()}\nError: ${s.error}\nGPU compute check:\n$probeLog\nNative log tail:\n${s.logTail}\n${journal.describe()}\nSamsung output acceptance: candidate; not established by CI\n${if(prior.isBlank())"" else "Previous recorded diagnostics:\n$prior"}"
    }
}
