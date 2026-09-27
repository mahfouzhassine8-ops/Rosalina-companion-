package com.rosalina.motion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeWorkerTest {
    @Test fun launchesInstalledWorkerInsideAndroidSandbox() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val executable=File(context.applicationInfo.nativeLibraryDir,"librosalina-motion.so")
        assertTrue("APK native worker must be extracted",executable.isFile)
        assertTrue("Installed worker must be executable",executable.canExecute())
        val log=File(context.cacheDir,"worker-self-test.log")
        val p=ProcessBuilder(executable.path,"--self-test").redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue("Native launch timed out",p.waitFor(60,TimeUnit.SECONDS))
            val text=log.readText()
            assertEquals(text,0,p.exitValue())
            assertTrue(text,text.contains("ROSALINA_MOTION_V1"))
            assertTrue(text,text.contains("CPU"))
        } finally {
            if(p.isAlive)p.destroyForcibly()
            log.delete()
        }
    }
}
