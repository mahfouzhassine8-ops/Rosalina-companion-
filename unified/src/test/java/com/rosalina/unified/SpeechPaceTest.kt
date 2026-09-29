package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test
class SpeechPaceTest {
    @Test fun paceAndDurationsAreFiniteAndBounded(){
        assertEquals(1f,SpeechPace.bounded(Float.NaN),0f)
        assertEquals(1f,SpeechPace.bounded(Float.POSITIVE_INFINITY),0f)
        assertEquals(.8f,SpeechPace.bounded(-5f),0f)
        assertEquals(1.2f,SpeechPace.bounded(9f),0f)
        assertEquals(1000L,SpeechPace.durationMillis(24000,24000,1f))
        assertTrue(SpeechPace.durationMillis(24000,24000,.8f)>=1250)
        assertTrue(SpeechPace.durationMillis(24000,24000,1.2f) in 833..834)
    }
}
