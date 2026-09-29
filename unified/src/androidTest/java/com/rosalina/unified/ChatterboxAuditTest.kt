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
@RunWith(AndroidJUnit4::class)
class ChatterboxAuditTest {
    @Test fun realPinnedGraphsPlayStopAndRecoverWithoutReplacingWhisperRuntime()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File("/data/local/tmp/rosalina-chatterbox-phone.zip")
        assertTrue("Actual previously verified Chatterbox pack required",source.isFile)
        val store=VoiceV3Models(context);withTimeout(120_000){store.importPack(Uri.fromFile(source)){_,_->}}
        val prefs=context.getSharedPreferences("rosalina-unified",0)
        assertFalse("Model import must not promote a voice",prefs.getBoolean("voice-v3-primary",false))
        val audio=context.getSystemService(AudioManager::class.java);val old=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val rpc=EngineRpc(context,ExpressiveSpeechService::class.java)
        val output=File(context.filesDir,"audit-qa").apply{mkdirs()}
        val evidence=StringBuilder("Real pinned Chatterbox ONNX graphs, Android Java JNI and AudioTrack. Not human-quality or Samsung acceptance.\n")
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,maxOf(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2),0)
                suspend fun say(text:String)=withTimeout(90_000){rpc.call(Bundle().apply{putString("text",text);putString("operation","speak")}){}}
                val first=say("That's funny! [chuckle]")
                assertTrue(first.getLong("playedFrames")>0);assertTrue(first.getLong("firstAudioMs")>=0);assertTrue(first.getLong("audioMs")>100)
                assertTrue(first.getString("engine").orEmpty().contains("Chatterbox"));evidence.append("FIRST ").append(first).append('\n')
                File(output,"chatterbox-android-playback.txt").writeText(evidence.toString())
                val began=CompletableDeferred<Unit>()
                val speech=launch {
                    rpc.call(Bundle().apply{putString("text","We can take this slowly, one sentence at a time. This sentence gives us a chance to test stopping actual speech.")}){e->
                        if(e.getString("type")=="playback" && e.getString("text")=="start")began.complete(Unit)
                    }
                }
                withTimeout(90_000){began.await()};val at=SystemClock.elapsedRealtime();rpc.interruptNow();speech.cancelAndJoin();rpc.shutdown()
                assertTrue("Owned worker shutdown must be bounded",SystemClock.elapsedRealtime()-at<6000)
                val next=say("Hello again.");assertTrue(next.getLong("playedFrames")>0);evidence.append("RECOVERY ").append(next).append('\n')
                File(output,"chatterbox-android-playback.txt").writeText(evidence.toString())
                assertFalse("Synthesis success is not user acceptance",prefs.getBoolean("voice-v3-primary",false))
            }finally{withContext(NonCancellable){rpc.shutdown()};audio.setStreamVolume(AudioManager.STREAM_MUSIC,old,0)}
        }
    }
}
