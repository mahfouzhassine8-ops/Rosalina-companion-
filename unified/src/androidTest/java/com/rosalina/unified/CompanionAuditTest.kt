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

/** Runs against production UI code. No stub replaces MainActivity or the avatar. */
@RunWith(AndroidJUnit4::class)
class CompanionAuditTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("rosalina-unified", Context.MODE_PRIVATE)

    @Before fun resetNavigation() {
        prefs.edit().putString("section", "COMPANION").putString("companion-mode", "CHAT")
            .putString("tab", "Chat").putBoolean("live-avatar", true).commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun click(activity: MainActivity, text: String) {
        val button = descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == text || it.contentDescription?.toString() == text }
        assertNotNull("Missing control: $text", button)
        assertTrue("Disabled control: $text", button!!.isEnabled)
        assertTrue(button.performClick())
    }

    @Test fun chatLiveSwitchRepeatedlyThenRecreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(5) {
                scenario.onActivity { click(it, "Live Voice") }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(120)
                scenario.onActivity { a ->
                    assertTrue(descendants(a.window.decorView).filterIsInstance<Button>()
                        .any { it.text.toString().equals("Start") })
                    click(a, "Chat")
                }
            }
            scenario.onActivity { click(it, "Live Voice") }
            scenario.recreate()
            scenario.onActivity { click(it, "Chat") }
        }
    }

    @Test fun coldLaunchDirectlyIntoSavedLive() {
        prefs.edit().putString("companion-mode", "LIVE").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(descendants(a.window.decorView).filterIsInstance<Button>()
                    .any { it.text.toString().equals("Start") })
            }
            scenario.recreate()
            scenario.onActivity { click(it, "Chat") }
        }
    }

    @Test fun liveEndBeforeStartingDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> click(a, "Live Voice"); click(a, "End") }
            scenario.onActivity { click(it, "Chat") }
        }
    }
}
