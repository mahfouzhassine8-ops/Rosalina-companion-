package com.rosalina.unified

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

internal fun interface StudioConnectionFactory { fun open(url:URL):HttpURLConnection }
internal object StudioHttpsConnections:StudioConnectionFactory {
    private val factory=PrivateStudioTls(HttpsURLConnection.getDefaultSSLSocketFactory())
    override fun open(url:URL):HttpURLConnection {
        require(url.protocol=="https")
        return (url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply{
            sslSocketFactory=factory // Normal platform certificate and hostname verification remain enabled.
        }
    }
}
/** Validate the connected peer before HTTP sends credentials or response text. */
internal class PrivateStudioTls(private val delegate:SSLSocketFactory):SSLSocketFactory() {
    override fun getDefaultCipherSuites():Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites():Array<String> = delegate.supportedCipherSuites
    private fun checked(socket:Socket):Socket {
        if(!socket.isConnected || !StudioNetworkPolicy.isPrivate(socket.inetAddress)) {
            runCatching{socket.close()};throw StudioVoiceException(StudioFailure.ADDRESS)
        };return socket
    }
    override fun createSocket(socket:Socket,host:String,port:Int,autoClose:Boolean):Socket = delegate.createSocket(checked(socket),host,port,autoClose)
    override fun createSocket():Socket=throw IOException("An already connected, private-network TLS socket is required")
    private fun connect(host:String,port:Int,local:InetAddress?=null,localPort:Int=0):Socket {
        val socket=Socket()
        try{if(local!=null)socket.bind(InetSocketAddress(local,localPort));socket.connect(InetSocketAddress(host,port),2000)
            return delegate.createSocket(checked(socket),host,port,true)
        }catch(t:Throwable){runCatching{socket.close()};throw t}
    }
    override fun createSocket(host:String,port:Int)=connect(host,port)
    override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=connect(host,port,local,localPort)
    override fun createSocket(host:InetAddress,port:Int)=connect(host.hostAddress!!,port)
    override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=connect(host.hostAddress!!,port,local,localPort)
}

internal sealed class StudioPacket {
    class Bytes(val data:ByteArray):StudioPacket()
    object End:StudioPacket()
    class Failed(val reason:StudioFailure):StudioPacket()
}
internal class StudioExchange internal constructor(
    val startedMs:Long,private val clock:()->Long,private val firstByteLimitMs:Long,private val totalLimitMs:Long
) {
    internal val queue=ArrayBlockingQueue<StudioPacket>(4)
    val cancelled=AtomicBoolean(false)
    internal val connection=AtomicReference<HttpURLConnection?>(null)
    internal var worker:Thread?=null
    val setupMs=AtomicLong(-1)
    val lastByteMs=AtomicLong(startedMs)
    private var received=false
    companion object {
        private val closer=Executors.newSingleThreadExecutor{r->Thread(r,"Rosalina-Studio-close").apply{isDaemon=true}}
    }
    fun cancel() {
        if(cancelled.compareAndSet(false,true)){
            queue.clear();worker?.interrupt()
            connection.getAndSet(null)?.let{con->closer.execute{runCatching{con.disconnect()}}}
        }
    }
    internal fun send(packet:StudioPacket) {
        while(!cancelled.get()){if(queue.offer(packet,100,TimeUnit.MILLISECONDS))return}
    }
    suspend fun next():ByteArray? {
        while(true){
            currentCoroutineContext().ensureActive()
            if(cancelled.get())throw CancellationException("Studio request stopped")
            if(clock()-startedMs>totalLimitMs || (!received && clock()-startedMs>firstByteLimitMs))throw StudioVoiceException(StudioFailure.TIMEOUT)
            when(val packet=queue.poll()){
                is StudioPacket.Bytes->{received=true;return packet.data}
                is StudioPacket.Failed->throw StudioVoiceException(packet.reason)
                StudioPacket.End->return null
                null->delay(10)
            }
        }
    }
}

/** One network worker, bounded buffering, cancellable consumer; no automatic retries or redirects. */
internal class StudioTransport(
    private val factory:StudioConnectionFactory=StudioHttpsConnections,
    private val clock:()->Long={System.nanoTime()/1_000_000},
    private val firstByteLimitMs:Long=6000,private val totalLimitMs:Long=90000
) {
    private val occupied=AtomicBoolean(false)
    fun start(config:StudioConfig,body:ByteArray?,probe:Boolean=false):StudioExchange {
        if(!occupied.compareAndSet(false,true))throw StudioVoiceException(StudioFailure.BUSY)
        val exchange=StudioExchange(clock(),clock,firstByteLimitMs,if(probe)6000 else totalLimitMs)
        val thread=Thread({
            var connection:HttpURLConnection?=null
            try {
                if(exchange.cancelled.get())return@Thread
                val con=factory.open(URL(config.endpoint.root+if(probe)"/v1/audio/voices" else "/v1/audio/speech"));connection=con
                exchange.connection.set(con)
                if(exchange.cancelled.get())return@Thread
                con.connectTimeout=2000;con.readTimeout=2500;con.instanceFollowRedirects=false;con.useCaches=false
                con.requestMethod=if(probe)"GET" else "POST"
                con.setRequestProperty("Accept",if(probe)"application/json" else "audio/pcm")
                con.setRequestProperty("Accept-Encoding","identity");con.setRequestProperty("Cache-Control","no-store")
                if(config.apiKey.isNotBlank())con.setRequestProperty("Authorization","Bearer ${config.apiKey}")
                if(config.pin.isNotBlank())con.setRequestProperty("X-OmniVoice-Pin",config.pin)
                if(body!=null){con.doOutput=true;con.setRequestProperty("Content-Type","application/json");con.setFixedLengthStreamingMode(body.size);con.outputStream.use{it.write(body)}}
                val code=con.responseCode
                if(code!=200)throw StudioVoiceException(when(code){401,403->StudioFailure.AUTH;408,504->StudioFailure.TIMEOUT;else->StudioFailure.SERVER})
                val type=con.contentType.orEmpty().substringBefore(';').trim().lowercase()
                if(type != if(probe)"application/json" else "audio/pcm")throw StudioVoiceException(StudioFailure.FORMAT)
                exchange.setupMs.set(clock()-exchange.startedMs)
                val max=if(probe)131072L else 24_000L*2*60
                if(con.contentLengthLong>max)throw StudioVoiceException(StudioFailure.FORMAT)
                var total=0L
                con.inputStream.use{input->
                    val buffer=ByteArray(4096)
                    while(!exchange.cancelled.get()){
                        if(clock()-exchange.startedMs>totalLimitMs)throw StudioVoiceException(StudioFailure.TIMEOUT)
                        val count=input.read(buffer);if(count<0)break;if(count==0)continue
                        total+=count;if(total>max)throw StudioVoiceException(StudioFailure.FORMAT)
                        exchange.lastByteMs.set(clock());exchange.send(StudioPacket.Bytes(buffer.copyOf(count)))
                    }
                }
                if(total==0L)throw StudioVoiceException(StudioFailure.FORMAT)
                if(!exchange.cancelled.get())exchange.send(StudioPacket.End)
            } catch(t:Throwable){
                if(!exchange.cancelled.get())runCatching{exchange.send(StudioPacket.Failed(when(t){
                    is StudioVoiceException->t.failure
                    is SSLException->StudioFailure.TLS
                    is SocketTimeoutException->StudioFailure.TIMEOUT
                    else->StudioFailure.NETWORK
                }))}
            } finally {
                body?.fill(0)
                exchange.connection.compareAndSet(connection,null)
                runCatching{connection?.disconnect()};occupied.set(false)
            }
        },"Rosalina-Studio-network").apply{isDaemon=true}
        exchange.worker=thread
        try{thread.start()}catch(t:Throwable){occupied.set(false);throw t}
        return exchange
    }
}
