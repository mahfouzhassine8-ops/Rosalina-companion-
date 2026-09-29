package com.rosalina.unified
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class CompanionAuditTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset(){context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit().putString("section","COMPANION").putString("companion-mode","CHAT").putString("tab","Chat").putBoolean("live-avatar",true).commit()}
    private fun views(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(views(v.getChildAt(i)))}
    private fun click(a:MainActivity,label:String){val b=views(a.window.decorView).filterIsInstance<Button>().first{it.isShown && it.contentDescription?.toString()==label};assertTrue(b.isEnabled);assertTrue(b.performClick())}
    private fun select(a:MainActivity,label:String){click(a,"Open navigation menu");click(a,"Drawer $label");assertFalse(views(a.window.decorView).first{it.contentDescription=="Close navigation menu"}.isShown)}
    @Test fun repeatedNavigationUsesHiddenDrawerAndFullLiveWidth(){ActivityScenario.launch(MainActivity::class.java).use{scenario->
        repeat(5){scenario.onActivity{select(it,"Live Voice")};InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity{a->val scene=views(a.window.decorView).first{it.tag=="live-scene"};val content=views(a.window.decorView).first{it.tag=="companion-content"};assertTrue(scene.width>0);assertEquals(content.width,scene.width);assertEquals(content.height,scene.height);select(a,"Chat");assertFalse(views(a.window.decorView).any{it is LiveAvatarView})}}
        scenario.onActivity{select(it,"Live Voice")};scenario.recreate();scenario.onActivity{a->assertTrue(views(a.window.decorView).any{it.isShown && it.contentDescription=="Start Live Voice"});select(a,"Chat")}
    }}
    @Test fun savedLiveRecreatesWithoutStartingMic(){context.getSharedPreferences("rosalina-unified",0).edit().putString("companion-mode","LIVE").commit();ActivityScenario.launch(MainActivity::class.java).use{scenario->
        scenario.onActivity{a->assertTrue(views(a.window.decorView).any{it.isShown && it.contentDescription=="Start Live Voice"});assertFalse(Session.get(a).presentation.snapshot.microphoneActive)};scenario.recreate();scenario.onActivity{select(it,"Chat")}
    }}
    @Test fun endInactiveLiveReturnsToChatWithoutKillingActivity(){ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{a->select(a,"Live Voice");click(a,"End Live Voice");assertFalse(a.isFinishing);assertNotNull(a.findViewById<View>(1001))}}}
}
