package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class LiveVoiceTest {
    @Test fun liveEndpointIsBounded(){
        assertEquals(550,LiveVoiceTuning(endpointMs=1).safe().endpointMs)
        assertEquals(1400,LiveVoiceTuning(endpointMs=9999).safe().endpointMs)
    }
    @Test fun liveChunkPrefersSentence(){
        val s="That sounds very good and natural to me. I can keep going with the next thought."
        assertEquals("That sounds very good and natural to me. ".length,LiveSpeechChunker.cut(s))
    }
    @Test fun liveChunkCanUseNaturalClause(){
        val s="I can do that for you, and I can make the delivery softer after that."
        assertEquals("I can do that for you, ".length,LiveSpeechChunker.cut(s,LiveVoiceTuning(minClauseChars=14)))
    }
    @Test fun liveChunkDoesNotSpeakTinyFragment(){
        assertEquals(0,LiveSpeechChunker.cut("Yes, okay",LiveVoiceTuning(minClauseChars=14),eager=true))
    }
    @Test fun eagerFirstPhraseStartsBeforeFullReply(){
        val s="I can help you with that right now and keep the rest of my answer flowing naturally."
        val n=LiveSpeechChunker.cut(s,LiveVoiceTuning(firstChunkChars=44,clauseChars=96),eager=true)
        assertTrue(n in 32..48)
        assertTrue(n<s.length)
    }
    @Test fun laterPhraseWaitsForLargerNaturalChunk(){
        val s="I can help you with that right now and keep the rest flowing naturally without rushing."
        assertEquals(0,LiveSpeechChunker.cut(s,LiveVoiceTuning(firstChunkChars=44,clauseChars=96),eager=false))
    }
    @Test fun liveChunkFallsBackAtBoundedLength(){
        val s=("word ".repeat(40)).trim()
        val n=LiveSpeechChunker.cut(s,LiveVoiceTuning(clauseChars=96,minClauseChars=24),eager=false)
        assertTrue(n in 24..97)
    }
}
