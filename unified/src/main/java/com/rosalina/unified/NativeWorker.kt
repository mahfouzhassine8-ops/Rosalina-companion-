package com.rosalina.unified

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.TimeUnit

internal data class Resources(val available:Long,val total:Long,val low:Boolean,val thermal:Int,val measuredAt:Long)
internal class ThermalManager(context:Context) {
    private val power=context.getSystemService(PowerManager::class.java)
    private val memory=context.getSystemService(ActivityManager::class.java)
    fun read():Resources {
        val info=ActivityManager.MemoryInfo();memory.getMemoryInfo(info)
        return Resources(info.availMem,info.totalMem,info.lowMemory,runCatching{power.currentThermalStatus}.getOrDefault(-1),SystemClock.elapsedRealtime())
    }
}
internal class WorkerQuarantined(message:String):IOException(message)
/** One coroutine owns process creation, file-tail reads, termination, and reap. No pipe readers. */
internal class NativeWorker(private val thermal:ThermalManager) {
    suspend fun run(command:List<String>,directory:File,expectedSteps:Int,onState:(String,Int?,Int,Int,Int,String,Resources)->Unit):String = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && File(command[0]).canExecute()) {"The packaged native engine is missing or not executable"}
        val log=File(directory,"native.log")
        val parser=ProgressParser(expectedSteps);val tail=StringBuilder();var pid=0
        var process:Process?=null;var position=0L
        val pending=ByteArrayOutputStream()
        fun consume(line:String) {
            if(line.startsWith("@@PID "))pid=line.removePrefix("@@PID ").trim().toIntOrNull() ?: pid
            parser.consume(line)
            if(tail.length>16000)tail.delete(0,tail.length-12000)
            tail.append(line.take(2000)).append('\n')
        }
        fun drain() {
            if(!log.exists())return
            RandomAccessFile(log,"r").use{input->
                input.seek(position);val bytes=ByteArray(8192)
                while(true){val n=input.read(bytes);if(n<0)break;position+=n
                    for(i in 0 until n){val b=bytes[i].toInt() and 255
                        if(b==10){consume(pending.toString("UTF-8"));pending.reset()}else if(pending.size()<65536)pending.write(b)
                    }
                }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val start=thermal.read();check(!ThermalPolicy.blocks(start.thermal)){"Android thermal status is ${ThermalPolicy.label(start.thermal)}. Generation has not started."}
            process=ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
            val p=process
            while(true) {
                currentCoroutineContext().ensureActive();drain()
                val resources=thermal.read()
                onState(parser.stage,parser.percent,parser.step,parser.total,pid,tail.toString(),resources)
                check(!ThermalPolicy.blocks(resources.thermal)){"Stopped safely: Android reported ${ThermalPolicy.label(resources.thermal)} heat"}
                if(!p.isAlive)break
                delay(200)
            }
            drain();if(pending.size()>0)consume(pending.toString("UTF-8"))
            onState(parser.stage,parser.percent,parser.step,parser.total,pid,tail.toString(),thermal.read())
            val code=p.waitFor()
            check(code==0){"Native engine exited with code $code. See Copy diagnostics for its log."}
            tail.toString()
        } finally {
            withContext(NonCancellable+Dispatchers.IO) {
                process?.let{p->
                    if(p.isAlive){p.destroy();p.waitFor(350,TimeUnit.MILLISECONDS)}
                    if(p.isAlive){p.destroyForcibly();p.waitFor(1800,TimeUnit.MILLISECONDS)}
                    // Process.destroyForcibly targets this Process object's PID, not an untrusted log PID.
                    if(p.isAlive)throw WorkerQuarantined("Native worker $pid did not acknowledge termination. New work is blocked; force-stop Rosalina before retrying.")
                    runCatching{p.inputStream.close()};runCatching{p.errorStream.close()};runCatching{p.outputStream.close()}
                }
            }
        }
    }
}
