package com.rosalina.unified

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlatformPlaybackAuditTest {
    @Test fun realAndroidTtsPlaybackEventsCancellationAndRecovery()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val audio=ctx.getSystemService(AudioManager::class.java)
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val events=CopyOnWriteArrayList<SpeechObservation>()
        val tts=PlatformSpeech(ctx,ctx.packageName)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                // Only changes the disposable CI emulator. Production code never raises system volume.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,maxOf(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2),0)
                val first=withTimeout(15000){tts.speak("fixture-one",1f,observe={events.add(it)})}
                assertTrue(first.offline)
                assertTrue("Real platform playback onStart must be observed",events.any{it.event=="start"})
                assertTrue("Stop event must be emitted",events.last().event=="stop")
                assertTrue("No lips before playback start",events.indexOfFirst{it.event=="energy"}.let{n->n<0 || n>events.indexOfFirst{it.event=="start"}})
                events.clear()
                val started=CompletableDeferred<Unit>()
                val long=launch{tts.speak("fixture-long",1f,onStart={started.complete(Unit)},observe={events.add(it)})}
                withTimeout(10000){started.await()};val stopAt=SystemClock.elapsedRealtime();tts.stop();long.cancelAndJoin()
                assertTrue("Cancelled platform speech must return promptly",SystemClock.elapsedRealtime()-stopAt<2000)
                val recovered=withTimeout(15000){tts.speak("fixture-after-stop",1f,observe={events.add(it)})}
                assertTrue(recovered.playbackMs>0)
                val unique=events.filter{it.event=="start"}.map{it.utteranceId}.distinct()
                assertEquals("Two distinct utterances, not replay",2,unique.size)
                File(ctx.filesDir,"audit-qa").mkdirs()
                File(ctx.filesDir,"audit-qa/platform-playback.txt").writeText("Synthetic PCM passed through real Android TTS framework. Offline voice selection, playback callbacks, Stop and next utterance passed. Not Samsung audibility or human voice quality.\n"+first+"\n"+recovered)
            } finally {tts.stop();tts.shutdown();audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)}
        }
    }
    @Test fun microphoneMuteActuallyStopsAndRestartsRecording() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val ctx=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName,Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val input=VoiceCapture(ctx,false)
            try {input.start();assertTrue(input.recordingActive)
                input.setMuted(true);assertTrue(input.isMuted);assertFalse(input.recordingActive)
                input.setMuted(false);assertFalse(input.isMuted);assertTrue(input.recordingActive)
            }finally{input.close()}
            assertFalse(input.recordingActive)
        }
    }
}
