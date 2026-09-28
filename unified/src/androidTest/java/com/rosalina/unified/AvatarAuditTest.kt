package com.rosalina.unified

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AvatarAuditTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun companion() {
        context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit()
            .putString("section","COMPANION").putString("companion-mode","CHAT")
            .putBoolean("live-avatar",true).commit()
    }
    private fun descendants(v:View):Sequence<View> = sequence {
        yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))
    }
    @Test fun actualPortraitPixelsAnimateWithoutMovingTheWholeFrame() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { a ->
            val view=LiveAvatarView(a).apply{layout(0,0,720,1044)}
            assertTrue(view.imageLoaded)
            fun frame(time:Float):Bitmap = Bitmap.createBitmap(720,1044,Bitmap.Config.ARGB_8888).also{view.renderFrame(Canvas(it),time)}
            val idle=frame(0f);val blink=frame(1.72f)
            try {
                val x=IntArray(720*1044);val y=IntArray(x.size)
                idle.getPixels(x,0,720,0,0,720,1044);blink.getPixels(y,0,720,0,0,720,1044)
                assertTrue("The production renderer must change actual portrait pixels",x.indices.count{x[it]!=y[it]}>100)
                for(row in 20 until 600)for(col in 0..40)assertEquals("The background must not bob",x[row*720+col],y[row*720+col])
                val output=File(context.filesDir,"audit-qa").apply{mkdirs()}
                File(output,"avatar-idle.png").outputStream().use{idle.compress(Bitmap.CompressFormat.PNG,100,it)}
                File(output,"avatar-blink.png").outputStream().use{blink.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally{idle.recycle();blink.recycle()}
        } }
    }
    @Test fun animationClockStopsInBackgroundAndRestartsOnResume() {
        assertTrue("Audit runner must enable system animations",android.animation.ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var view:LiveAvatarView?=null
            scenario.onActivity{view=descendants(it.window.decorView).filterIsInstance<LiveAvatarView>().first()}
            Thread.sleep(400)
            scenario.onActivity{assertTrue("Visible avatar needs a live frame clock",view!!.motionRunning);assertTrue(view!!.renderedFrames>1)}
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(250)
            var stopped=0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync{assertFalse(view!!.motionRunning);stopped=view!!.renderedFrames}
            Thread.sleep(180)
            InstrumentationRegistry.getInstrumentation().runOnMainSync{assertEquals(stopped,view!!.renderedFrames)}
            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(350)
            scenario.onActivity{assertTrue(view!!.motionRunning);assertTrue(view!!.renderedFrames>stopped)}
        }
    }
    @Test fun avatarStatesReflectRealWorkAndErrors() {
        assertEquals(AvatarState.IDLE,AvatarStateResolver.resolve(TaskState()))
        assertEquals(AvatarState.ERROR,AvatarStateResolver.resolve(TaskState(error="Actual failure")))
        assertEquals(AvatarState.THINKING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Warming Live")))
        assertEquals(AvatarState.LISTENING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Listening · LIVE")))
        assertEquals(AvatarState.SPEAKING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,avatarEnergy=.5f)))
        assertEquals(AvatarState.INTERRUPTED,AvatarStateResolver.resolve(TaskState(busy=true,stopping=true)))
    }
}
