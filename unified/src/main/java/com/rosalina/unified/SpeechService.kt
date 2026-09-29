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
    private fun preferredOutput(am:AudioManager,conversation:Boolean):AudioDeviceInfo? {
        if(conversation && Build.VERSION.SDK_INT>=31)runCatching{am.communicationDevice}.getOrNull()?.let{return it}
        val bluetoothAllowed=Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        val devices=if(conversation && Build.VERSION.SDK_INT>=31)runCatching{am.availableCommunicationDevices}.getOrDefault(emptyList()) else runCatching{am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()}.getOrDefault(emptyList())
        return devices.filter{AudioRoutePolicy.usable(it.type,bluetoothAllowed)}.minByOrNull{AudioRoutePolicy.rank(it.type,bluetoothAllowed)}
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
        val conversation=values.getBoolean("conversation")
        val usage=if(conversation)AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val attributes=AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am=getSystemService(AudioManager::class.java)
        val previousMode=am.mode
        var changedMode=false
        var speechSetCommunicationDevice=false
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{loss->if(loss<0)interrupt()}.build()
        focus=request
        var audio:AudioTrack?=null
        try {
            require(am.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"}
            if(conversation && am.mode!=AudioManager.MODE_IN_COMMUNICATION){am.mode=AudioManager.MODE_IN_COMMUNICATION;changedMode=true}
            val preferred=preferredOutput(am,conversation)
            if(conversation && Build.VERSION.SDK_INT>=31 && am.communicationDevice==null && preferred!=null)speechSetCommunicationDevice=runCatching{am.setCommunicationDevice(preferred)}.getOrDefault(false)
            val encoding=AudioFormat.ENCODING_PCM_16BIT
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,encoding)
            require(minimum>0){"Speaker PCM format unsupported"}
            val out=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(maxOf(rate*2,minimum)).setTransferMode(AudioTrack.MODE_STREAM).build()
            audio=out;track=out
            val preferredApplied=preferred?.let{runCatching{out.setPreferredDevice(it)}.getOrDefault(false)} ?:false
            out.setVolume(1f)
            val dsp=VoiceDspProcessor(rate,expression)
            val pitchFactor=VoiceDspProcessor.pitchFactor(expression.pitchSemitones)
            val pitchApplied=runCatching {
                out.playbackParams=PlaybackParams().allowDefaults().setPitch(pitchFactor).setSpeed(1f)
                true
            }.getOrDefault(false)
            out.play()
            val started=SystemClock.elapsedRealtime();var first=0L;var total=0L;var announced=false;var clipped=0;var lastAvatarEmit=0L
            emit("stage","Preparing speech · "+expression.name,null)
            engine.generateWithCallback(text,sid,expression.pace){samples->
                if(cancelled.get())return@generateWithCallback 0
                val shaped=dsp.process(samples)
                var energySum=0.0
                for(value in shaped){
                    if(!value.isFinite())error("Voice produced non-finite audio")
                    energySum+=value.toDouble()*value.toDouble()
                    if(abs(value)>=.999f){clipped++;if(clipped>rate/20)error("Voice produced clipped audio; stopped")}else clipped=0
                }
                val pcm=ShortArray(shaped.size){i->(shaped[i].coerceIn(-1f,1f)*32767f).toInt().toShort()}
                var offset=0
                while(offset<pcm.size && !cancelled.get()) {
                    val n=out.write(pcm,offset,minOf(2048,pcm.size-offset),AudioTrack.WRITE_BLOCKING)
                    if(n<=0 && cancelled.get())break
                    check(n>0){"Audio output stopped accepting samples: $n"}
                    if(first==0L)first=SystemClock.elapsedRealtime()-started
                    offset+=n;total+=n
                    if(!announced){
                        announced=true
                        val actual=out.routedDevice?.type ?: -1
                        emit("playback","start",Bundle().apply{
                            putInt("route",actual);putString("routeLabel",AudioRoutePolicy.label(actual))
                            putInt("preferredRoute",preferred?.type ?: -1);putString("preferredRouteLabel",AudioRoutePolicy.label(preferred?.type ?: -1))
                            putBoolean("preferredApplied",preferredApplied);putString("profile",expression.summary());putBoolean("pitchApplied",pitchApplied)
                        })
                    }
                }
                val avatarNow=SystemClock.elapsedRealtime()
                if(offset>0 && !cancelled.get() && avatarNow-lastAvatarEmit>=70L){
                    val rms=sqrt(energySum/shaped.size).toFloat()
                    emit("avatar","energy",Bundle().apply{putFloat("energy",(rms*4.5f).coerceIn(0f,1f))})
                    val actual=out.routedDevice?.type ?: -1
                    emit("playback","start",Bundle().apply{putInt("route",actual);putString("routeLabel",AudioRoutePolicy.label(actual));putInt("preferredRoute",preferred?.type ?: -1);putString("preferredRouteLabel",AudioRoutePolicy.label(preferred?.type ?: -1));putBoolean("preferredApplied",preferredApplied)})
                    lastAvatarEmit=avatarNow
                }
                if(cancelled.get())0 else 1
            }
            val deadline=SystemClock.elapsedRealtime()+maxOf(5000L,total*1000/rate+3000L)
            while(!cancelled.get() && (out.playbackHeadPosition.toLong() and 0xffffffffL)<total){currentCoroutineContext().ensureActive();check(SystemClock.elapsedRealtime()<deadline){"Audio output did not finish"};kotlinx.coroutines.delay(20)}
            val interrupted=cancelled.get()
            return Bundle().apply{
                putLong("firstAudioMs",first)
                putLong("elapsedMs",SystemClock.elapsedRealtime()-started)
                putLong("audioMs",total*1000L/rate)
                putString("voice","Kokoro82M/speaker-$sid")
                putString("voiceProfile",expression.summary())
                putBoolean("pitchApplied",pitchApplied)
                putBoolean("interrupted",interrupted)
                val actual=out.routedDevice?.type ?: -1
                putInt("route",actual);putString("routeLabel",AudioRoutePolicy.label(actual))
                putInt("preferredRoute",preferred?.type ?: -1);putString("preferredRouteLabel",AudioRoutePolicy.label(preferred?.type ?: -1));putBoolean("preferredApplied",preferredApplied)
                putInt("audioMode",am.mode);putString("encoding","PCM_16BIT");putString("usage",if(conversation)"VOICE_COMMUNICATION" else "MEDIA")
            }
        }finally{emit("avatar","energy",Bundle().apply{putFloat("energy",0f)});emit("playback","stop",null);runCatching{audio?.pause();audio?.flush();audio?.release()};track=null;if(speechSetCommunicationDevice && Build.VERSION.SDK_INT>=31)runCatching{am.clearCommunicationDevice()};if(changedMode)runCatching{am.mode=previousMode};runCatching{am.abandonAudioFocusRequest(request)};focus=null}
    }
}
