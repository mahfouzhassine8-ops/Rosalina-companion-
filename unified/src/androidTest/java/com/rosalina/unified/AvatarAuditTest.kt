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
    @Test fun portraitDrawsRealMouthAndBlinkFrames() {
        val renderer=PortraitAvatarRenderer.load(context)
        assertEquals(1025,renderer.bitmap.width);assertEquals(1535,renderer.bitmap.height)
        val folder=java.io.File(context.filesDir,"audit-qa").apply{mkdirs()}
        val base=RigPose(FacePose(),0f,0f,0f,0f,0f,0f,0f,0f,MouthPose(),Gesture.NONE,0f)
        fun draw(p:RigPose,name:String):android.graphics.Bitmap {
            val b=android.graphics.Bitmap.createBitmap(1025,1535,android.graphics.Bitmap.Config.ARGB_8888)
            renderer.draw(android.graphics.Canvas(b),1025,1535,p)
            java.io.File(folder,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            return b
        }
        val idle=draw(base,"portrait-idle.png")
        val mouth=draw(base.copy(mouth=MouthPose(.9f,.6f,.2f)),"portrait-speaking.png")
        val blink=draw(base.copy(blink=1f),"portrait-blink.png")
        fun changed(a:android.graphics.Bitmap,b:android.graphics.Bitmap,x0:Int,y0:Int,x1:Int,y1:Int):Int {
            var n=0;for(y in y0 until y1)for(x in x0 until x1)if(a.getPixel(x,y)!=b.getPixel(x,y))n++;return n
        }
        // Full-frame content must change inside the rendered mouth and eye regions.
        assertTrue("Playback mouth must visibly articulate",changed(idle,mouth,480,200,550,260)>30)
        assertTrue("Blink must visibly close both eyes",changed(idle,blink,430,130,600,210)>200)
        for(i in 0 until 16){
            val t=i/16f;val phase=kotlin.math.sin(t*6.283185f)
            val b=draw(base.copy(mouth=MouthPose(kotlin.math.abs(phase)*.85f,.4f,.2f),blink=if(i==6)1f else 0f,
                sway=phase*.5f,head=phase,leftArm=phase*2f,rightArm=-phase,hair=phase*.7f,breath=phase*.002f),"portrait-frame-%02d.png".format(i));b.recycle()
        }
        idle.recycle();mouth.recycle();blink.recycle()
    }
}
