package com.rosalina.unified

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object StudioSpeechRequest {
    fun encode(text:String,config:StudioConfig,performance:PerformanceState):ByteArray {
        require(text.isNotBlank() && text.length<=600){"Studio speech clause is too long"}
        val json=JSONObject().put("input",text).put("model",config.model).put("voice",config.voice)
            .put("response_format","pcm").put("stream_format","audio").put("speed",performance.bounded().pace.toDouble().coerceIn(.8,1.2))
        StudioStyle.instructions(config.style,performance)?.let{json.put("instructions",it)}
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}
internal class StudioVoice(
    context:Context,val settings:StudioVoiceSettings,
    private val transport:StudioTransport=StudioTransport(clock={SystemClock.elapsedRealtime()})
) {
    private val output=StudioPcmOutput(context)
    private val exchange=AtomicReference<StudioExchange?>(null)
    private val serial=Mutex()
    @Volatile private var retryAfter=0L
    @Volatile private var detail="Not connected"
    @Volatile private var firstAudio:Long?=null
    fun readyForReply()=settings.preferred() && SystemClock.elapsedRealtime()>=retryAfter
    fun status()=if(!settings.enabled())"Off" else detail
    fun summary()=settings.summary()+"; status=${status()}; last first-audio=${firstAudio?.let{"$it ms"} ?:"not measured"}; quality acceptance not implied"
    fun stop(){output.stop();exchange.getAndSet(null)?.let{it.cancel();if(SystemClock.elapsedRealtime()>=retryAfter)detail="Studio interrupted"}}
    suspend fun probe():String=serial.withLock {
        val config=settings.snapshot();detail="Checking configured Studio"
        val call=transport.start(config,null,true);exchange.set(call)
        try {
            val bytes=ByteArrayOutputStream()
            while(true){val data=call.next() ?:break;bytes.write(data);data.fill(0)}
            val text=bytes.toByteArray().toString(Charsets.UTF_8)
            val valid=runCatching{if(text.trimStart().startsWith("["))JSONArray(text) else JSONObject(text)}.isSuccess
            if(!valid)throw StudioVoiceException(StudioFailure.FORMAT)
            retryAfter=0
            detail="Studio API reachable · ${SystemClock.elapsedRealtime()-call.startedMs} ms; voice not yet auditioned"
            detail
        }catch(t:Throwable){if(t is CancellationException)throw t;val failure=(t as? StudioVoiceException)?.failure ?:StudioFailure.NETWORK
            detail=failure.label;throw StudioVoiceException(failure)
        }finally{exchange.compareAndSet(call,null);call.cancel()}
    }
    suspend fun speak(text:String,performance:PerformanceState,emit:(String,String,Bundle?)->Unit):Bundle=serial.withLock {coroutineScope {
        val config=settings.snapshot();val started=AtomicBoolean(false);val timedOut=AtomicBoolean(false)
        val call=transport.start(config,StudioSpeechRequest.encode(text,config,performance));exchange.set(call)
        detail="Connecting to Studio";emit("stage","Connecting · Studio Voice",null)
        val watchdog=launch{
            while(isActive){
                if(!started.get() && SystemClock.elapsedRealtime()-call.startedMs>6000){timedOut.set(true);call.cancel();break}
                delay(25)
            }
        }
        try {
            val result=output.play(call,performance.bounded().volume){kind,label,event->
                if(kind=="playback" && label=="start"){started.set(true);detail="Speaking · Studio Voice · private network"}
                emit(kind,label,event)
            }
            firstAudio=result.getLong("firstAudioMs");detail="Studio ready · last first-audio ${firstAudio} ms";retryAfter=0
            result
        }catch(t:Throwable){
            currentCoroutineContext().ensureActive()
            if(t is CancellationException && !timedOut.get())throw t
            val failure=if(timedOut.get())StudioFailure.TIMEOUT else (t as? StudioVoiceException)?.failure ?:StudioFailure.AUDIO
            detail=failure.label;retryAfter=SystemClock.elapsedRealtime()+60000
            throw StudioVoiceException(failure)
        }finally{
            watchdog.cancel();exchange.compareAndSet(call,null);call.cancel()
        }
    }}
}
