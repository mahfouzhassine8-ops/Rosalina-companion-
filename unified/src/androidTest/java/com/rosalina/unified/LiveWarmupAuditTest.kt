package com.rosalina.unified

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CI-only model-slot fixtures: memory rejection occurs BEFORE native model loading. */
@RunWith(AndroidJUnit4::class)
class LiveWarmupAuditTest {
    @Test fun rejectedLiveWarmupIsAnErrorNotAnApplicationCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("rosalina-unified", Context.MODE_PRIVATE)
        prefs.edit().putString("section", "COMPANION").putString("companion-mode", "LIVE")
            .putBoolean("live-voice", true).commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val session = Session.get(context)
        // A small CI emulator must take the existing production low-memory rejection path.
        assertTrue("Reproduction requires the small CI emulator", ThermalManager(context).read().available < 3_500_000_000L)
        val field = ModelStore::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val original = field.get(session.models)
        val fixture = File(context.cacheDir, "live-memory-fixture").apply { mkdirs() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val registry = JSONObject()
                    for (key in listOf(ModelKey.CHAT, ModelKey.STT, ModelKey.TTS))
                        registry.put(key.name, JSONObject().put("path", fixture.path))
                    field.set(session.models, registry)
                    session.interruptAndListen()
                }
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline &&
                    (session.state.value.busy || session.state.value.error.isBlank())) Thread.sleep(50)
                scenario.onActivity {
                    assertFalse("Live failure must release task ownership", session.state.value.busy)
                    assertTrue("The actual memory error must be retained", session.state.value.error.contains("RAM"))
                }
            }
        } finally {
            field.set(session.models, original)
            fixture.deleteRecursively()
        }
    }
}
