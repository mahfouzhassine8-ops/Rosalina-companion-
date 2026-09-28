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

/** ONNX speech lives in :speech, never in llama.cpp's process. No system/cloud STT or TTS. */
class SpeechService:NativeRpcService() {
    private var tts:OfflineTts?=null
    private var ttsRoot=""
    @Volatile private var track:AudioTrack?=null
    private var focus:AudioFocusRequest?=null
    private val cancelled=AtomicBoolean(false)
    override fun interrupt() {
        cancelled.set(true)
        runCatching{track?.pause();track?.flush()}
        focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}
    }
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        cancelled.set(false)
        return if(values.getString("operation")=="transcribe") transcribe(values,emit) else speak(values,emit)
    }
    private suspend fun transcribe(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        tts?.release();tts=null;ttsRoot=""
        val models=ModelStore(this)
        val root=models.bundleFile(ModelKey.STT,"tiny.en-tokens.txt").parentFile ?: error("Speech model folder is missing")
        val recognizer=OfflineRecognizer(config=OfflineRecognizerConfig(modelConfig=OfflineModelConfig(
            whisper=OfflineWhisperModelConfig(encoder=File(root,"tiny.en-encoder.int8.onnx").path,decoder=File(root,"tiny.en-decoder.int8.onnx").path),
            tokens=File(root,"tiny.en-tokens.txt").path,numThreads=2,provider="cpu")))
        val started=SystemClock.elapsedRealtime()
        try {
            emit("stage","Transcribing on device",null)
            val file=File(values.getString("pcm") ?: error("No microphone recording"))
            require(file.canonicalPath.startsWith(filesDir.canonicalPath+File.separator) && file.length() in 2..960_000){"Invalid microphone recording"}
            val bytes=file.readBytes();val shorts=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples=FloatArray(shorts.remaining()){shorts.get()/32768f}
            currentCoroutineContext().ensureActive()
            val stream=recognizer.createStream()
            try {
                stream.acceptWaveform(samples,16000);recognizer.decode(stream)
                val text=recognizer.getResult(stream).text.trim()
                require(text.isNotBlank()){"No speech was recognized. Try speaking closer to the microphone."}
                return Bundle().apply{putString("transcript",text);putLong("inferenceMs",SystemClock.elapsedRealtime()-started);putLong("audioMs",samples.size*1000L/16000)}
            } finally {stream.release()}
        } finally {recognizer.release()}
    }
    private suspend fun speak(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        val models=ModelStore(this);val root=models.bundleFile(ModelKey.TTS,"model.onnx").parentFile ?: error("Voice model folder is missing")
        if(tts==null || root.path!=ttsRoot) {
            emit("stage","Loading Rosalina voice",null)
            tts?.release()
            tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(
                model=File(root,"model.onnx").path,voices=File(root,"voices.bin").path,tokens=File(root,"tokens.txt").path,
                dataDir=File(root,"espeak-ng-data").path,lexicon=File(root,"lexicon-us-en.txt").takeIf{it.exists()}?.path ?: "",lang="en-us"),
                numThreads=2,provider="cpu"),maxNumSentences=1))
            ttsRoot=root.path
        }
        val engine=tts ?: error("Voice is unavailable")
        val text=values.getString("text").orEmpty().trim();require(text.isNotBlank() && text.length<=1000){"Invalid speech chunk"}
        val rate=engine.sampleRate();val sid=values.getInt("speaker",3).coerceIn(0,engine.numSpeakers()-1)
        val speed=values.getFloat("speed",1f).coerceIn(0.7f,1.4f)
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am=getSystemService(AudioManager::class.java)
        val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{loss->if(loss<0)interrupt()}.build()
        focus=focusRequest
        require(am.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
        val audio=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(rate,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT))).setTransferMode(AudioTrack.MODE_STREAM).build()
        track=audio;audio.play();val started=SystemClock.elapsedRealtime();var first=0L;var totalSamples=0L
        try {
            emit("stage","Rosalina is speaking",null)
            engine.generateWithCallback(text,sid,speed){samples->
                if(cancelled.get())return@generateWithCallback 0
                // Refuse non-finite or rail-pinned model output instead of playing a damaging DC burst.
                var pinned=0
                for(value in samples){if(!value.isFinite())error("Voice engine produced non-finite audio");if(abs(value)>=0.999f){pinned++;if(pinned>rate/20)error("Voice engine produced clipped audio; playback stopped")}else pinned=0}
                if(first==0L)first=SystemClock.elapsedRealtime()-started
                var offset=0
                while(offset<samples.size && !cancelled.get()) {
                    val n=audio.write(samples,offset,minOf(2048,samples.size-offset),AudioTrack.WRITE_BLOCKING)
                    if(n<0)error("Audio output failed with code $n")
                    if(n==0)error("Audio output stopped accepting samples")
                    offset+=n;totalSamples+=n
                }
                if(cancelled.get())0 else 1
            }
            val drainDeadline=SystemClock.elapsedRealtime()+maxOf(5000L,totalSamples*1000/rate+3000L)
            while(!cancelled.get() && audio.playbackHeadPosition.toLong()<totalSamples) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime()<drainDeadline){"Audio output did not finish in its expected playback window"}
                kotlinx.coroutines.delay(20)
            }
            return Bundle().apply{putLong("firstAudioMs",first);putLong("elapsedMs",SystemClock.elapsedRealtime()-started);putLong("audioMs",totalSamples*1000/rate);putString("voice","Kokoro82M/speaker-$sid")}
        } finally {
            runCatching{audio.pause();audio.flush();audio.release()};track=null;am.abandonAudioFocusRequest(focusRequest);focus=null
        }
    }
}
