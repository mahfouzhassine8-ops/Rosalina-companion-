package com.rosalina.unified

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecoveryAuditTest {
    @Test fun missingModelsDoNotCrashOrKeepTaskOwnershipAcrossRepeatedStarts() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit()
            .putString("section","COMPANION").putString("companion-mode","LIVE").putBoolean("live-voice",true).commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val session=Session.get(context)
        assertNull("This regression uses the model-free emulator",session.models.path(ModelKey.STT))
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            repeat(3){
                scenario.onActivity{session.interruptAndListen()}
                val deadline=System.currentTimeMillis()+12000
                while(System.currentTimeMillis()<deadline && (session.state.value.busy || session.state.value.error.isBlank()))Thread.sleep(40)
                scenario.onActivity{
                    assertFalse("Rejected startup must release task ownership",session.state.value.busy)
                    assertTrue("Missing model must be explained",session.state.value.error.contains("Import"))
                    session.stop()
                }
            }
            scenario.recreate()
            scenario.onActivity{assertFalse(session.state.value.busy)}
        }
    }
}
