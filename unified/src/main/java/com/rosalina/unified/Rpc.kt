package com.rosalina.unified

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal const val RPC_RUN=1
internal const val RPC_SHUTDOWN=99
internal const val RPC_EVENT=2

/** Non-exported, same-UID engines. Binding death is the authority for releasing an engine. */
internal class EngineRpc(private val context:Context,private val type:Class<out Service>) {
    private var connection:ServiceConnection?=null
    private var remote:Messenger?=null
    private var remoteDeath:CompletableDeferred<Unit>?=null
    private var deathRecipient:IBinder.DeathRecipient?=null
    private var shuttingDown=false
    @Volatile var pid=0; private set
    private val listeners=ConcurrentHashMap<String,Channel<Bundle>>()
    private val replies=Messenger(Handler(Looper.getMainLooper()){msg->
        if(msg.what==RPC_EVENT) {
            val b=msg.data
            val channel=listeners[b.getString("id")]
            // Late events from an already-cancelled request cannot change a successor's PID.
            if(channel!=null) {
                if(b.getInt("pid")>0)pid=b.getInt("pid")
                channel.trySend(b)
            }
        }
        true
    })

    private suspend fun bind():Messenger=withContext(Dispatchers.Main.immediate) {
        check(!shuttingDown){"The previous engine is still shutting down"}
        remote?.takeIf{it.binder.isBinderAlive}?.let{return@withContext it}
        connection?.let{runCatching{context.unbindService(it)}}
        connection=null; remote=null
        suspendCancellableCoroutine {continuation->
            val died=CompletableDeferred<Unit>()
            val c=object:ServiceConnection {
                override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                    if(!continuation.isActive || connection!==this) {
                        runCatching{context.unbindService(this)}
                        return
                    }
                    val m=Messenger(binder)
                    val recipient=IBinder.DeathRecipient{died.complete(Unit)}
                    try {binder.linkToDeath(recipient,0)}
                    catch(t:RemoteException){died.complete(Unit);continuation.resumeWith(Result.failure(t));return}
                    remote=m;remoteDeath=died;deathRecipient=recipient
                    continuation.resume(m){_,_,_->}
                }
                override fun onServiceDisconnected(name:ComponentName) {
                    died.complete(Unit)
                    if(connection===this) {
                        remote=null
                        listeners.values.forEach{it.close(IllegalStateException("${type.simpleName} process exited"))}
                    }
                }
                override fun onBindingDied(name:ComponentName)=onServiceDisconnected(name)
                override fun onNullBinding(name:ComponentName) {
                    died.complete(Unit)
                    if(continuation.isActive)continuation.resumeWith(Result.failure(IllegalStateException("Engine binding refused")))
                }
            }
            connection=c
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    runCatching{context.unbindService(c)}
                    if(connection===c){connection=null;remote=null}
                }
            }
            if(!context.bindService(Intent(context,type),c,Context.BIND_AUTO_CREATE)) {
                connection=null
                if(continuation.isActive)continuation.resumeWith(Result.failure(IllegalStateException("Android could not bind ${type.simpleName}")))
            }
        }
    }

    suspend fun call(values:Bundle,onEvent:suspend(Bundle)->Unit={}):Bundle {
        val m=withTimeout(15000){bind()}
        val id=UUID.randomUUID().toString()
        val channel=Channel<Bundle>(Channel.UNLIMITED)
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
        if(shuttingDown)throw WorkerQuarantined("Concurrent engine cleanup was rejected")
        shuttingDown=true
        val m=remote
        val died=remoteDeath
        val recipient=deathRecipient
        val oldPid=pid
        val oldConnection=connection
        remote=null;connection=null;remoteDeath=null;deathRecipient=null
        listeners.values.forEach{it.close(CancellationException("Engine stopped"))}
        listeners.clear()
        try {
            runCatching{m?.send(Message.obtain().apply{what=RPC_SHUTDOWN})}
            oldConnection?.let{runCatching{context.unbindService(it)}}
            runCatching{context.stopService(Intent(context,type))}
            if(m!=null && m.binder.isBinderAlive) {
                val graceful=died!=null && withTimeoutOrNull(750){died.await();true}==true
                if(!graceful && m.binder.isBinderAlive) {
                    withContext(Dispatchers.IO) {
                        val expected=context.packageName+if(type==ChatService::class.java)":chat" else ":speech"
                        val cmd=runCatching{File("/proc/$oldPid/cmdline").readText().substringBefore('\u0000')}.getOrDefault("")
                        if(oldPid>0 && cmd==expected)android.os.Process.killProcess(oldPid)
                    }
                    if(died!=null)withTimeoutOrNull(2000){died.await()}
                    if(m.binder.isBinderAlive)throw WorkerQuarantined("${type.simpleName} PID $oldPid did not stop. Force-stop Rosalina before loading another engine.")
                }
            }
            pid=0
        } finally {
            if(m!=null && recipient!=null)runCatching{m.binder.unlinkToDeath(recipient,0)}
            shuttingDown=false
        }
    }
}

abstract class NativeRpcService:Service() {
    protected val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val owner=AtomicReference<String?>(null)
    private val messenger=Messenger(Handler(Looper.getMainLooper()){msg->
        when(msg.what) {
            RPC_SHUTDOWN->{interrupt();scope.cancel();android.os.Process.killProcess(android.os.Process.myPid())}
            RPC_RUN->{
                val reply=msg.replyTo
                val values=Bundle(msg.data)
                val id=values.getString("id") ?: ""
                fun send(type:String,text:String="",extra:Bundle?=null) {
                    val b=Bundle(extra ?: Bundle()).apply{putString("id",id);putString("type",type);putString("text",text);putInt("pid",android.os.Process.myPid())}
                    runCatching{reply.send(Message.obtain().apply{what=RPC_EVENT;data=b})}
                }
                if(!owner.compareAndSet(null,id))send("error","Engine already has an active request") else {
                    send("stage","Preparing local engine")
                    scope.launch {
                        var result:Bundle?=null
                        var failure:Throwable?=null
                        try {result=execute(values){type,text,extra->send(type,text,extra)}}
                        catch(t:Throwable){failure=t}
                        finally {owner.compareAndSet(id,null)}
                        // All native work and its cleanup finish before DONE permits the next chunk.
                        if(failure==null)send("done",extra=result)
                        else if(failure !is CancellationException)send("error",failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }
        true
    })
    protected abstract suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle
    protected open fun interrupt() {}
    override fun onBind(intent:Intent?):IBinder=messenger.binder
    override fun onUnbind(intent:Intent?):Boolean {interrupt();scope.cancel();android.os.Process.killProcess(android.os.Process.myPid());return false}
    override fun onDestroy(){interrupt();scope.cancel();super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid())}
}
