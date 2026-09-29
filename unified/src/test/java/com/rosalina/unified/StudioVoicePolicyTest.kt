package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class StudioVoicePolicyTest {
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun normalizesOnlyServiceRootOrV1(){
        assertEquals("https://mac.local:3900",StudioEndpoint.parse("https://mac.local:3900/v1/").root)
        assertEquals("https://mac.local",StudioEndpoint.parse("https://mac.local/").root)
        assertEquals("https://[fd7a:115c:a1e0::1]",StudioEndpoint.parse("https://[fd7a:115c:a1e0::1]/v1").root)
    }
    @Test fun refusesPlaintextAndCredentialsInAddress(){
        listOf("http://192.168.1.5:3900","https://key@mac.local","https://mac.local/?api_key=secret","https://mac.local/#token","https://mac.local/v1/audio/speech","https://mac.local:0","https://mac.local:65536","https://localhost","https://mac.local/../v1","https://mac.local/%76%31"," https://mac.local").forEach{value->rejected{StudioEndpoint.parse(value)}}
    }
    @Test fun peerPolicyAllowsOnlyPrivateAndTailnetAddresses(){
        listOf("10.0.0.3","172.16.0.1","172.31.255.1","192.168.1.2","100.64.0.1","100.127.255.254","fd7a:115c:a1e0::1","fc00::10").forEach{assertTrue(it,StudioNetworkPolicy.isPrivate(InetAddress.getByName(it)))}
        listOf("8.8.8.8","172.32.0.1","100.63.255.1","100.128.0.1","127.0.0.1","0.0.0.0","169.254.169.254","224.0.0.1","::1","::","fe80::1","2001:4860:4860::8888").forEach{assertFalse(it,StudioNetworkPolicy.isPrivate(InetAddress.getByName(it)))}
    }
    @Test fun pcmCarriesOddBytesAndSignedSamples(){
        val decoder=StudioPcmDecoder()
        assertArrayEquals(shortArrayOf(),decoder.decode(byteArrayOf(1)))
        assertArrayEquals(shortArrayOf(513),decoder.decode(byteArrayOf(2,0)))
        assertArrayEquals(shortArrayOf(-32768,-1),decoder.decode(byteArrayOf(-128,-1,-1)))
        decoder.finish();assertEquals(6,decoder.bytes)
    }
    @Test fun pcmRejectsTruncationEmptyAndOversize(){
        rejected{StudioPcmDecoder().finish()}
        val odd=StudioPcmDecoder();odd.decode(byteArrayOf(1));rejected{odd.finish()}
        val big=StudioPcmDecoder();rejected{big.decode(ByteArray(2_880_002))}
    }
    @Test fun gainAppliedWithoutPitchOrTimingChanges(){assertArrayEquals(shortArrayOf(24575,-24576),StudioPcmDecoder(.75f).decode(byteArrayOf(-1,127,0,-128)).also{assertEquals(2,it.size)})}
    @Test fun defaultDoesNotPretendStylesAreSupported(){assertNull(StudioStyle.instructions(StudioStyleMode.NONE,PerformanceState(delivery=Delivery.WHISPER)))}
    @Test fun omnivoiceUsesOnlyDocumentedTags(){
        assertEquals("female, whisper",StudioStyle.instructions(StudioStyleMode.OMNIVOICE_TAGS,PerformanceState(delivery=Delivery.WHISPER)))
        assertEquals("female",StudioStyle.instructions(StudioStyleMode.OMNIVOICE_TAGS,PerformanceState(delivery=Delivery.LAUGH)))
    }
    @Test fun instructionsDoNotExposeArbitraryContext(){
        val instruction=StudioStyle.instructions(StudioStyleMode.FREE_TEXT,PerformanceState(emotion="secret conversation",delivery=Delivery.SOFT))!!
        assertFalse(instruction.contains("secret"));assertTrue(instruction.contains("gentle"))
    }
    @Test fun neverDuplicatePartialSpeechOrEscalateToCloud(){
        assertTrue(StudioFallbackPolicy.mayReplayClause(false,false));assertFalse(StudioFallbackPolicy.mayReplayClause(true,false))
        assertFalse(StudioFallbackPolicy.mayReplayClause(false,true));assertFalse(StudioFallbackPolicy.useOnlineAfterStudioFailure())
    }
    @Test fun credentialsNeverAppearInConfigurationToString(){
        val config=StudioConfig(StudioEndpoint.parse("https://mac.local"),"tts-1","default",StudioStyleMode.NONE,"secret-key","123456")
        assertFalse(config.toString().contains("secret-key"));assertFalse(config.toString().contains("123456"))
    }
}
