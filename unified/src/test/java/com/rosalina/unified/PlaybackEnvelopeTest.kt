package com.rosalina.unified
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
class PlaybackEnvelopeTest {
    @Test fun pcmEnvelopeIsMeasuredAndBounded() {
        val e=PlaybackEnvelope(2);e.format(1000,2,1)
        val raw=ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN)
        repeat(20){raw.putShort(0)};repeat(60){raw.putShort(8000)}
        val b=raw.array();e.add(b.copyOfRange(0,11));e.add(b.copyOfRange(11,b.size))
        assertEquals(0f,e.atMillis(0)!!,0f);assertTrue(e.atMillis(20)!!>.5f);assertNull(e.atMillis(40))
    }
    @Test fun invalidFloatsCannotPoisonEnergy() {
        val e=PlaybackEnvelope();e.format(1000,4,1)
        val b=ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);repeat(20){b.putFloat(Float.NaN)};e.add(b.array())
        assertEquals(0f,e.atMillis(0)!!,0f)
    }
    @Test fun voiceCannotPromoteBeforePhysicalAcceptanceOrWhenHot() {
        assertFalse(VoiceV3Policy.eligible(true,false,true,false,0,false))
        assertFalse(VoiceV3Policy.eligible(true,true,true,false,2,false))
        assertFalse(VoiceV3Policy.eligible(true,true,true,true,0,false))
        assertTrue(VoiceV3Policy.eligible(true,true,true,false,0,false))
    }
    @Test fun fallbackNeverReplaysAStartedOrCancelledClause() {
        assertTrue(VoiceV3Policy.retryThroughFallback(false,false))
        assertFalse(VoiceV3Policy.retryThroughFallback(true,false))
        assertFalse(VoiceV3Policy.retryThroughFallback(false,true))
    }
}
