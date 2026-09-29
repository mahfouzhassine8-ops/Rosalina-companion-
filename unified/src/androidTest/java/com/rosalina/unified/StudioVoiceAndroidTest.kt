package com.rosalina.unified

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StudioVoiceAndroidTest {
    private var activity:ActivityScenario<MainActivity>?=null
    private var oldVolume=0
    @Before fun foregroundFixture(){
        activity=ActivityScenario.launch(MainActivity::class.java)
        val audio=context.getSystemService(AudioManager::class.java)
        oldVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Disposable emulator only. Production never raises the user's system volume.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,maxOf(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2),0)
    }
    @After fun closeFixture(){
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC,oldVolume,0)
        activity?.close()
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun settings(model:String="fixture-ok"):StudioVoiceSettings=StudioVoiceSettings(context).also{
        it.clear();it.save("https://10.0.2.2:8443",model,"default",StudioStyleMode.NONE,"test-studio-key","123456",true,false)
    }
    @Test fun defaultOffVaultEncryptedAndBoundToOrigin(){
        val s=StudioVoiceSettings(context);s.clear();assertFalse(s.enabled());assertFalse(s.preferred())
        s.save("https://10.0.2.2:8443","tts-1","default",StudioStyleMode.NONE,"test-studio-key","123456",true,true)
        assertTrue(s.enabled());assertTrue(s.preferred())
        val all=context.getSharedPreferences("studio-voice-vault",Context.MODE_PRIVATE).all.toString()
        assertFalse(all.contains("test-studio-key"));assertFalse(all.contains("123456"))
        try{s.save("https://10.0.2.3:8443","tts-1","default",StudioStyleMode.NONE,"","",true,true);fail("Credentials must not migrate to another origin")}catch(_:IllegalArgumentException){}
        assertEquals("https://10.0.2.2:8443",s.snapshot().endpoint.root)
        s.disable();assertFalse(s.enabled());s.clear()
    }
    @Test fun requestContainsOnlyTheReplyAndAllowedPerformanceFields(){
        val s=settings();try{
            val json=JSONObject(StudioSpeechRequest.encode("Fixed test reply.",s.snapshot(),PerformanceState()).toString(Charsets.UTF_8))
            assertEquals(setOf("input","model","voice","response_format","stream_format","speed"),json.keys().asSequence().toSet())
            assertEquals("pcm",json.getString("response_format"));assertEquals("audio",json.getString("stream_format"))
            assertFalse(json.toString().contains("test-studio-key"));assertFalse(json.has("messages"));assertFalse(json.has("audio"))
            val upper=JSONObject(StudioSpeechRequest.encode("Fixed test.",s.snapshot(),PerformanceState(pace=10f)).toString(Charsets.UTF_8))
            assertTrue(upper.getDouble("speed")<=1.2)
        }finally{s.clear()}
    }
    private fun transport(stream:InputStream):StudioTransport=StudioTransport(StudioConnectionFactory{url->object:HttpURLConnection(url){
        override fun connect(){};override fun usingProxy()=false;override fun disconnect(){stream.close()}
        override fun getResponseCode()=200;override fun getContentType()="audio/pcm";override fun getContentLengthLong()=-1L
        override fun getInputStream()=stream;override fun getOutputStream():OutputStream=ByteArrayOutputStream()
    }},clock={SystemClock.elapsedRealtime()})
    private fun tone(frames:Int=24000)=ByteArray(frames*2).also{bytes->for(i in 0 until frames){val sample=(sin(i*2*Math.PI*440/24000)*6000).toInt();bytes[i*2]=sample.toByte();bytes[i*2+1]=(sample shr 8).toByte()}}
    @Test fun actualAudioTrackStartsBeforeTheFullResponseArrives()=runBlocking {
        val s=settings();val playback=CountDownLatch(1);val eof=AtomicBoolean(false);val samples=tone()
        val stream=object:InputStream(){var offset=0
            override fun read():Int{val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
            override fun read(dst:ByteArray,off:Int,len:Int):Int{
                if(offset>=12000 && !playback.await(4,TimeUnit.SECONDS))throw IOException("Playback never began before EOF")
                if(offset>=samples.size){eof.set(true);return -1}
                val n=minOf(len,samples.size-offset,if(offset<12000)12000-offset else len)
                samples.copyInto(dst,off,offset,offset+n);offset+=n;return n
            }
            override fun close(){playback.countDown()}
        }
        val voice=StudioVoice(context,s,transport(stream));val startedBeforeEnd=AtomicBoolean(false)
        try{val result=withTimeout(8000){voice.speak("Fixed tone fixture.",PerformanceState()){kind,label,_->if(kind=="playback" && label=="start"){startedBeforeEnd.set(!eof.get());playback.countDown()}}}
            assertTrue(startedBeforeEnd.get());assertTrue(result.getLong("playedFrames")>0);assertTrue(result.getLong("firstAudioMs")>=0)
        }finally{voice.stop();s.clear()}
    }
    @Test fun stopCancelsWaitingStreamAndNextUtteranceWorks()=runBlocking {
        val s=settings();val gate=CountDownLatch(1);val waiting=CountDownLatch(1)
        val stream=object:InputStream(){override fun read():Int{waiting.countDown();gate.await();return -1};override fun close(){gate.countDown()}}
        val voice=StudioVoice(context,s,transport(stream))
        try{
            val work=async(Dispatchers.Default){voice.speak("Fixed test.",PerformanceState()){_,_,_->}}
            assertTrue(waiting.await(2,TimeUnit.SECONDS));val now=SystemClock.elapsedRealtime();voice.stop()
            try{withTimeout(1000){work.await()};fail("Expected Stop cancellation")}catch(_:CancellationException){}
            assertTrue(SystemClock.elapsedRealtime()-now<1000)
            val next=StudioVoice(context,s,transport(ByteArrayInputStream(tone(12000))))
            try{assertTrue(withTimeout(6000){next.speak("Next fixed test.",PerformanceState()){_,_,_->}}.getLong("playedFrames")>0)}finally{next.stop()}
        }finally{voice.stop();s.clear()}
    }
    private fun fixtureTls():StudioTransport {
        val certificate=InstrumentationRegistry.getInstrumentation().context.assets.open("studio-fixture-ca.pem").use{CertificateFactory.getInstance("X.509").generateCertificate(it)}
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setCertificateEntry("ephemeral-fixture",certificate)}
        val managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(store)}
        val tls=SSLContext.getInstance("TLS").apply{init(null,managers.trustManagers,null)}
        return StudioTransport(StudioConnectionFactory{url->(url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply{sslSocketFactory=PrivateStudioTls(tls.socketFactory)}},clock={SystemClock.elapsedRealtime()})
    }
    @Test fun realPrivateHttpsAndChunkedPcmContract()=runBlocking {
        val s=settings();val voice=StudioVoice(context,s,fixtureTls())
        try{
            assertTrue(withTimeout(8000){voice.probe()}.contains("reachable"))
            val result=withTimeout(10000){voice.speak("Fixed TLS contract test.",PerformanceState()){_,_,_->}}
            assertTrue(result.getLong("playedFrames")>0)
        }finally{voice.stop();s.clear()}
    }
    @Test fun wrongTlsHostnameIsNotAccepted()=runBlocking {
        val s=settings();s.save("https://10.0.2.2:8444","fixture-ok","default",StudioStyleMode.NONE,"test-studio-key","123456",true,false)
        val voice=StudioVoice(context,s,fixtureTls())
        try{withTimeout(8000){voice.probe()};fail("TLS hostname mismatch must fail")}
        catch(e:StudioVoiceException){assertEquals(StudioFailure.TLS,e.failure)}finally{voice.stop();s.clear()}
    }
}
