package com.rosalina.motion
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test

class ThermalPolicyTest {
    @Test fun normalUsesFourThreads(){
        assertEquals(4,ThermalPolicy.workerThreads(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(4,ThermalPolicy.workerThreads(PowerManager.THERMAL_STATUS_LIGHT))
    }
    @Test fun moderateReducesLoad(){
        assertEquals(3,ThermalPolicy.workerThreads(PowerManager.THERMAL_STATUS_MODERATE))
    }
    @Test fun severePausesAndCriticalAborts(){
        assertTrue(ThermalPolicy.shouldPause(PowerManager.THERMAL_STATUS_SEVERE))
        assertFalse(ThermalPolicy.shouldAbort(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalPolicy.shouldAbort(PowerManager.THERMAL_STATUS_CRITICAL))
    }
}
