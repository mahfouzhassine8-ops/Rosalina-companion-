package com.rosalina.unified

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val RPC_RUN=1
internal const val RPC_SHUTDOWN=99
internal const val RPC_EVENT=2
/** All services are non-exported and same-UID. Requests/events carry a unique call token. */
internal class EngineRpc(private val context:Context,private val type:Class<out Service>) {
    private var connection:ServiceConnection?=null
    private var remote:Messenger?=null
    @Volatile var pid=0;private set
    private val listeners=ConcurrentHashMap<String,Channel<Bundle>>()
    private val replies=Messenger(Handler(Looper.getMainLooper()){msg->
        if(msg.what==RPC_EVENT){val b=msg.data;if(b.getInt("pid")>0)pid=b.getInt("pid");listeners[b.getString("id")]?.trySend(b)};true
    })
    private suspend fun bind():Messenger=withContext(Dispatchers.Main.immediate) {
        remote?.let{return@withContext it}
        suspendCancellableCoroutine {continuation->
            val c=object:ServiceConnection {
                override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                    val m=Messenger(binder);remote=m
                    if(continuation.isActive)continuation.resume(m){_,_,_->} else {runCatching{context.unbindService(this)};remote=null;connection=null}
                }
                override fun onServiceDisconnected(name:ComponentName) {remote=null;listeners.values.forEach{it.close(IllegalStateException("${type.simpleName} process exited"))}}
                override fun onBindingDied(name:ComponentName)=onServiceDisconnected(name)
                override fun onNullBinding(name:ComponentName) {if(continuation.isActive)continuation.resumeWith(Result.failure(IllegalStateException("Engine binding refused")))}
            }
            connection=c
            continuation.invokeOnCancellation{Handler(Looper.getMainLooper()).post{runCatching{context.unbindService(c)};if(connection===c){connection=null;remote=null}}}
            if(!context.bindService(Intent(context,type),c,Context.BIND_AUTO_CREATE)) {
                connection=null
                if(continuation.isActive)continuation.resumeWith(Result.failure(IllegalStateException("Android could not bind ${type.simpleName}")))
            }
        }
    }
    suspend fun call(values:Bundle,onEvent:suspend(Bundle)->Unit={}):Bundle {
        val m=withTimeout(15000){bind()};val id=UUID.randomUUID().toString();val channel=Channel<Bundle>(Channel.UNLIMITED)
        listeners[id]=channel
        try {
            m.send(Message.obtain().apply{what=RPC_RUN;data=Bundle(values).apply{putString("id",id)};replyTo=replies})
            for(event in channel) {
                when(event.getString("type")) {
                    "error"->error(event.getString("text") ?: "Native engine failed")
                    "done"->{onEvent(event);return event}
                    else->onEvent(event)
                }
            }
            error("Engine disconnected without completing its request")
        } finally {listeners.remove(id);channel.cancel()}
    }
    suspend fun shutdown()=withContext(NonCancellable+Dispatchers.Main.immediate) {
        runCatching{remote?.send(Message.obtain().apply{what=RPC_SHUTDOWN})}
        connection?.let{runCatching{context.unbindService(it)}};connection=null;remote=null
        context.stopService(Intent(context,type))
        listeners.values.forEach{it.close(CancellationException("Engine stopped"))};listeners.clear()
        val oldPid=pid;pid=0
        withContext(Dispatchers.IO) {
            repeat(20){if(oldPid<=0 || !File("/proc/$oldPid").exists())return@withContext;delay(100)}
            if(oldPid>0) {
                val cmd=runCatching{File("/proc/$oldPid/cmdline").readText().substringBefore('\u0000')}.getOrDefault("")
                if(cmd==context.packageName+":chat" || cmd==context.packageName+":speech")android.os.Process.killProcess(oldPid)
            }
        }
    }
}
abstract class NativeRpcService:Service() {
    protected val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var active:Job?=null
    private val messenger=Messenger(Handler(Looper.getMainLooper()){msg->
        when(msg.what) {
            RPC_SHUTDOWN->{interrupt();scope.cancel();android.os.Process.killProcess(android.os.Process.myPid())}
            RPC_RUN->{
                val reply=msg.replyTo;val values=Bundle(msg.data);val id=values.getString("id") ?: ""
                fun send(type:String,text:String="",extra:Bundle?=null) {
                    val b=Bundle(extra ?: Bundle()).apply{putString("id",id);putString("type",type);putString("text",text);putInt("pid",android.os.Process.myPid())}
                    runCatching{reply.send(Message.obtain().apply{what=RPC_EVENT;data=b})}
                }
                if(active?.isActive==true)send("error","Engine already has an active request") else {
                    send("stage","Preparing local engine")
                    active=scope.launch {
                        try { val result=execute(values){type,text,extra->send(type,text,extra)};send("done",extra=result) }
                        catch(e:CancellationException){throw e}
                        catch(t:Throwable){send("error",t.message ?: t.javaClass.simpleName)}
                    }
                }
            }
        };true
    })
    protected abstract suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle
    protected open fun interrupt() {}
    override fun onBind(intent:Intent?):IBinder=messenger.binder
    override fun onUnbind(intent:Intent?):Boolean {interrupt();scope.cancel();android.os.Process.killProcess(android.os.Process.myPid());return false}
    override fun onDestroy(){interrupt();scope.cancel();super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid())}
}
