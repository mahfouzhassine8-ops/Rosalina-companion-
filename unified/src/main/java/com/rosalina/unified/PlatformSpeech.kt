package com.rosalina.unified

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object SpeechCompatibility {
    fun preferPlatform(manufacturer:String,sdk:Int,nativeCrashed:Boolean=false):Boolean =
        nativeCrashed || (manufacturer.equals("samsung",ignoreCase=true) && sdk>=36)
}
internal data class PlatformSpeechResult(
    val engine:String,val voice:String,val elapsedMs:Long,
    val mediaVolume:Int,val mediaMax:Int,val mediaMuted:Boolean,
    val firstAudioMs:Long=-1,val playbackMs:Long=0,val offline:Boolean=false,val lipInput:String="unavailable"
)

/** Keep 10015's direct Android MEDIA/speak path. Observe it; do not replace it with a new player. */
internal class PlatformSpeech(private val context:Context,private val testEnginePackage:String?=null) {
    private val mutex=Mutex()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var engine:TextToSpeech?=null
    @Volatile private var ready=false
    private var defaultVoiceName=""
    private val active=AtomicReference("")
    private val activeFocus=AtomicReference<AudioFocusRequest?>(null)
    @Volatile private var completion:CompletableDeferred<Unit>?=null

    private suspend fun ensureEngine():TextToSpeech {
        engine?.takeIf{ready}?.let{return it}
        val status=CompletableDeferred<Int>()
        val created=withContext(Dispatchers.Main.immediate) {
            if(testEnginePackage==null)TextToSpeech(context.applicationContext){code->status.complete(code)}
            else TextToSpeech(context.applicationContext,{code->status.complete(code)},testEnginePackage)
        }
        try {
            val code=withTimeout(8000){status.await()}
            check(code==TextToSpeech.SUCCESS){"Android system speech engine failed to initialize: $code"}
            withContext(Dispatchers.Main.immediate) {
                created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                if(created.isLanguageAvailable(Locale.US)>=TextToSpeech.LANG_AVAILABLE)created.language=Locale.US
                // A system engine is not automatically offline. Retain its current offline voice when possible.
                val current=created.voice
                val usable=current!=null && !current.isNetworkConnectionRequired &&
                    !current.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                if(!usable) {
                    val offline=created.voices.orEmpty().filter{!it.isNetworkConnectionRequired &&
                        it.locale.language=="en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}
                        .sortedWith(compareByDescending<android.speech.tts.Voice>{it.quality}.thenBy{it.latency}.thenBy{it.name}).firstOrNull()
                    check(offline!=null){"Install an offline English voice in Android Text-to-speech settings. No network voice was used."}
                    check(created.setVoice(offline)==TextToSpeech.SUCCESS){"Android could not select the installed offline voice"}
                }
                check(created.voice?.isNetworkConnectionRequired==false){"An offline system voice could not be verified"}
            }
            defaultVoiceName=created.voice?.name.orEmpty()
            engine=created;ready=true
            return created
        } catch(t:Throwable) {
            withContext(NonCancellable+Dispatchers.Main.immediate){created.shutdown()}
            throw t
        }
    }
    suspend fun installedVoiceNames():List<String> = mutex.withLock {
        ensureEngine().voices.orEmpty().filter{!it.isNetworkConnectionRequired && it.locale.language=="en" &&
            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}
            .sortedWith(compareByDescending<android.speech.tts.Voice>{it.quality}.thenBy{it.name})
            .map{it.name}
    }
    suspend fun prepare():String=mutex.withLock {
        val e=ensureEngine()
        "Android system TTS · engine=${e.defaultEngine ?: "default"} · voice=${e.voice?.name ?: "default"} · offline=${e.voice?.isNetworkConnectionRequired==false}"
    }
    suspend fun speak(text:String,pace:Float,onStart:()->Unit={},onDone:()->Unit={},
        observe:(SpeechObservation)->Unit={},requestUtterance:String?=null,volume:Float=1f,voiceName:String?=null):PlatformSpeechResult=mutex.withLock {
        require(text.isNotBlank()){"Nothing to speak"}
        currentCoroutineContext().ensureActive()
        val e=ensureEngine()
        currentCoroutineContext().ensureActive()
        val utterance=requestUtterance ?:"rosalina-${UUID.randomUUID()}"
        val finished=CompletableDeferred<Unit>()
        completion=finished;active.set(utterance)
        val began=SystemClock.elapsedRealtime()
        val playbackBegan=AtomicLong(-1L);val playbackAnchor=AtomicLong(-1L)
        val envelope=PlaybackEnvelope();var lipSource="unavailable"
        val audio=requireNotNull(context.getSystemService(AudioManager::class.java)){"Android audio service unavailable"}
        val mediaVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted=runCatching{audio.isStreamMute(AudioManager.STREAM_MUSIC)}.getOrDefault(false)
        fun owns(id:String?)=id==utterance && active.get()==utterance && !finished.isCompleted
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({loss->if(loss<0 && owns(utterance)){
                observe(SpeechObservation("interrupt",utterance));finished.cancel(CancellationException("Audio focus lost"));stop()
            }},main).build()
        try {
            require(mediaVolume>0 && !muted){"Android media volume is muted. Raise Media volume and try again."}
            check(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
            activeFocus.set(focus)
            observe(SpeechObservation("preparing",utterance))
            withContext(Dispatchers.Main.immediate) {
                val requested=voiceName ?:context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).getString("voice-system-name",defaultVoiceName)
                val selected=e.voices.orEmpty().firstOrNull{it.name==requested && !it.isNetworkConnectionRequired && it.locale.language=="en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}
                check(selected!=null){"The selected offline voice is unavailable. Choose an installed female voice in Settings."}
                check(e.setVoice(selected)==TextToSpeech.SUCCESS){"Android could not select this offline voice"}
                e.setSpeechRate(pace.coerceIn(.75f,1.25f))
                e.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String?){if(owns(id)){
                        val now=SystemClock.elapsedRealtime();playbackBegan.compareAndSet(-1,now);playbackAnchor.compareAndSet(-1,now)
                        observe(SpeechObservation("start",utterance));onStart()
                    }}
                    override fun onBeginSynthesis(id:String?,sampleRateInHz:Int,audioFormat:Int,channelCount:Int){if(owns(id))envelope.format(sampleRateInHz,audioFormat,channelCount)}
                    override fun onAudioAvailable(id:String?,bytes:ByteArray?){if(owns(id) && bytes!=null)envelope.add(bytes)}
                    override fun onRangeStart(id:String?,start:Int,end:Int,frame:Int){if(owns(id))envelope.frameMillis(frame)?.let{playbackAnchor.set(SystemClock.elapsedRealtime()-it)}}
                    override fun onDone(id:String?){if(owns(id))finished.complete(Unit)}
                    @Deprecated("Deprecated in Java")
                    override fun onError(id:String?){if(owns(id))finished.completeExceptionally(IllegalStateException("Android system speech failed"))}
                    override fun onError(id:String?,errorCode:Int){if(owns(id))finished.completeExceptionally(IllegalStateException("Android system speech failed: $errorCode"))}
                    override fun onStop(id:String?,interrupted:Boolean){if(owns(id))finished.cancel(CancellationException("Speech stopped"))}
                })
                val params=Bundle().apply{
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume.coerceIn(.55f,1f))
                }
                check(e.speak(text,TextToSpeech.QUEUE_FLUSH,params,utterance)==TextToSpeech.SUCCESS){"Android system speech rejected the utterance"}
            }
            coroutineScope {
                val meter=launch {
                    while(isActive && !finished.isCompleted) {
                        val anchor=playbackAnchor.get()
                        if(anchor>=0)envelope.atMillis(SystemClock.elapsedRealtime()-anchor)?.let{
                            lipSource="synthesis PCM / playback-clock estimate (not phonemes)"
                            if(active.get()==utterance)observe(SpeechObservation("energy",utterance,it,lipSource,envelope.poseAtMillis(SystemClock.elapsedRealtime()-anchor)))
                        }
                        delay(50)
                    }
                }
                try {withTimeout((10_000L+text.length*140L).coerceAtMost(60_000L)){finished.await()}}
                finally {meter.cancelAndJoin()}
            }
            val now=SystemClock.elapsedRealtime();val first=playbackBegan.get()
            PlatformSpeechResult(e.defaultEngine ?: "default",e.voice?.name ?: "default",now-began,mediaVolume,max,muted,
                if(first<0)-1 else first-began,if(first<0)0 else now-first,e.voice?.isNetworkConnectionRequired==false,lipSource)
        } finally {
            // Do not let an old cancellation stop a successor utterance.
            if(active.compareAndSet(utterance,""))withContext(NonCancellable+Dispatchers.Main.immediate){runCatching{e.stop()}}
            if(completion===finished)completion=null
            activeFocus.compareAndSet(focus,null);runCatching{audio.abandonAudioFocusRequest(focus)}
            observe(SpeechObservation("stop",utterance));onDone()
        }
    }
    suspend fun shutdown()=mutex.withLock {
        withContext(Dispatchers.Main.immediate){engine?.shutdown();engine=null;ready=false}
    }
    fun stop() {
        val e=engine
        activeFocus.getAndSet(null)?.let{runCatching{context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it)}}
        val target=active.get();completion?.cancel(CancellationException("Speech stopped by user"))
        main.post{if(active.get()==target)runCatching{e?.stop()}}
    }
}
