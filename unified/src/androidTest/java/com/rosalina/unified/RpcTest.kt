package com.rosalina.unified

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests IPC ownership and cleanup using the explicitly labelled emulator-only echo endpoint. */
@RunWith(AndroidJUnit4::class)
class RpcTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sequentialRequestsAndConfirmedProcessRelease():Unit=runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val rpc=EngineRpc(context,ChatService::class.java)
            try {
                repeat(20){n->
                    val result=withTimeout(15000){rpc.call(Bundle().apply{putString("operation","qa-protocol");putInt("sequence",n)})}
                    assertEquals(n,result.getInt("sequence"));assertTrue(rpc.pid>0)
                }
                rpc.shutdown();assertEquals(0,rpc.pid)
                val result=withTimeout(15000){rpc.call(Bundle().apply{putString("operation","qa-protocol");putInt("sequence",99)})}
                assertEquals(99,result.getInt("sequence"))
            } finally {rpc.shutdown()}
        }
    }
    @Test fun cancellationCannotLeaveAnEngineResident():Unit=runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val rpc=EngineRpc(context,ChatService::class.java)
            val launched=CompletableDeferred<Unit>()
            val job=launch(Dispatchers.IO) {rpc.call(Bundle().apply{putString("operation","qa-protocol");putLong("delay",10000)}){if(it.getInt("pid")>0)launched.complete(Unit)}}
            try {
                withTimeout(15000){launched.await()}
                val started=SystemClock.elapsedRealtime()
                job.cancelAndJoin();rpc.shutdown()
                assertTrue("Engine cleanup was not bounded",SystemClock.elapsedRealtime()-started<5000)
                assertEquals(0,rpc.pid)
            } finally {job.cancelAndJoin();rpc.shutdown()}
        }
    }
}
