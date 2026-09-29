package com.rosalina.unified

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class SpeechService:NativeRpcService() {
    private var tts:OfflineTts?=null
    private var ttsRoot=""
    @Volatile private var track:AudioTrack?=null
    @Volatile private var focus:AudioFocusRequest?=null
    private val cancelled=AtomicBoolean(false)
    override fun interrupt(){cancelled.set(true);runCatching{track?.pause();track?.flush()};focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}}
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        cancelled.set(false)
        return when(values.getString("operation")) {
            "transcribe"->transcribe(values,emit)
            "prepare"->prepareVoice(emit)
            "tone"->tone(emit)
            else->speak(values,emit)
        }
    }
    private suspend fun transcribe(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        tts?.release();tts=null;ttsRoot=""
        val root=ModelStore(this).bundleFile(ModelKey.STT,"tiny.en-tokens.txt").parentFile ?:error("Listening model folder is missing")
        val recognizer=OfflineRecognizer(config=OfflineRecognizerConfig(modelConfig=OfflineModelConfig(whisper=OfflineWhisperModelConfig(encoder=File(root,"tiny.en-encoder.int8.onnx").path,decoder=File(root,"tiny.en-decoder.int8.onnx").path),tokens=File(root,"tiny.en-tokens.txt").path,numThreads=2,provider="cpu")))
        val start=SystemClock.elapsedRealtime()
        try {
            emit("stage","Transcribing on device",null)
            val file=File(values.getString("pcm") ?:error("No microphone recording"))
            require(file.canonicalPath.startsWith(filesDir.canonicalPath+File.separator) && file.length() in 2..960_000){"Invalid microphone recording"}
            val shorts=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples=FloatArray(shorts.remaining()){shorts.get()/32768f};currentCoroutineContext().ensureActive()
            val stream=recognizer.createStream()
            try{stream.acceptWaveform(samples,16000);recognizer.decode(stream);val text=recognizer.getResult(stream).text.trim();require(text.isNotBlank()){"No speech recognized. Try speaking closer to the microphone."};return Bundle().apply{putString("transcript",text);putLong("inferenceMs",SystemClock.elapsedRealtime()-start);putLong("audioMs",samples.size*1000L/16000)}}finally{stream.release()}
        }finally{recognizer.release()}
    }
    private fun ensureVoice(emit:(String,String,Bundle?)->Unit):OfflineTts {
        val root=ModelStore(this).bundleFile(ModelKey.TTS,"model.onnx").parentFile ?:error("Voice model folder is missing")
        if(tts==null || root.path!=ttsRoot) {
            emit("stage","Loading Rosalina voice",null);tts?.release()
            tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(model=File(root,"model.onnx").path,voices=File(root,"voices.bin").path,tokens=File(root,"tokens.txt").path,dataDir=File(root,"espeak-ng-data").path,lexicon=File(root,"lexicon-us-en.txt").takeIf{it.exists()}?.path ?:"",lang="en-us"),numThreads=2,provider="cpu"),maxNumSentences=1));ttsRoot=root.path
        }
        return tts ?:error("Voice unavailable")
    }
    private fun prepareVoice(emit:(String,String,Bundle?)->Unit):Bundle {
        val start=SystemClock.elapsedRealtime()
        val e=ensureVoice(emit)
        return Bundle().apply{
            putBoolean("prepared",true);putInt("sampleRate",e.sampleRate())
            putInt("speakers",e.numSpeakers());putLong("elapsedMs",SystemClock.elapsedRealtime()-start)
        }
    }
    private suspend fun tone(emit:(String,String,Bundle?)->Unit):Bundle {
        val rate=24000
        val seconds=.6
        val count=(rate*seconds).toInt()
        val pcm=ShortArray(count){i->
            val envelope=minOf(1.0,i/800.0,(count-i-1)/800.0).coerceAtLeast(0.0)
            (kotlin.math.sin(2.0*Math.PI*440.0*i/rate)*envelope*0.22*Short.MAX_VALUE).toInt().toShort()
        }
        return playPcm(pcm,rate,false,emit,"speaker-tone")
    }

    private suspend fun playPcm(pcm:ShortArray,rate:Int,conversation:Boolean,emit:(String,String,Bundle?)->Unit,label:String):Bundle {
        val am=getSystemService(AudioManager::class.java)
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{loss->if(loss<0)interrupt()}.build()
        focus=request
        var out:AudioTrack?=null
        try {
            require(am.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            require(minimum>0){"Speaker PCM format unsupported"}
            val trackLocal=AudioTrack(
                AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum,rate*2),AudioTrack.MODE_STREAM
            )
            require(trackLocal.state==AudioTrack.STATE_INITIALIZED){"Android media AudioTrack did not initialize"}
            out=trackLocal;track=trackLocal;trackLocal.setVolume(1f);trackLocal.play()
            require(trackLocal.playState==AudioTrack.PLAYSTATE_PLAYING){"Android media AudioTrack did not enter PLAYING state"}
            val started=SystemClock.elapsedRealtime();var offset=0;var first=0L
            while(offset<pcm.size && !cancelled.get()){
                currentCoroutineContext().ensureActive()
                val n=trackLocal.write(pcm,offset,minOf(2048,pcm.size-offset),AudioTrack.WRITE_BLOCKING)
                check(n>0){"Android media AudioTrack rejected PCM: $n"}
                if(first==0L)first=SystemClock.elapsedRealtime()-started
                offset+=n
            }
            val deadline=SystemClock.elapsedRealtime()+maxOf(5000L,offset*1000L/rate+2500L)
            while(!cancelled.get() && (trackLocal.playbackHeadPosition.toLong() and 0xffffffffL)<offset){
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime()<deadline){"Android media playback head did not finish"}
                kotlinx.coroutines.delay(20)
            }
            val actual=trackLocal.routedDevice?.type ?: -1
            emit("playback","start",Bundle().apply{putInt("route",actual);putString("routeLabel",AudioRoutePolicy.label(actual));putString("profile",label);putBoolean("pitchApplied",false)})
            return Bundle().apply{
                putLong("firstAudioMs",first);putLong("audioMs",offset*1000L/rate);putLong("elapsedMs",SystemClock.elapsedRealtime()-started)
                putBoolean("interrupted",cancelled.get());putInt("route",actual);putString("routeLabel",AudioRoutePolicy.label(actual))
                putInt("preferredRoute",-1);putString("preferredRouteLabel","Android default media route");putBoolean("preferredApplied",false)
                putInt("audioMode",am.mode);putString("encoding","PCM_16BIT");putString("usage","MEDIA")
                putInt("streamVolume",am.getStreamVolume(AudioManager.STREAM_MUSIC));putInt("streamMax",am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));putBoolean("streamMuted",runCatching{am.isStreamMute(AudioManager.STREAM_MUSIC)}.getOrDefault(false))
            }
        } finally {
            emit("playback","stop",null)
            runCatching{out?.pause();out?.flush();out?.release()};track=null
            runCatching{am.abandonAudioFocusRequest(request)};focus=null
        }
    }

    private suspend fun speak(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        val engine=ensureVoice(emit)
        val text=values.getString("text").orEmpty().trim();require(text.isNotBlank() && text.length<=1000){"Invalid speech chunk"}
        val rate=engine.sampleRate()
        val sid=values.getInt("speaker",3).coerceIn(0,engine.numSpeakers()-1)
        val expression=VoiceExpression(
            name=values.getString("voiceProfile") ?: "Voice V2",
            pitchSemitones=values.getFloat("pitchSemitones",0f),
            breathiness=values.getFloat("breathiness",0f),
            tone=values.getFloat("tone",0f),
            rasp=values.getFloat("rasp",0f),
            energy=values.getFloat("energy",1f),
            pace=values.getFloat("pace",values.getFloat("speed",1f)),
            intensity=values.getFloat("voiceIntensity",1f)
        ).safe()
        emit("stage","Generating speech · "+expression.name,null)
        val pcm=java.io.ByteArrayOutputStream()
        val dsp=VoiceDspProcessor(rate,expression.copy(pitchSemitones=0f))
        var clipped=0
        engine.generateWithCallback(text,sid,expression.pace){samples->
            if(cancelled.get())return@generateWithCallback 0
            val shaped=dsp.process(samples)
            var energySum=0.0
            val bytes=ByteBuffer.allocate(shaped.size*2).order(ByteOrder.LITTLE_ENDIAN)
            for(value in shaped){
                if(!value.isFinite())error("Voice produced non-finite audio")
                energySum+=value.toDouble()*value.toDouble()
                if(abs(value)>=.999f){clipped++;if(clipped>rate/20)error("Voice produced clipped audio; stopped")}else clipped=0
                bytes.putShort((value.coerceIn(-1f,1f)*32767f).toInt().toShort())
            }
            pcm.write(bytes.array())
            val rms=sqrt(energySum/maxOf(1,shaped.size)).toFloat()
            emit("avatar","energy",Bundle().apply{putFloat("energy",(rms*4.5f).coerceIn(0f,1f))})
            if(cancelled.get())0 else 1
        }
        currentCoroutineContext().ensureActive()
        val data=pcm.toByteArray();require(data.isNotEmpty()){"Kokoro produced no audio samples"}
        val shorts=ShortArray(data.size/2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        emit("stage","Speaking · "+expression.name,null)
        val result=playPcm(shorts,rate,values.getBoolean("conversation"),emit,expression.summary())
        emit("avatar","energy",Bundle().apply{putFloat("energy",0f)})
        return Bundle(result).apply{
            putString("voice","Kokoro82M/speaker-$sid");putString("voiceProfile",expression.summary());putBoolean("pitchApplied",false)
        }
    }
}
