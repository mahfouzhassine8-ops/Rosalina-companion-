package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class PhoneCapabilityTest {
    private fun res(available:Long,thermal:Int,low:Boolean=false)=Resources(available,15_800_000_000L,low,thermal,1L)

    @Test fun adaptiveUsesFullProfileWhenPhoneHasHeadroom(){
        val p=PhoneCapabilityPolicy.choose(res(9_500_000_000L,1),"adaptive")
        assertEquals("Adaptive · Full",p.label);assertTrue(p.overlapListening);assertTrue(p.allowPrewarm);assertEquals(1280,p.chatTokenCap)
    }
    @Test fun adaptiveDropsToBalancedBeforeHardThermalPressure(){
        val p=PhoneCapabilityPolicy.choose(res(6_500_000_000L,2),"adaptive")
        assertEquals("Adaptive · Balanced",p.label);assertFalse(p.overlapListening);assertEquals(224,p.liveTokenCap)
    }
    @Test fun adaptiveUsesCoolProfileInsteadOfStoppingConversation(){
        val p=PhoneCapabilityPolicy.choose(res(4_000_000_000L,3),"adaptive")
        assertEquals("Adaptive · Cool",p.label);assertFalse(p.allowPrewarm);assertEquals(144,p.liveTokenCap)
    }
    @Test fun manualModesRemainDeterministic(){
        assertEquals("Phone Cool",PhoneCapabilityPolicy.choose(res(10_000_000_000L,0),"cool").label)
        assertEquals("Phone Maximum",PhoneCapabilityPolicy.choose(res(4_000_000_000L,3),"maximum").label)
    }
}
