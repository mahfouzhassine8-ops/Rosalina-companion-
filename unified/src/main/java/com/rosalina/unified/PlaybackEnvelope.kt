package com.rosalina.unified

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Bounded RMS envelope; never stores the user's words or complete PCM. */
internal class PlaybackEnvelope(private val maximumWindows:Int=6000) {
    private val windows=ArrayList<Float>()
    private val poses=ArrayList<MouthPose>()
    private var crossings=0;private var previous=0f
    private var rate=0;private var encoding=0;private var channels=1
    private var sum=0.0;private var count=0;private var tail=ByteArray(0)
    @Synchronized fun format(sampleRate:Int,audioFormat:Int,channelCount:Int) {
        windows.clear();poses.clear();crossings=0;previous=0f;rate=sampleRate;encoding=audioFormat;channels=channelCount.coerceAtLeast(1)
        sum=0.0;count=0;tail=ByteArray(0)
    }
    @Synchronized fun add(bytes:ByteArray) {
        if(rate<=0 || windows.size>=maximumWindows)return
        // Android encodings: PCM_16BIT=2, PCM_8BIT=3, PCM_FLOAT=4.
        val sampleBytes=when(encoding){2->2;3->1;4->4;else->return}
        val all=if(tail.isEmpty())bytes else tail+bytes
        val usable=all.size-all.size%sampleBytes
        tail=all.copyOfRange(usable,all.size)
        val b=ByteBuffer.wrap(all,0,usable).order(ByteOrder.LITTLE_ENDIAN)
        val windowSamples=(rate*channels/50).coerceAtLeast(1)
        while(b.remaining()>=sampleBytes && windows.size<maximumWindows) {
            val raw=when(encoding){2->b.short/32768f;3->((b.get().toInt() and 255)-128)/128f;else->b.float}
            val x=if(raw.isFinite())raw.coerceIn(-1f,1f) else 0f
            if((x>=0)!=(previous>=0))crossings++;previous=x
            sum+=x.toDouble()*x;count++
            if(count>=windowSamples){
                val energy=(sqrt(sum/count)*4.5).toFloat().coerceIn(0f,1f)
                windows.add(energy);poses.add(PcmMouth.estimate(energy,crossings.toFloat()/count));count=0;sum=0.0;crossings=0
            }
        }
    }
    @Synchronized fun atMillis(milliseconds:Long):Float? = windows.getOrNull((milliseconds.coerceAtLeast(0)/20).toInt())
    @Synchronized fun poseAtMillis(milliseconds:Long):MouthPose? = poses.getOrNull((milliseconds.coerceAtLeast(0)/20).toInt())
    @Synchronized fun frameMillis(frame:Int):Long? = if(rate>0)frame*1000L/rate else null
    @Synchronized fun hasData()=windows.isNotEmpty()
}

internal data class SpeechObservation(val event:String,val utteranceId:String,val energy:Float=0f,val source:String="unavailable",val mouth:MouthPose?=null)
internal object VoiceV3Policy {
    fun eligible(requested:Boolean,approved:Boolean,installed:Boolean,failed:Boolean,thermal:Int,lowMemory:Boolean):Boolean =
        requested && approved && installed && !failed && !lowMemory && thermal in 0..1
    fun retryThroughFallback(playbackStarted:Boolean,userCancelled:Boolean):Boolean = !playbackStarted && !userCancelled
}
