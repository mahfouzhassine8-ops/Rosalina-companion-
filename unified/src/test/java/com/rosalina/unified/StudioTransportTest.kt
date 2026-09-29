package com.rosalina.unified

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

class StudioTransportTest {
    private val config=StudioConfig(StudioEndpoint.parse("https://10.10.10.10:3900"),"tts-1","default",StudioStyleMode.NONE,"test-private-key","123456")
    private class FakeConnection(url:URL,val code:Int=200,val type:String="audio/pcm",val stream:InputStream=ByteArrayInputStream(byteArrayOf(1,2,3,4))):HttpURLConnection(url){
        val sent=ByteArrayOutputStream();val closed=CountDownLatch(1)
        override fun connect(){};override fun disconnect(){closed.countDown();stream.close()};override fun usingProxy()=false
        override fun getResponseCode()=code
        override fun getContentType()=type
        override fun getContentLengthLong()=-1L
        override fun getInputStream()=stream
        override fun getOutputStream():OutputStream=sent
    }
    @Test fun requestHasHeaderAuthenticationAndNoAutomaticRedirects()=runBlocking {
        lateinit var con:FakeConnection
        val transport=StudioTransport(StudioConnectionFactory{url->FakeConnection(url).also{con=it}})
        val bytes="{\"input\":\"hello\"}".toByteArray();val call=transport.start(config,bytes)
        try{assertArrayEquals(byteArrayOf(1,2,3,4),call.next());assertNull(call.next())}finally{call.cancel()}
        assertEquals("https://10.10.10.10:3900/v1/audio/speech",con.url.toString())
        assertEquals("Bearer test-private-key",con.getRequestProperty("Authorization"));assertEquals("123456",con.getRequestProperty("X-OmniVoice-Pin"))
        assertFalse(con.instanceFollowRedirects);assertFalse(con.useCaches);assertEquals("identity",con.getRequestProperty("Accept-Encoding"))
        assertTrue(con.closed.await(1,TimeUnit.SECONDS));assertEquals("{\"input\":\"hello\"}",con.sent.toString())
    }
    @Test fun probeIsGetAndContainsNoSpeechBody()=runBlocking {
        lateinit var con:FakeConnection
        val transport=StudioTransport(StudioConnectionFactory{url->FakeConnection(url,type="application/json",stream=ByteArrayInputStream("[]".toByteArray())).also{con=it}})
        val call=transport.start(config,null,true)
        try{assertNotNull(call.next());assertNull(call.next())}finally{call.cancel()}
        assertEquals("GET",con.requestMethod);assertEquals(0,con.sent.size());assertTrue(con.url.path.endsWith("/voices"))
    }
    @Test fun errorsAreRedactedAndWrongMimeNeverBecomesAudio()=runBlocking {
        for((code,type,expected) in listOf(Triple(401,"application/json",StudioFailure.AUTH),Triple(302,"audio/pcm",StudioFailure.SERVER),Triple(429,"application/json",StudioFailure.SERVER),Triple(200,"audio/wav",StudioFailure.FORMAT))){
            val transport=StudioTransport(StudioConnectionFactory{url->FakeConnection(url,code,type)})
            val call=transport.start(config,byteArrayOf(1))
            try{call.next();fail("Expected rejection")}catch(e:StudioVoiceException){assertEquals(expected,e.failure);assertFalse(e.toString().contains(config.apiKey));assertFalse(e.toString().contains(config.pin))}finally{call.cancel()}
        }
    }
    @Test fun firstByteDeadlineDoesNotWaitForBlockedSocket()=runBlocking {
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val stream=object:InputStream(){override fun read():Int{entered.countDown();released.await();return -1};override fun close(){released.countDown()}}
        val transport=StudioTransport(StudioConnectionFactory{url->FakeConnection(url,stream=stream)},firstByteLimitMs=40)
        val call=transport.start(config,byteArrayOf(1));val start=System.nanoTime()
        try{withTimeout(1000){call.next()};fail("Expected first-byte deadline")}catch(e:StudioVoiceException){assertEquals(StudioFailure.TIMEOUT,e.failure)}finally{call.cancel()}
        assertTrue((System.nanoTime()-start)/1_000_000<1000)
    }
    @Test fun cancellationStopsReaderAndAllowsASecondRequest()=runBlocking {
        val entered=CountDownLatch(1);val released=CountDownLatch(1);val useBlocked=AtomicBoolean(true)
        val stream=object:InputStream(){override fun read():Int{entered.countDown();released.await();return -1};override fun close(){released.countDown()}}
        val transport=StudioTransport(StudioConnectionFactory{url->FakeConnection(url,stream=if(useBlocked.getAndSet(false))stream else ByteArrayInputStream(byteArrayOf(1,2)))})
        val first=transport.start(config,byteArrayOf(1));assertTrue(entered.await(1,TimeUnit.SECONDS));first.cancel()
        try{first.next();fail("Expected cancellation")}catch(_:CancellationException){}
        var second:StudioExchange?=null
        withTimeout(1000){while(second==null){try{second=transport.start(config,byteArrayOf(1))}catch(e:StudioVoiceException){assertEquals(StudioFailure.BUSY,e.failure);delay(10)}}}
        try{assertArrayEquals(byteArrayOf(1,2),second!!.next());assertNull(second!!.next())}finally{second!!.cancel()}
    }
    @Test fun tlsGuardRejectsActualPublicOrLoopbackPeerBeforeDelegate(){
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        server.use{Socket("127.0.0.1",server.localPort).use{socket->
            try{PrivateStudioTls(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,"mac.local",443,true);fail("Expected private-peer rejection")}
            catch(e:StudioVoiceException){assertEquals(StudioFailure.ADDRESS,e.failure);assertTrue(socket.isClosed)}
        }}
    }
}
