package com.rosalina.unified

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.sqrt

internal data class VoiceRecording(val file:File,val duringSpeakerOutput:Boolean)
internal class VoiceCapture(private val context:Context,private val handsFree:Boolean):AutoCloseable {
    private val audio=context.getSystemService(AudioManager::class.java)
    private var record:AudioRecord?=null
    private var echo:AcousticEchoCanceler?=null
    private var noise:NoiseSuppressor?=null
    private var previousMode=AudioManager.MODE_NORMAL
    private var changedMode=false
    @Volatile private var closed=false
    @Volatile var outputActive=false
    @Volatile var outputRoute=-1
    // Connected devices do not establish the actual output route. AudioTrack reports this.
    fun headphones()=outputRoute in intArrayOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_BLE_HEADSET)
    fun canInterrupt()=handsFree && (headphones() || (echo?.enabled==true && changedMode))
    fun describe()="Hands-free requested=$handsFree; output route=$outputRoute; AEC available=${AcousticEchoCanceler.isAvailable()}; AEC enabled=${echo?.enabled==true}; acoustic interruption=${canInterrupt()}"
    fun start() {
        check(record==null && !closed);previousMode=audio.mode
        check(previousMode!=AudioManager.MODE_IN_CALL){"Voice is unavailable during a telephone call"}
        try {
            val communication=handsFree && AcousticEchoCanceler.isAvailable() && previousMode==AudioManager.MODE_NORMAL
            if(communication){audio.mode=AudioManager.MODE_IN_COMMUNICATION;changedMode=true}
            val source=if(communication)MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);require(minimum>0){"Microphone format unsupported"}
            val r=AudioRecord(source,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,12800));record=r
            require(r.state==AudioRecord.STATE_INITIALIZED){"Microphone initialization failed"}
            if(communication)echo=runCatching{AcousticEchoCanceler.create(r.audioSessionId)?.apply{enabled=true}}.getOrNull()
            if(NoiseSuppressor.isAvailable())noise=runCatching{NoiseSuppressor.create(r.audioSessionId)?.apply{enabled=true}}.getOrNull()
            r.startRecording();require(r.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Microphone did not start"}
        }catch(t:Throwable){close();throw t}
    }
    suspend fun capture(manual:()->Boolean,finish:()->Boolean,idle:()->Boolean,onStart:()->Unit):VoiceRecording?=withContext(Dispatchers.IO) {
        val r=record ?:error("Microphone not open");val folder=File(context.filesDir,"voice-input").apply{mkdirs()};val file=File(folder,"${UUID.randomUUID()}.pcm")
        val gate=VoiceGate();val preRoll=ArrayDeque<ByteArray>();val samples=ShortArray(640)
        var began=false;var outputAtStart=false;var count=0;var idleSince=SystemClock.elapsedRealtime();var keep=false
        try {
            FileOutputStream(file).use{out->
                while(count<480000) {
                    currentCoroutineContext().ensureActive();check(!closed){"Voice input closed"}
                    val n=r.read(samples,0,samples.size,AudioRecord.READ_NON_BLOCKING);require(n>=0){"Microphone read failed: $n"}
                    if(n==0){delay(10);continue}
                    val data=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN).apply{asShortBuffer().put(samples,0,n)}.array()
                    val rms=sqrt((0 until n).sumOf{samples[it].toDouble()*samples[it]}/n)
                    val forced=manual();val allowed=!outputActive || canInterrupt()
                    if(!allowed && !began)preRoll.clear()
                    if(allowed || began || forced){preRoll.addLast(data);while(preRoll.size>10)preRoll.removeFirst()}
                    val onset=gate.accept(rms,maxOf(1,n*1000/16000),allowed)
                    if(!began && (onset || forced)){began=true;outputAtStart=outputActive && !headphones();onStart();for(bytes in preRoll){out.write(bytes);count+=bytes.size/2};preRoll.clear()}
                    else if(began){out.write(data);count+=n;preRoll.clear()}
                    if(began && (gate.finished() || finish()))break
                    if(!idle() || began)idleSince=SystemClock.elapsedRealtime()
                    if(!began && SystemClock.elapsedRealtime()-idleSince>=45000)break
                };out.fd.sync()
            }
            if(count<1600)return@withContext null
            keep=true;VoiceRecording(file,outputAtStart)
        }finally{if(!keep)file.delete()}
    }
    override fun close() {
        if(closed)return
        closed=true;runCatching{record?.stop()};runCatching{record?.release()};record=null
        runCatching{echo?.release()};runCatching{noise?.release()};echo=null;noise=null
        if(changedMode && audio.mode==AudioManager.MODE_IN_COMMUNICATION)runCatching{audio.mode=previousMode}
    }
}
