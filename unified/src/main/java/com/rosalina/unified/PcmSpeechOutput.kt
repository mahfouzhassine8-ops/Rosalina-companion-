package com.rosalina.unified

import android.content.Context
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** Actual playback-head observations, shared by candidate and optional online PCM output. */
internal class PcmSpeechOutput(private val context:Context,private val cancelled:AtomicBoolean) {
    private val current=AtomicReference<AudioTrack?>(null)
    private val currentFocus=AtomicReference<AudioFocusRequest?>(null)
    private val audio=context.getSystemService(AudioManager::class.java)
    fun interrupt(){cancelled.set(true);runCatching{current.get()?.pause();current.get()?.flush()};currentFocus.getAndSet(null)?.let{runCatching{audio.abandonAudioFocusRequest(it)}}}
    suspend fun play(pcm:ShortArray,rate:Int,requestStarted:Long,emit:(String,String,Bundle?)->Unit):Bundle=coroutineScope {
        require(rate in 8000..48000 && pcm.isNotEmpty() && pcm.size<=rate*60){"Invalid PCM speech"}
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({if(it<0)interrupt()},Handler(Looper.getMainLooper())).build()
        var track:AudioTrack?=null;var meter:Job?=null;val started=AtomicBoolean(false);val first=AtomicLong(-1L)
        try {
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0 && !audio.isStreamMute(AudioManager.STREAM_MUSIC)){"Android Media volume is muted"}
            require(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus was not granted"};currentFocus.set(focus)
            if(cancelled.get())throw CancellationException("Speech stopped before playback")
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);require(minimum>0)
            val output=AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum,rate/2)).setTransferMode(AudioTrack.MODE_STREAM).build()
            track=output;current.set(output);require(output.state==AudioTrack.STATE_INITIALIZED){"Speech player failed to initialize"}
            val firstSignal=pcm.indexOfFirst{kotlin.math.abs(it.toInt())>32};require(firstSignal>=0){"Speech PCM is silent"}
            output.play();val playbackStarted=SystemClock.elapsedRealtime()
            meter=launch {
                while(isActive && !cancelled.get()){
                    val head=(output.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(pcm.size.toLong()).toInt()
                    if(head>firstSignal && started.compareAndSet(false,true)){first.set(SystemClock.elapsedRealtime()-requestStarted)
                        emit("playback","start",Bundle().apply{putInt("route",output.routedDevice?.type ?: -1)})}
                    if(started.get()){
                        val end=minOf(pcm.size,head+rate/50);var square=0.0;var crossings=0;var previous=0
                        for(i in head until end){val value=pcm[i].toInt();if((value>=0)!=(previous>=0))crossings++;previous=value;square+=value/32768.0*(value/32768.0)}
                        val count=end-head;val energy=if(count>0)(sqrt(square/count)*4.5).toFloat().coerceIn(0f,1f) else 0f
                        val mouth=PcmMouth.estimate(energy,if(count>0)crossings.toFloat()/count else 0f)
                        emit("energy","PCM / playback-head articulation estimate",Bundle().apply{putFloat("energy",energy);putFloat("mouthOpen",mouth.open);putFloat("mouthWide",mouth.wide);putFloat("mouthRound",mouth.round)})
                    };delay(33)
                }
            }
            var offset=0;var lastWrite=SystemClock.elapsedRealtime()
            while(offset<pcm.size){
                currentCoroutineContext().ensureActive();if(cancelled.get())throw CancellationException("Speech playback interrupted")
                val n=output.write(pcm,offset,minOf(2048,pcm.size-offset),AudioTrack.WRITE_NON_BLOCKING);check(n>=0){"PCM write failed: $n"}
                if(n==0){check(SystemClock.elapsedRealtime()-lastWrite<3000){"PCM output stalled"};delay(10)}else{offset+=n;lastWrite=SystemClock.elapsedRealtime()}
            }
            val deadline=SystemClock.elapsedRealtime()+pcm.size*1000L/rate+2000
            while((output.playbackHeadPosition.toLong() and 0xffffffffL)<offset){
                currentCoroutineContext().ensureActive();if(cancelled.get())throw CancellationException("Speech playback interrupted")
                check(SystemClock.elapsedRealtime()<deadline){"PCM playback did not drain"};delay(15)
            }
            if(started.compareAndSet(false,true)){first.set(SystemClock.elapsedRealtime()-requestStarted);emit("playback","start",null)}
            Bundle().apply{putLong("firstAudioMs",first.get());putLong("audioMs",pcm.size*1000L/rate);putLong("playbackMs",SystemClock.elapsedRealtime()-playbackStarted)
                putLong("playedFrames",output.playbackHeadPosition.toLong() and 0xffffffffL);putInt("underruns",output.underrunCount);putInt("route",output.routedDevice?.type ?: -1)}
        }finally {
            withContext(NonCancellable){meter?.cancelAndJoin()}
            emit("playback","stop",null);current.compareAndSet(track,null)
            runCatching{track?.pause();track?.flush();track?.release()}
            currentFocus.compareAndSet(focus,null);runCatching{audio.abandonAudioFocusRequest(focus)}
        }
    }
}
