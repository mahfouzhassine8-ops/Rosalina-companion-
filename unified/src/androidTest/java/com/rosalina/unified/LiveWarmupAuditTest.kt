package com.rosalina.unified

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-style acceptance for the Live memory policy.
 * 10016 intentionally removed the stale 3.5 GB hard stop: Adaptive Cool must
 * remain usable at the ~3.4 GB headroom observed on the Samsung test device.
 */
@RunWith(AndroidJUnit4::class)
class LiveWarmupAuditTest {
    @Test fun adaptiveCoolAllowsObservedSamsungHeadroomAndKeepsEmergencyFloor() {
        val observed=Resources(
            available=3_400_000_000L,
            total=15_805_669_376L,
            low=false,
            thermal=1,
            measuredAt=1L
        )
        val profile=PhoneCapabilityPolicy.choose(observed,"adaptive")
        assertEquals("Adaptive · Cool",profile.label)
        assertFalse("Cool Live must avoid overlapping listening",profile.overlapListening)
        assertTrue("3.4 GB must no longer be rejected by the stale 3.5 GB gate",PhoneCapabilityPolicy.canStartLive(observed))

        val emergency=observed.copy(available=1_900_000_000L)
        assertFalse("The 2.0 GB emergency floor must still protect Live",PhoneCapabilityPolicy.canStartLive(emergency))

        val androidLow=observed.copy(available=5_000_000_000L,low=true)
        assertFalse("Android's real low-memory signal must still protect Live",PhoneCapabilityPolicy.canStartLive(androidLow))
    }
}
