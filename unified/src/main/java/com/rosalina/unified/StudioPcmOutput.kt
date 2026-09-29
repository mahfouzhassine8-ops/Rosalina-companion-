package com.rosalina.unified

import android.content.Context
import android.media.*
import android.os.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.*
import kotlin.math.abs
import kotlin.math.sqrt

/** Incremental 24-kHz PCM playback. No WAV/MP3 guessing and no wait for the complete response. */
internal class StudioPcmOutput(context:Context) {
    private val audio=context.getSystemService(AudioManager::class.java)
    private class Active {val stopped=AtomicBoolean(false);val track=AtomicReference<AudioTrack?>(null);val focus=AtomicReference<AudioFocusRequest?>(null)}
    private val active=AtomicReference<Active?>(null)
    fun stop(){active.get()?.let{a->a.stopped.set(true);runCatching{a.track.get()?.pause();a.track.get()?.flush()};a.focus.getAndSet(null)?.let{runCatching{audio.abandonAudioFocusRequest(it)}}}}
    suspend fun play(exchange:StudioExchange,volume:Float,emit:(String,String,Bundle?)->Unit):Bundle=withContext(Dispatchers.Default){coroutineScope {
        val a=Active();check(active.compareAndSet(null,a)){"Studio playback already active"}
        val rate=24000;val decoder=StudioPcmDecoder(volume)
        // Retained only for playback-head metering; capped at 60 s / 2.88 MB, erased at completion.
        val history=ShortArray(rate*60);val available=AtomicInteger(0);val firstSignal=AtomicInteger(Int.MAX_VALUE)
        val signalled=AtomicBoolean(false);val firstAudio=AtomicLong(-1);var playbackAt=0L
        var track:AudioTrack?=null;var focus:AudioFocusRequest?=null;var meter:Job?=null;var written=0
        fun checkStopped(){if(a.stopped.get() || exchange.cancelled.get())throw CancellationException("Studio playback stopped")}
        fun observe(output:AudioTrack) {
            val head=(output.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(available.get().toLong()).toInt()
            if(head>firstSignal.get() && signalled.compareAndSet(false,true)){
                firstAudio.set(SystemClock.elapsedRealtime()-exchange.startedMs)
                emit("playback","start",Bundle().apply{putInt("route",output.routedDevice?.type ?: -1)})
            }
            if(signalled.get()){
                val end=minOf(available.get(),head+rate/50);var square=0.0;var crossings=0;var prev=0
                for(i in head until end){val v=history[i].toInt();if(i>head && (v>=0)!=(prev>=0))crossings++;prev=v;square+=(v/32768.0)*(v/32768.0)}
                val count=end-head;val energy=if(count>0)(sqrt(square/count)*4.5).toFloat().coerceIn(0f,1f)else 0f
                val pose=PcmMouth.estimate(energy,if(count>0)crossings.toFloat()/count else 0f)
                emit("energy","Studio PCM / playback-head estimate",Bundle().apply{putFloat("energy",energy);putFloat("mouthOpen",pose.open);putFloat("mouthWide",pose.wide);putFloat("mouthRound",pose.round)})
            }
        }
        try {
            while(true){
                currentCoroutineContext().ensureActive();checkStopped()
                val raw=exchange.next() ?:break
                val pcm=try{decoder.decode(raw)}catch(_:IllegalArgumentException){throw StudioVoiceException(StudioFailure.FORMAT)}finally{raw.fill(0)}
                if(pcm.isEmpty())continue
                val start=available.get();pcm.copyInto(history,start);available.set(start+pcm.size)
                if(firstSignal.get()==Int.MAX_VALUE){val local=pcm.indexOfFirst{abs(it.toInt())>32};if(local>=0)firstSignal.compareAndSet(Int.MAX_VALUE,start+local)}
                if(track==null){
                    require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0 && !audio.isStreamMute(AudioManager.STREAM_MUSIC)){"Android Media volume is muted"}
                    val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener({if(it<0){stop();exchange.cancel()}},Handler(Looper.getMainLooper())).build()
                    focus=f
                    if(audio.requestAudioFocus(f)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw StudioVoiceException(StudioFailure.AUDIO)
                    a.focus.set(f);checkStopped()
                    val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    if(minimum<=0)throw StudioVoiceException(StudioFailure.AUDIO)
                    val out=AudioTrack.Builder().setAudioAttributes(attrs)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,rate/4)).build()
                    track=out;a.track.set(out);checkStopped()
                    if(out.state!=AudioTrack.STATE_INITIALIZED)throw StudioVoiceException(StudioFailure.AUDIO)
                    playbackAt=SystemClock.elapsedRealtime();out.play()
                    meter=launch{while(isActive && !a.stopped.get()){observe(out);delay(25)}}
                }
                val out=track!!;var offset=0;var progressAt=SystemClock.elapsedRealtime()
                while(offset<pcm.size){
                    currentCoroutineContext().ensureActive();checkStopped()
                    val count=out.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                    if(count<0)throw StudioVoiceException(StudioFailure.AUDIO)
                    if(count==0){if(SystemClock.elapsedRealtime()-progressAt>3000)throw StudioVoiceException(StudioFailure.TIMEOUT);delay(10)}
                    else{offset+=count;written+=count;progressAt=SystemClock.elapsedRealtime()}
                }
                pcm.fill(0)
            }
            try{decoder.finish()}catch(_:IllegalArgumentException){throw StudioVoiceException(StudioFailure.FORMAT)}
            val out=track ?:throw StudioVoiceException(StudioFailure.FORMAT)
            if(firstSignal.get()==Int.MAX_VALUE)throw StudioVoiceException(StudioFailure.FORMAT)
            var previous=-1L;var progressAt=SystemClock.elapsedRealtime()
            while(true){
                currentCoroutineContext().ensureActive();checkStopped()
                val head=out.playbackHeadPosition.toLong() and 0xffffffffL;observe(out)
                if(head>=written)break
                if(head!=previous){previous=head;progressAt=SystemClock.elapsedRealtime()}
                if(SystemClock.elapsedRealtime()-progressAt>3000)throw StudioVoiceException(StudioFailure.TIMEOUT)
                delay(10)
            }
            Bundle().apply{putLong("setupMs",exchange.setupMs.get());putLong("firstAudioMs",firstAudio.get());putLong("playbackMs",SystemClock.elapsedRealtime()-playbackAt)
                putLong("audioMs",written*1000L/rate);putLong("playedFrames",out.playbackHeadPosition.toLong() and 0xffffffffL);putInt("underruns",out.underrunCount);putString("engine","Studio Voice · private network");putBoolean("offline",false)}
        } finally {
            withContext(NonCancellable){meter?.cancelAndJoin()}
            // The head may have moved between the last meter sample and a network failure.
            // Preserve that evidence so fallback cannot duplicate the already-spoken beginning.
            if(!a.stopped.get())runCatching{track?.let{observe(it)}}
            emit("playback","stop",null);a.stopped.set(true)
            runCatching{track?.pause();track?.flush();track?.release()};a.track.set(null)
            focus?.let{f->a.focus.compareAndSet(f,null);runCatching{audio.abandonAudioFocusRequest(f)}}
            active.compareAndSet(a,null);history.fill(0)
        }
    }}
}
