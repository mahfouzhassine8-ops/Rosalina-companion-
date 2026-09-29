package com.rosalina.unified

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

internal object SpeechCompatibility {
    fun preferPlatform(manufacturer:String,sdk:Int,nativeCrashed:Boolean=false):Boolean =
        nativeCrashed || (manufacturer.equals("samsung",ignoreCase=true) && sdk>=36)
}

internal data class PlatformSpeechResult(
    val engine:String,
    val voice:String,
    val elapsedMs:Long,
    val mediaVolume:Int,
    val mediaMax:Int,
    val mediaMuted:Boolean
)

internal class PlatformSpeech(private val context:Context) {
    private val mutex=Mutex()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var engine:TextToSpeech?=null
    @Volatile private var ready=false

    private suspend fun ensureEngine():TextToSpeech {
        engine?.takeIf{ready}?.let{return it}
        val status=CompletableDeferred<Int>()
        val created=withContext(Dispatchers.Main.immediate) {
            TextToSpeech(context.applicationContext){code->if(!status.isCompleted)status.complete(code)}
        }
        val code=withTimeout(8000){status.await()}
        if(code!=TextToSpeech.SUCCESS){created.shutdown();error("Android system speech engine failed to initialize: $code")}
        withContext(Dispatchers.Main.immediate) {
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if(created.isLanguageAvailable(Locale.US)>=TextToSpeech.LANG_AVAILABLE)created.language=Locale.US
        }
        engine=created;ready=true
        return created
    }

    suspend fun prepare():String=mutex.withLock {
        val e=ensureEngine()
        "Android system TTS · engine=${e.defaultEngine ?: "default"} · voice=${e.voice?.name ?: "default"}"
    }

    suspend fun speak(text:String,pace:Float,onStart:()->Unit={},onDone:()->Unit={}):PlatformSpeechResult=mutex.withLock {
        require(text.isNotBlank()){"Nothing to speak"}
        val e=ensureEngine()
        val utterance="rosalina-${UUID.randomUUID()}"
        val finished=CompletableDeferred<Unit>()
        val began=SystemClock.elapsedRealtime()
        val audio=context.getSystemService(AudioManager::class.java)
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted=runCatching{audio.isStreamMute(AudioManager.STREAM_MUSIC)}.getOrDefault(false)
        require(volume>0 && !muted){"Android media volume is muted. Raise Media volume and try again."}
        withContext(Dispatchers.Main.immediate) {
            e.setSpeechRate(pace.coerceIn(.75f,1.25f))
            e.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){if(id==utterance)onStart()}
                override fun onDone(id:String?){if(id==utterance && !finished.isCompleted){onDone();finished.complete(Unit)}}
                @Deprecated("Deprecated in Java")
                override fun onError(id:String?){if(id==utterance && !finished.isCompleted)finished.completeExceptionally(IllegalStateException("Android system speech failed"))}
                override fun onError(id:String?,errorCode:Int){if(id==utterance && !finished.isCompleted)finished.completeExceptionally(IllegalStateException("Android system speech failed: $errorCode"))}
                override fun onStop(id:String?,interrupted:Boolean){if(id==utterance && !finished.isCompleted){onDone();finished.completeExceptionally(kotlinx.coroutines.CancellationException("Speech stopped"))}}
            })
            val params=Bundle().apply{
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f)
            }
            check(e.speak(text,TextToSpeech.QUEUE_FLUSH,params,utterance)==TextToSpeech.SUCCESS){"Android system speech rejected the utterance"}
        }
        try {
            val timeout=(10_000L+text.length*140L).coerceAtMost(60_000L)
            withTimeout(timeout){finished.await()}
        } finally {
            onDone()
        }
        PlatformSpeechResult(
            engine=e.defaultEngine ?: "default",
            voice=e.voice?.name ?: "default",
            elapsedMs=SystemClock.elapsedRealtime()-began,
            mediaVolume=volume,
            mediaMax=max,
            mediaMuted=muted
        )
    }

    fun stop(){val e=engine ?:return;main.post{runCatching{e.stop()}}}
}
