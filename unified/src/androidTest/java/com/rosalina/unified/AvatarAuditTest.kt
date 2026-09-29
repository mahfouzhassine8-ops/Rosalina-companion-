package com.rosalina.unified
import android.content.Context
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
@RunWith(AndroidJUnit4::class)
class AvatarAuditTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun live(){context.getSharedPreferences("rosalina-unified",0).edit().putString("section","COMPANION").putString("companion-mode","LIVE").putBoolean("live-avatar",true).commit()}
    private fun views(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(views(v.getChildAt(i)))}
    @Test fun artworkLoadsAsynchronouslyAndBackgroundStopsClock(){ActivityScenario.launch(MainActivity::class.java).use{scenario->
        var avatar:LiveAvatarView?=null;scenario.onActivity{avatar=views(it.window.decorView).filterIsInstance<LiveAvatarView>().first()}
        repeat(30){Thread.sleep(100);var ready=false;scenario.onActivity{ready=avatar!!.imageLoaded};if(ready)return@repeat}
        scenario.onActivity{assertTrue("Primary rig or explicit emergency recovery must load",avatar!!.imageLoaded)}
        scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(150);var count=0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync{assertFalse(avatar!!.motionRunning);count=avatar!!.renderedFrames};Thread.sleep(150)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{assertEquals(count,avatar!!.renderedFrames)}
        scenario.moveToState(Lifecycle.State.RESUMED);scenario.onActivity{assertFalse(it.isFinishing)}
    }}
    @Test fun phaseCannotBeInferredFromUnplayedText(){val r=CompanionRuntime();r.begin("qa",1);r.processing("qa",true,2);r.preparingSpeech("qa","speech",RosalinaExpression.HAPPY,"test",3);assertEquals(CompanionPhase.THINKING,r.snapshot.phase);assertEquals(MouthPose(),r.snapshot.mouth)}
    @Test fun interruptionRejectsLatePlayback(){val r=CompanionRuntime();r.begin("qa",1);r.preparingSpeech("qa","old",RosalinaExpression.SPEAKING,"test",2);r.playback("qa","old",true,3);r.energy("qa","old",.7f,"test PCM",MouthPose(.7f));r.interrupt("qa",4);r.playback("qa","old",true,5);assertEquals(CompanionPhase.INTERRUPTED,r.snapshot.phase);assertEquals(MouthPose(),r.snapshot.mouth)}
}
