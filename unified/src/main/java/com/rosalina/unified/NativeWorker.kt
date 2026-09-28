package com.rosalina.unified

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class Resources(val available:Long,val total:Long,val low:Boolean,val thermal:Int,val measuredAt:Long,
    val headroom:Float=Float.NaN,val control:String="",val rssBytes:Long=0,
    val workerCpuPct:Float=Float.NaN,val cpuCurrentKhz:Long=0,val cpuMaxKhz:Long=0,
    val gpuBusyPct:Float=Float.NaN,val gpuCurrentHz:Long=0,val gpuMaxHz:Long=0,val thermalZones:String="")
internal class ThermalManager(context:Context) {
    private val power=context.getSystemService(PowerManager::class.java)
    private val memory=context.getSystemService(ActivityManager::class.java)
    private var headroomAt=Long.MIN_VALUE
    private var headroom=Float.NaN
    private var lastStatus=Int.MIN_VALUE
    private val history=ArrayDeque<String>()
    @Synchronized fun read():Resources {
        val now=SystemClock.elapsedRealtime()
        val info=ActivityManager.MemoryInfo();memory.getMemoryInfo(info)
        if(headroomAt==Long.MIN_VALUE || now-headroomAt>=10000) {
            headroom=runCatching{power.getThermalHeadroom(10)}.getOrDefault(Float.NaN);headroomAt=now
        }
        val status=runCatching{power.currentThermalStatus}.getOrDefault(-1)
        if(status!=lastStatus){history.addLast("$now:$status");while(history.size>12)history.removeFirst();lastStatus=status}
        return Resources(info.availMem,info.totalMem,info.lowMemory,status,now,headroom)
    }
    @Synchronized fun describe():String =
        "Thermal source: Android PowerManager.currentThermalStatus + getThermalHeadroom(10). Not connected to Samsung Good Guardians / Thermal Guardian. Status history(elapsedRealtime:status): "+history.joinToString(", ")
}
internal class WorkerQuarantined(message:String):IOException(message)

/** A log PID alone never authorizes a signal. Verify executable, parent, and start time first. */
internal class OwnedChild(private val process:Process,private val executable:String) {
    var pid=0;private set
    private var born=""
    var paused=false;private set
    private fun startTime(id:Int):String = File("/proc/$id/stat").readText().substringAfterLast(") ").split(' ').getOrElse(19){""}
    fun claim(id:Int) {
        if(pid!=0 || id<=0 || !process.isAlive)return
        runCatching {
            val cmd=File("/proc/$id/cmdline").readText().substringBefore('\u0000')
            val status=File("/proc/$id/status").readText()
            val parent=Regex("(?m)^PPid:\\s*(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull()
            if(cmd==executable && parent==android.os.Process.myPid()) {born=startTime(id);if(born.isNotBlank())pid=id}
        }
    }
    fun signal(signal:Int):Boolean {
        if(!owns())return false
        return runCatching{Os.kill(pid,signal);true}.getOrDefault(false)
    }
    fun kernelState():String = if(pid<=0)"unknown" else runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").firstOrNull()?.toString() ?: "unknown"
    }.getOrDefault("gone")
    private fun owns()=pid>0 && process.isAlive && runCatching{startTime(pid)==born}.getOrDefault(false)
    fun pause(value:Boolean) {
        if(value==paused)return
        if(!owns()){if(value && process.isAlive)error("Worker identity unavailable; safe workload pacing could not be enabled");return}
        try {Os.kill(pid,if(value)OsConstants.SIGSTOP else OsConstants.SIGCONT);paused=value}
        catch(t:Throwable){if(process.isAlive)throw IOException("Could not ${if(value)"pace" else "resume"} the owned worker",t)}
    }
    fun rss():Long=if(!owns())0 else runCatching{
        Regex("(?m)^VmRSS:\\s*(\\d+) kB").find(File("/proc/$pid/status").readText())?.groupValues?.get(1)?.toLong()?.times(1024) ?:0
    }.getOrDefault(0)
}

/** The spawning thread remains alive until reaping, preserving Linux parent-death semantics. */
internal class NativeWorker(private val thermal:ThermalManager) {
    suspend fun run(command:List<String>,directory:File,expectedSteps:Int,onState:(String,Int?,Int,Int,Int,String,Resources)->Unit):String {
        val owner=Executors.newSingleThreadExecutor{Thread(it,"Rosalina-native-owner")}.asCoroutineDispatcher()
        return try{withContext(owner){runOwned(command,directory,expectedSteps,onState)}}finally{owner.close()}
    }
    private suspend fun runOwned(command:List<String>,directory:File,expectedSteps:Int,onState:(String,Int?,Int,Int,Int,String,Resources)->Unit):String {
        require(command.isNotEmpty() && File(command[0]).canExecute()){"The packaged native engine is missing or not executable"}
        val log=File(directory,"native.log");val parser=ProgressParser(expectedSteps)
        val tail=StringBuilder();val pending=ByteArrayOutputStream();val job=currentCoroutineContext()
        var process:Process?=null;var child:OwnedChild?=null;var position=0L;var reportedPid=0
        val gpu=command[0].contains("-vulkan")
        val paced=command[0].contains("librosalina-") && command.getOrNull(1)!="--probe"
        fun consume(line:String) {
            if(line.startsWith("@@PID ")){reportedPid=line.substringAfter(' ').trim().toIntOrNull() ?:0;child?.claim(reportedPid)}
            parser.consume(line)
            if(tail.length>16000)tail.delete(0,tail.length-12000)
            tail.append(line.take(2000)).append('\n')
        }
        fun drain() {
            if(!log.exists())return
            RandomAccessFile(log,"r").use{input->
                input.seek(position);val bytes=ByteArray(8192);val end=input.length()
                while(input.filePointer<end) {
                    job.ensureActive();val n=input.read(bytes,0,minOf(bytes.size.toLong(),end-input.filePointer).toInt())
                    if(n<0)break
                    position+=n
                    for(i in 0 until n){val b=bytes[i].toInt() and 255;if(b==10){consume(pending.toString("UTF-8"));pending.reset()}else if(pending.size()<65536)pending.write(b)}
                }
            }
        }
        try {
            job.ensureActive()
            val initial=thermal.read()
            check(!ThermalPolicy.blocks(initial.thermal)){"Android reports ${ThermalPolicy.label(initial.thermal)} heat. Cool the phone before rendering."}
            val p=ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
            process=p
            val owned=OwnedChild(p,command[0]);child=owned
            val began=SystemClock.elapsedRealtime()
            var nextState=0L;var resources=initial;var resourceAt=0L;var rss=0L
            val hardware=HardwareTelemetry()
            while(true) {
                job.ensureActive();drain();val now=SystemClock.elapsedRealtime()
                if(now-resourceAt>=1000){
                    resources=thermal.read();rss=owned.rss()
                    val hw=hardware.read(owned.pid.takeIf{it>0} ?:reportedPid)
                    resources=resources.copy(workerCpuPct=hw.workerCpuPct,cpuCurrentKhz=hw.cpuCurrentKhz,cpuMaxKhz=hw.cpuMaxKhz,gpuBusyPct=hw.gpuBusyPct,gpuCurrentHz=hw.gpuCurrentHz,gpuMaxHz=hw.gpuMaxHz,thermalZones=hw.thermalZones)
                    resourceAt=now
                }
                val percent=if(paced)WorkBudget.percent(resources.thermal,gpu,resources.headroom,parser.stage)else 100
                if(now>=nextState || !p.isAlive) {
                    val hw=HardwareSample(resources.workerCpuPct,resources.cpuCurrentKhz,resources.cpuMaxKhz,resources.gpuBusyPct,resources.gpuCurrentHz,resources.gpuMaxHz,resources.thermalZones).short()
                    val control=if(paced)"${if(gpu)"GPU" else "CPU"} work budget $percent% · ${if(owned.paused)"cooling interval" else "running"} · $hw" else "Backend compute check · $hw"
                    onState(parser.stage,parser.percent,parser.step,parser.total,owned.pid.takeIf{it>0} ?:reportedPid,tail.toString(),resources.copy(control=control,rssBytes=rss))
                    nextState=now+250
                }
                check(!ThermalPolicy.blocks(resources.thermal)){"Stopped safely: Android reported ${ThermalPolicy.label(resources.thermal)} heat during ${parser.stage}"}
                if(!p.isAlive)break
                if(paced && (owned.pid>0 || now-began>3000))owned.pause(WorkBudget.shouldPause(now-began,percent))
                delay(50)
            }
            drain();if(pending.size()>0)consume(pending.toString("UTF-8"))
            onState(parser.stage,parser.percent,parser.step,parser.total,owned.pid.takeIf{it>0} ?:reportedPid,tail.toString(),thermal.read().copy(rssBytes=rss))
            val code=p.waitFor();check(code==0){"Native engine exited with code $code. See Copy diagnostics."}
            return tail.toString()
        } finally {
            withContext(NonCancellable) {
                process?.let{p->
                    val owned=child
                    runCatching{owned?.pause(false)}
                    if(p.isAlive){
                        owned?.signal(OsConstants.SIGCONT)
                        owned?.signal(OsConstants.SIGTERM)
                        p.destroy()
                        p.waitFor(1200,TimeUnit.MILLISECONDS)
                    }
                    if(p.isAlive){
                        owned?.signal(OsConstants.SIGCONT)
                        owned?.signal(OsConstants.SIGKILL)
                        p.destroyForcibly()
                        p.waitFor(8000,TimeUnit.MILLISECONDS)
                    }
                    if(p.isAlive){
                        val id=owned?.pid ?: reportedPid
                        val state=owned?.kernelState() ?: "unknown"
                        throw WorkerQuarantined("Native worker "+id+" could not be reaped; kernel state="+state+". Force-stop Rosalina before another task.")
                    }
                    runCatching{p.inputStream.close()};runCatching{p.errorStream.close()};runCatching{p.outputStream.close()}
                }
            }
        }
    }
}
