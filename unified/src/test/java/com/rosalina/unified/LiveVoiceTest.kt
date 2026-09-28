package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class LiveVoiceTest {
    @Test fun liveEndpointIsBounded(){
        assertEquals(550,LiveVoiceTuning(endpointMs=1).safe().endpointMs)
        assertEquals(1400,LiveVoiceTuning(endpointMs=9999).safe().endpointMs)
    }
    @Test fun liveChunkPrefersSentence(){
        val s="That sounds good. I can keep going with the next thought."
        assertEquals("That sounds good. ".length,LiveSpeechChunker.cut(s))
    }
    @Test fun liveChunkCanUseNaturalClause(){
        val s="I can do that for you, and I can make the delivery softer after that."
        assertEquals("I can do that for you, ".length,LiveSpeechChunker.cut(s,LiveVoiceTuning(minClauseChars=16)))
    }
    @Test fun liveChunkDoesNotSpeakTinyFragment(){
        assertEquals(0,LiveSpeechChunker.cut("Yes, okay",LiveVoiceTuning(minClauseChars=16)))
    }
    @Test fun liveChunkFallsBackAtBoundedLength(){
        val s=("word ".repeat(40)).trim()
        val n=LiveSpeechChunker.cut(s,LiveVoiceTuning(clauseChars=100,minClauseChars=24))
        assertTrue(n in 24..100)
    }
}
