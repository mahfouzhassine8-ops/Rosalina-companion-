package com.rosalina.unified

import android.media.*
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Pocket TTS is a trial, isolated from the phone-proven system voice and from Qwen/Whisper. */
class ExpressiveSpeechService:NativeRpcService() {
    private var engine:OfflineTts?=null
    private var rootPath=""
    private val cancelled=AtomicBoolean(false)
    @Volatile private var player:AudioTrack?=null
    override fun interrupt(){cancelled.set(true);runCatching{player?.pause();player?.flush()}}

    private fun load(emit:(String,String,Bundle?)->Unit):OfflineTts {
        val root=VoiceV3Models(this).requireDirectory()
        if(engine==null || rootPath!=root.path) {
            emit("stage","Loading local expressive voice candidate",null)
            engine?.release()
            fun p(name:String)=File(root,name).also{require(it.isFile){"Candidate pack is missing $name"}}.path
            engine=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(
                pocket=OfflineTtsPocketModelConfig(lmFlow=p("lm_flow.int8.onnx"),lmMain=p("lm_main.int8.onnx"),
                    encoder=p("encoder.onnx"),decoder=p("decoder.int8.onnx"),textConditioner=p("text_conditioner.onnx"),
                    vocabJson=p("vocab.json"),tokenScoresJson=p("token_scores.json"),voiceEmbeddingCacheCapacity=1),
                numThreads=2,provider="cpu",debug=false),maxNumSentences=1))
            rootPath=root.path
        }
        return engine ?:error("Candidate voice did not load")
    }
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        cancelled.set(false)
        val started=SystemClock.elapsedRealtime();val e=load(emit)
        if(values.getString("operation")=="prepare")return Bundle().apply{putBoolean("prepared",true);putLong("setupMs",SystemClock.elapsedRealtime()-started)}
        val text=values.getString("text").orEmpty().trim()
        require(text.isNotBlank() && text.length<=500){"Use a bounded speech clause for Voice V3"}
        val (reference,sampleRate)=readReference(File(rootPath,"reference.wav"))
        emit("stage","Synthesizing local expressive voice candidate",null)
        val config=GenerationConfig(referenceAudio=reference,referenceSampleRate=sampleRate,numSteps=5,
            extra=mapOf("max_reference_audio_len" to "10.0","seed" to "42"))
        // JNI requires a typed FloatArray -> boxed Integer bridge, not an indy lambda.
        val generated=e.generateWithConfigAndCallback(text,config,NativeSpeechCallback(cancelled))
        currentCoroutineContext().ensureActive();check(!cancelled.get()){"Candidate speech cancelled"}
        val synthesisMs=SystemClock.elapsedRealtime()-started
        val samples=generated.samples;val rate=generated.sampleRate
        require(rate in 8000..48000 && samples.isNotEmpty() && samples.size<=rate*60){"Candidate produced invalid or oversized audio"}
        val gain=values.getFloat("gain",.9f).coerceIn(.55f,1f)
        val pcm=ShortArray(samples.size){i->val x=samples[i];require(x.isFinite()){"Candidate produced non-finite audio"};(x.coerceIn(-1f,1f)*gain*32767).toInt().toShort()}
        return play(pcm,rate,started,emit).apply{
            putLong("synthesisMs",synthesisMs);putString("engine","Pocket TTS INT8 / sherpa-onnx 1.13.8")
            putString("voice","Alba MacKenna · licensed casual reference")
            putLong("pssKb",Debug.getPss().toLong());putBoolean("offline",true)
        }
    }
    private suspend fun play(pcm:ShortArray,rate:Int,requestStarted:Long,emit:(String,String,Bundle?)->Unit):Bundle=coroutineScope {
        val am=getSystemService(AudioManager::class.java)
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener{if(it<0)interrupt()}.build()
        var out:AudioTrack?=null;var meter:Job?=null
        try {
            require(am.getStreamVolume(AudioManager.STREAM_MUSIC)>0 && !am.isStreamMute(AudioManager.STREAM_MUSIC)){"Android Media volume is muted"}
            require(am.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            require(minimum>0){"Unsupported audio format"}
            val track=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum,rate/2)).setTransferMode(AudioTrack.MODE_STREAM).build()
            out=track;player=track;require(track.state==AudioTrack.STATE_INITIALIZED){"Candidate audio output did not initialize"}
            track.play();val playbackStart=SystemClock.elapsedRealtime();var started=false;var firstAudioMs=-1L
            meter=launch {
                while(isActive && !cancelled.get()) {
                    val head=(track.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(pcm.size.toLong()).toInt()
                    if(head>0 && !started){started=true;firstAudioMs=SystemClock.elapsedRealtime()-requestStarted
                        emit("playback","start",Bundle().apply{putInt("route",track.routedDevice?.type ?: -1)})}
                    if(started){val end=minOf(pcm.size,head+rate/50);var sum=0.0
                        for(i in head until end){val x=pcm[i]/32768.0;sum+=x*x}
                        val rms=if(end>head)sqrt(sum/(end-head)).toFloat() else 0f
                        emit("energy","PCM playback head",Bundle().apply{putFloat("energy",(rms*4.5f).coerceIn(0f,1f))})}
                    delay(50)
                }
            }
            var offset=0;var lastWrite=SystemClock.elapsedRealtime()
            while(offset<pcm.size && !cancelled.get()) {
                currentCoroutineContext().ensureActive()
                val n=track.write(pcm,offset,minOf(2048,pcm.size-offset),AudioTrack.WRITE_NON_BLOCKING)
                check(n>=0){"Candidate audio write failed: $n"}
                if(n==0){check(SystemClock.elapsedRealtime()-lastWrite<5000){"Candidate output stalled"};delay(10)}else{offset+=n;lastWrite=SystemClock.elapsedRealtime()}
            }
            val deadline=SystemClock.elapsedRealtime()+pcm.size*1000L/rate+3000
            while(!cancelled.get() && (track.playbackHeadPosition.toLong() and 0xffffffffL)<offset){
                currentCoroutineContext().ensureActive();check(SystemClock.elapsedRealtime()<deadline){"Candidate playback did not drain"};delay(20)
            }
            check(!cancelled.get()){"Candidate playback interrupted"}
            Bundle().apply{putLong("firstAudioMs",firstAudioMs);putLong("audioMs",pcm.size*1000L/rate)
                putLong("playbackMs",SystemClock.elapsedRealtime()-playbackStart);putLong("playedFrames",track.playbackHeadPosition.toLong() and 0xffffffffL)
                putInt("underruns",track.underrunCount);putInt("route",track.routedDevice?.type ?: -1)}
        } finally {
            withContext(NonCancellable){meter?.cancelAndJoin()}
            emit("playback","stop",null);player=null
            runCatching{out?.pause();out?.flush();out?.release()};runCatching{am.abandonAudioFocusRequest(focus)}
        }
    }
    private fun readReference(file:File):Pair<FloatArray,Int> {
        val bytes=file.readBytes();require(bytes.size in 44..2_000_000){"Invalid candidate reference recording"}
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x46464952){"Reference must be RIFF WAV"};b.int;require(b.int==0x45564157){"Reference must be WAV"}
        var rate=0;var channels=0;var encoding=0;var bits=0
        while(b.remaining()>=8){val kind=b.int;val size=b.int;require(size>=0 && size<=b.remaining()){"Truncated reference WAV"};val end=b.position()+size
            if(kind==0x20746d66){require(size>=16);encoding=b.short.toInt();channels=b.short.toInt();rate=b.int;b.int;b.short;bits=b.short.toInt()}
            if(kind==0x61746164){require(encoding==1 && channels==1 && bits==16 && rate in 8000..48000){"Reference must be mono PCM16"}
                val n=size/2;require(n<=rate*15){"Reference exceeds 15 seconds"};return FloatArray(n){b.short/32768f} to rate}
            b.position(end);if(size%2==1 && b.hasRemaining())b.get()
        };error("Reference WAV has no PCM data")
    }
}
