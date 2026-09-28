package com.rosalina.unified

import android.media.*
import android.os.Bundle
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

internal object VoicePcm {
    fun toPcm16(samples:FloatArray):ShortArray {
        val out=ShortArray(samples.size)
        for(i in samples.indices) {
            val value=samples[i]
            require(value.isFinite()){"Voice produced non-finite audio"}
            out[i]=(value.coerceIn(-1f,1f)*32767f).roundToInt().coerceIn(-32767,32767).toShort()
        }
        return out
    }
}

class SpeechService:NativeRpcService() {
    private var tts:OfflineTts?=null
    private var ttsRoot=""
    private val voiceThreads by lazy{Runtime.getRuntime().availableProcessors().coerceIn(2,4)}
    @Volatile private var track:AudioTrack?=null
    private var focus:AudioFocusRequest?=null
    private val cancelled=AtomicBoolean(false)
    override fun interrupt(){cancelled.set(true);runCatching{track?.pause();track?.flush()};focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}}
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        cancelled.set(false)
        return when(values.getString("operation")) {
            "transcribe"->transcribe(values,emit)
            "prepare"->prepareVoice(emit)
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
            tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(model=File(root,"model.onnx").path,voices=File(root,"voices.bin").path,tokens=File(root,"tokens.txt").path,dataDir=File(root,"espeak-ng-data").path,lexicon=File(root,"lexicon-us-en.txt").takeIf{it.exists()}?.path ?:"",lang="en-us"),numThreads=voiceThreads,provider="cpu"),maxNumSentences=1));ttsRoot=root.path
        }
        return tts ?:error("Voice unavailable")
    }
    private fun prepareVoice(emit:(String,String,Bundle?)->Unit):Bundle {
        val start=SystemClock.elapsedRealtime()
        val e=ensureVoice(emit)
        return Bundle().apply{
            putBoolean("prepared",true);putInt("sampleRate",e.sampleRate())
            putInt("speakers",e.numSpeakers());putInt("threads",voiceThreads);putLong("elapsedMs",SystemClock.elapsedRealtime()-start)
        }
    }
    private fun routeType(out:AudioTrack)=runCatching{out.routedDevice?.type ?: -1}.getOrDefault(-1)
    private suspend fun speak(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        val engine=ensureVoice(emit)
        val text=values.getString("text").orEmpty().trim();require(text.isNotBlank() && text.length<=1000){"Invalid speech chunk"}
        val rate=engine.sampleRate()
        val sid=values.getInt("speaker",3).coerceIn(0,engine.numSpeakers()-1)
        val expression=VoiceExpression(
            name=values.getString("voiceProfile") ?: "Voice V2",
            pitchSemitones=0f,
            breathiness=values.getFloat("breathiness",0f),
            tone=values.getFloat("tone",0f),
            rasp=values.getFloat("rasp",0f),
            energy=values.getFloat("energy",1f),
            pace=values.getFloat("pace",values.getFloat("speed",1f)),
            intensity=values.getFloat("voiceIntensity",1f)
        ).safe()
        val started=SystemClock.elapsedRealtime()
        emit("voiceDiag","tts-synthesis-start",Bundle().apply{putInt("sampleRate",rate);putInt("threads",voiceThreads);putString("profile",expression.summary())})
        val generated=engine.generate(text,sid,expression.pace)
        val synthesisMs=SystemClock.elapsedRealtime()-started
        require(generated.sampleRate==rate){"Voice sample-rate changed unexpectedly: ${generated.sampleRate} vs $rate"}
        require(generated.samples.isNotEmpty()){"Voice synthesis returned zero samples"}
        emit("voiceDiag","tts-synthesis-complete",Bundle().apply{putInt("samples",generated.samples.size);putInt("sampleRate",generated.sampleRate);putLong("synthesisMs",synthesisMs)})
        if(cancelled.get())return Bundle().apply{
            putLong("synthesisMs",synthesisMs);putLong("firstAudioMs",0);putLong("audioMs",0)
            putString("voice","Kokoro82M/speaker-$sid");putString("voiceProfile",expression.summary())
            putString("playbackPath","PCM16 safe path · interrupted before playback");putBoolean("pitchApplied",false);putBoolean("interrupted",true)
        }
        currentCoroutineContext().ensureActive()
        val shaped=VoiceDspProcessor(rate,expression).process(generated.samples)
        var clipped=0
        for(value in shaped) {
            if(!value.isFinite())error("Voice produced non-finite audio")
            if(abs(value)>=.999f){clipped++;if(clipped>rate/20)error("Voice produced clipped audio; stopped")}else clipped=0
        }
        val pcm=VoicePcm.toPcm16(shaped)
        emit("voiceDiag","pcm16-ready",Bundle().apply{putInt("samples",pcm.size);putInt("sampleRate",rate)})
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am=getSystemService(AudioManager::class.java)
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{loss->if(loss<0)interrupt()}.build()
        focus=request;require(am.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
        var audio:AudioTrack?=null
        try {
            val encoding=AudioFormat.ENCODING_PCM_16BIT
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,encoding)
            require(minimum>0){"Android rejected Rosalina PCM16 output at $rate Hz"}
            val bufferBytes=maxOf(minimum,rate*2/5)
            val out=AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            require(out.state==AudioTrack.STATE_INITIALIZED){"Android audio track failed to initialize"}
            audio=out;track=out
            emit("voiceDiag","audio-track-ready",Bundle().apply{putInt("sampleRate",rate);putInt("route",routeType(out));putInt("bufferBytes",bufferBytes)})
            out.play()
            require(out.playState==AudioTrack.PLAYSTATE_PLAYING){"Android audio track did not enter playing state"}
            emit("voiceDiag","audio-play-called",Bundle().apply{putInt("route",routeType(out))})
            var offset=0;var total=0L;var first=0L;var announced=false
            while(offset<pcm.size && !cancelled.get()) {
                val n=out.write(pcm,offset,minOf(2048,pcm.size-offset),AudioTrack.WRITE_BLOCKING)
                check(n>0){"Audio output stopped accepting samples: $n"}
                offset+=n;total+=n
                if(!announced) {
                    announced=true;first=SystemClock.elapsedRealtime()-started
                    val route=routeType(out)
                    emit("playback","start",Bundle().apply{putInt("route",route);putString("profile",expression.summary());putBoolean("pitchApplied",false)})
                    emit("stage","Rosalina is speaking · "+expression.name,null)
                    emit("voiceDiag","first-audio-written",Bundle().apply{putInt("route",route);putInt("samples",n);putInt("sampleRate",rate)})
                }
            }
            require(total>0 || cancelled.get()){"No audio samples reached Android playback"}
            val deadline=SystemClock.elapsedRealtime()+maxOf(5000L,total*1000/rate+3000L)
            while(!cancelled.get() && out.playbackHeadPosition.toLong()<total){currentCoroutineContext().ensureActive();check(SystemClock.elapsedRealtime()<deadline){"Audio output did not finish"};kotlinx.coroutines.delay(20)}
            val interrupted=cancelled.get()
            return Bundle().apply{
                putLong("synthesisMs",synthesisMs);putLong("firstAudioMs",first)
                putLong("elapsedMs",SystemClock.elapsedRealtime()-started);putLong("audioMs",total*1000L/rate)
                putLong("samples",total);putInt("sampleRate",rate);putInt("route",routeType(out));putInt("threads",voiceThreads)
                putString("voice","Kokoro82M/speaker-$sid");putString("voiceProfile",expression.summary())
                putString("playbackPath","PCM16 buffered media path");putBoolean("pitchApplied",false);putBoolean("interrupted",interrupted)
            }
        }finally{emit("playback","stop",null);runCatching{audio?.pause();audio?.flush();audio?.release()};track=null;am.abandonAudioFocusRequest(request);focus=null}
    }
}
