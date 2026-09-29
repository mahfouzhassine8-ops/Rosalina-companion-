package com.rosalina.unified

import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** No Pocket path. A Chatterbox trial runs only in :expressive; the protected runtime is unchanged. */
class ExpressiveSpeechService:NativeRpcService() {
    private var engine:ChatterboxEngine?=null
    private var rootPath=""
    private val cancelled=AtomicBoolean(false)
    private val output by lazy{PcmSpeechOutput(this,cancelled)}
    override fun beginRequest(){cancelled.set(false)}
    override fun interrupt(){cancelled.set(true);engine?.cancel();output.interrupt()}
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle=coroutineScope {
        if(cancelled.get())throw CancellationException("Voice request was already cancelled")
        val start=SystemClock.elapsedRealtime();val thermal=getSystemService(PowerManager::class.java)
        val before=thermal.currentThermalStatus;val peak=AtomicLong(Debug.getPss().toLong())
        val sampler=launch(Dispatchers.Default){while(isActive){val p=Debug.getPss().toLong();peak.updateAndGet{maxOf(it,p)};delay(250)}}
        try {
            val root=VoiceV3Models(this@ExpressiveSpeechService).requireDirectory()
            if(engine==null || rootPath!=root.path){
                emit("stage","Loading Chatterbox candidate",null);engine?.close();engine=null
                engine=ChatterboxEngine(root,cancelled);rootPath=root.path
            }
            val initialized=SystemClock.elapsedRealtime()-start
            if(values.getString("operation")=="prepare")return@coroutineScope Bundle().apply{putBoolean("prepared",true);putLong("setupMs",initialized)}
            val text=values.getString("text").orEmpty().trim();require(text.length in 1..500){"Voice clause must be 1–500 characters"}
            emit("stage","Synthesizing Chatterbox candidate",null)
            val samples=engine!!.synthesize(text){step->if(step%16==0){peak.updateAndGet{maxOf(it,Debug.getPss().toLong())};emit("progress","Generating speech tokens",Bundle().apply{putInt("speechTokens",step)})}}
            currentCoroutineContext().ensureActive();if(cancelled.get())throw CancellationException("Voice request cancelled")
            val synthesized=SystemClock.elapsedRealtime()-start
            val gain=values.getFloat("gain",.95f).coerceIn(.55f,1f)
            val pcm=ShortArray(samples.size){(samples[it].coerceIn(-1f,1f)*gain*32767).toInt().toShort()}
            output.play(pcm,24000,start,emit,values.getFloat("pace",1f)).apply{
                putString("engine","Chatterbox Turbo Q4 / ORT ${engine!!.runtimeVersion}");putBoolean("offline",true)
                putLong("setupMs",initialized);putLong("synthesisMs",synthesized-initialized);putLong("peakPssKb",peak.get());putLong("pssKb",Debug.getPss().toLong())
                putInt("thermalBefore",before);putInt("thermalAfter",thermal.currentThermalStatus)
            }
        }finally {sampler.cancelAndJoin()}
    }
}
