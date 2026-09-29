package com.rosalina.unified

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPersistenceAuditTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun resetPane(){context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit()
        .putString("section","COMPANION").putString("companion-mode","CHAT").putString("photo-mode","CREATE").commit()}
    private fun descendants(v:View):Sequence<View> = sequence{
        yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))
    }
    private fun clickLabel(a:MainActivity,label:String){
        val b=descendants(a.window.decorView).filterIsInstance<Button>().first{it.contentDescription?.toString()==label}
        assertTrue(b.performClick())
    }
    @Test fun latestKeystrokesSurviveImmediateRecreation(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{it.findViewById<EditText>(1001).setText("Immediate draft preservation")}
            scenario.recreate()
            scenario.onActivity{assertEquals("Immediate draft preservation",it.findViewById<EditText>(1001).text.toString())}
        }
    }
    @Test fun focusBuildHidesMediaSectionsAndPreservesChatDraft(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{a->
                a.findViewById<EditText>(1001).setText("Keep this chat request")
                clickLabel(a,"Settings")
                clickLabel(a,"Chat")
                assertEquals("Keep this chat request",a.findViewById<EditText>(1001).text.toString())
                val descriptions=descendants(a.window.decorView).mapNotNull{it.contentDescription?.toString()}.toList()
                assertFalse(descriptions.contains("Photo"))
                assertFalse(descriptions.contains("Animate"))
            }
        }
    }
    @Test fun backClosesDrawerInsteadOfClosingActivity(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{a->
            clickLabel(a,"Open navigation menu")
            val scrim=descendants(a.window.decorView).first{it.contentDescription?.toString()=="Close navigation menu"}
            assertEquals(View.VISIBLE,scrim.visibility)
            a.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.GONE,scrim.visibility)
            assertFalse(a.isFinishing)
        }}
    }
}
