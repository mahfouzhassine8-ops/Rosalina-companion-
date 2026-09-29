package com.rosalina.unified

import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Pocket model + JNI + AudioTrack on Android x86_64; not a stub or Samsung acceptance. */
@RunWith(AndroidJUnit4::class)
class ExpressivePocketAuditTest {
    @Test fun pinnedPocketPackSynthesizesPlaysCancelsAndRecovers()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File("/data/local/tmp/rosalina-voice-v3.zip")
        assertTrue("CI must supply the actual pinned candidate, not skip this test",source.isFile)
        val store=VoiceV3Models(context)
        withTimeout(90_000){store.importPack(Uri.fromFile(source)){_,_->}}
        val audio=context.getSystemService(AudioManager::class.java)
        val oldVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val rpc=EngineRpc(context,ExpressiveSpeechService::class.java)
        val evidence=StringBuilder("Actual Pocket INT8 model / sherpa JNI / Android x86_64 PCM playback. Not Samsung audibility or expressive-quality acceptance.\n")
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,maxOf(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2),0)
                suspend fun say(text:String):Bundle=withTimeout(60_000){rpc.call(Bundle().apply{putString("operation","speak");putString("text",text)}){}}
                val first=say("Hello. I am Rosalina. Let us take this one step at a time.")
                assertTrue("Playback head must advance",first.getLong("playedFrames")>0)
                assertTrue("Generated audio must be nonempty",first.getLong("audioMs")>0)
                assertTrue(first.getString("engine").orEmpty().contains("Pocket"))
                evidence.append("FIRST ").append(first).append('\n')
                val began=CompletableDeferred<Unit>()
                val long=launch {
                    rpc.call(Bundle().apply{putString("operation","speak");putString("text","This sentence is intentionally long enough to exercise interruption during actual playback. After stopping, the next sentence must still be able to speak.")}){event->
                        if(event.getString("type")=="playback" && event.getString("text")=="start")began.complete(Unit)
                    }
                }
                withTimeout(60_000){began.await()}
                val stopAt=SystemClock.elapsedRealtime();rpc.interruptNow();long.cancelAndJoin();rpc.shutdown()
                assertTrue("Owned candidate worker cleanup must be bounded",SystemClock.elapsedRealtime()-stopAt<6000)
                val next=say("The next sentence is a new request, not a replay.")
                assertTrue(next.getLong("playedFrames")>0)
                evidence.append("RECOVERY ").append(next).append('\n')
                val output=File(context.filesDir,"audit-qa").apply{mkdirs()}
                File(output,"pocket-android-playback.txt").writeText(evidence.toString())
            } finally {
                withContext(NonCancellable){rpc.shutdown()}
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,oldVolume,0)
            }
        }
    }
}
