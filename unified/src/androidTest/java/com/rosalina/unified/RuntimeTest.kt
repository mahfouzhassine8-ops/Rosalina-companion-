package com.rosalina.unified

import android.content.Context
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun chatStreamingReusesTextViewAndKeepsEveryCharacter() {
        context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit().putString("section","COMPANION").putString("companion-mode","CHAT").putString("tab","Chat").commit()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{activity->
            val update=MainActivity::class.java.getDeclaredMethod("update",TaskState::class.java).apply{isAccessible=true}
            val field=MainActivity::class.java.getDeclaredField("streaming").apply{isAccessible=true}
            val base=TaskState(id="qa-ui-only",kind=TaskKind.CHAT,busy=true,stage="QA synthetic UI text — no inference")
            update.invoke(activity,base)
            val view=field.get(activity) as TextView
            val expected=StringBuilder();val start=SystemClock.elapsedRealtime()
            repeat(500){expected.append("word $it 🌸 ");update.invoke(activity,base.copy(answer=expected.toString()))}
            assertSame(view,field.get(activity));assertEquals(expected.toString(),view.text.toString())
            android.util.Log.i("RosalinaQA","500 synthetic UI deltas=${SystemClock.elapsedRealtime()-start} ms; not Samsung or model speed")
        }}
    }
    @Test fun onlyVerifiedChildCanBePausedAndResumed():Unit=runBlocking {
        val log=File(context.cacheDir,"owned-child.pid")
        val process=ProcessBuilder("/system/bin/sh","-c","echo $$; exec /system/bin/sleep 30").redirectOutput(log).start()
        try {
            var pid=0
            withTimeout(5000){while(pid==0){pid=runCatching{log.readText().trim().toInt()}.getOrDefault(0);delay(25)}}
            delay(100)
            val invalid=OwnedChild(process,"/not/the/executable");invalid.claim(pid);assertEquals(0,invalid.pid)
            val owned=OwnedChild(process,"/system/bin/sleep");owned.claim(pid);assertEquals(pid,owned.pid)
            owned.pause(true);assertTrue(owned.paused);assertTrue(process.isAlive)
            owned.pause(false);assertFalse(owned.paused);assertTrue(process.isAlive)
        }finally{process.destroyForcibly();process.waitFor(2000,TimeUnit.MILLISECONDS);log.delete()}
    }
}
