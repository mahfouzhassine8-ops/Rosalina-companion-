package com.rosalina.unified

import java.io.File
import kotlin.math.roundToInt

internal data class HardwareSample(
    val workerCpuPct:Float=Float.NaN,val cpuCurrentKhz:Long=0,val cpuMaxKhz:Long=0,
    val gpuBusyPct:Float=Float.NaN,val gpuCurrentHz:Long=0,val gpuMaxHz:Long=0,val thermalZones:String=""
) {
    fun short():String {
        val parts=mutableListOf<String>()
        if(workerCpuPct.isFinite())parts+="worker CPU ${workerCpuPct.roundToInt()}% total-capacity"
        if(cpuCurrentKhz>0)parts+="CPU ${"%.2f".format(cpuCurrentKhz/1e6)} GHz"+if(cpuMaxKhz>0)"/${"%.2f".format(cpuMaxKhz/1e6)}" else ""
        if(gpuBusyPct.isFinite())parts+="GPU busy ${gpuBusyPct.roundToInt()}%"
        if(gpuCurrentHz>0)parts+="GPU ${gpuCurrentHz/1_000_000} MHz"+if(gpuMaxHz>0)"/${gpuMaxHz/1_000_000}" else ""
        return parts.joinToString(" · ").ifBlank{"hardware counters unavailable to app"}
    }
}
internal class HardwareTelemetry {
    private var previousPid=0
    private var previousProcessTicks:Long?=null;private var previousTotalTicks:Long?=null
    private fun readLong(path:String)=runCatching{File(path).readText().trim().split(Regex("\\s+")).first().toLong()}.getOrDefault(0L)
    private fun cpuFreq(name:String):Long {
        val values=File("/sys/devices/system/cpu").listFiles().orEmpty().filter{it.name.matches(Regex("cpu\\d+"))}.mapNotNull{val v=readLong(File(it,"cpufreq/$name").path);v.takeIf{x->x>0}}
        return if(values.isEmpty())0 else values.average().toLong()
    }
    private fun processTicks(pid:Int):Long?=runCatching{val p=File("/proc/$pid/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"));p.getOrNull(11)!!.toLong()+p.getOrNull(12)!!.toLong()}.getOrNull()
    private fun totalTicks():Long?=runCatching{File("/proc/stat").bufferedReader().use{it.readLine()}.split(Regex("\\s+")).drop(1).mapNotNull{it.toLongOrNull()}.sum()}.getOrNull()
    private fun cpuPercent(pid:Int):Float {
        if(pid<=0)return Float.NaN
        if(pid!=previousPid){previousPid=pid;previousProcessTicks=null;previousTotalTicks=null}
        val p=processTicks(pid);val t=totalTicks();
        if(p==null || t==null){previousProcessTicks=null;previousTotalTicks=null;return Float.NaN}
        val pp=previousProcessTicks;val pt=previousTotalTicks
        previousProcessTicks=p;previousTotalTicks=t
        if(pp==null||pt==null||t<=pt||p<pp)return Float.NaN
        return ((p-pp).toDouble()/(t-pt).toDouble()*100.0).toFloat().coerceIn(0f,100f)
    }
    private fun gpuBusy():Float {
        val percent=runCatching{File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim().substringBefore(' ').toFloat()}.getOrNull()
        if(percent!=null)return percent.coerceIn(0f,100f)
        return runCatching{val p=File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+"));val busy=p.getOrNull(0)?.toDoubleOrNull()?:return@runCatching Float.NaN;val total=p.getOrNull(1)?.toDoubleOrNull()?:return@runCatching Float.NaN;if(total<=0)Float.NaN else (busy/total*100.0).toFloat().coerceIn(0f,100f)}.getOrDefault(Float.NaN)
    }
    private fun gpuFreq(vararg names:String):Long {for(name in names){val v=readLong("/sys/class/kgsl/kgsl-3d0/$name");if(v>0)return v};return 0}
    private fun zones():String=runCatching{
        File("/sys/class/thermal").listFiles().orEmpty().filter{it.name.startsWith("thermal_zone")}.mapNotNull{z->
            val type=runCatching{File(z,"type").readText().trim()}.getOrNull()?:return@mapNotNull null
            if(!Regex("cpu|gpu|soc|skin|shell|battery|quiet|modem",RegexOption.IGNORE_CASE).containsMatchIn(type))return@mapNotNull null
            val raw=readLong(File(z,"temp").path);if(raw==0L)return@mapNotNull null
            val c=if(raw>1000)raw/1000.0 else raw.toDouble()
            "$type=${"%.1f".format(c)}C"
        }.take(10).joinToString(", ")
    }.getOrDefault("")
    fun read(pid:Int)=HardwareSample(cpuPercent(pid),cpuFreq("scaling_cur_freq"),cpuFreq("cpuinfo_max_freq"),gpuBusy(),gpuFreq("devfreq/cur_freq","gpuclk"),gpuFreq("devfreq/max_freq","max_gpuclk"),zones())
}
