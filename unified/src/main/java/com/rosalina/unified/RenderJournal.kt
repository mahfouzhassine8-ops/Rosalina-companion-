package com.rosalina.unified

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private measurements of actual jobs, including failed jobs. Never an inferred phone benchmark. */
internal class RenderJournal(context: Context) {
    private val file = File(context.filesDir, "performance/recent-renders.json")
    private var current: JSONObject? = null
    private var started = 0L
    private var lastStage = ""
    private var lastStageAt = 0L
    private var phases = JSONArray()
    @Volatile var liveSummary = ""; private set
    @Synchronized fun begin(request: TaskRequest, resources: Resources) {
        started = SystemClock.elapsedRealtime(); lastStageAt = started; lastStage = "Preparing"; phases = JSONArray()
        val profile = if (request.kind == TaskKind.ANIMATE) "${request.width}x${request.height}/${request.seconds}s/12" else "${request.profile.width}x${request.profile.height}/${request.profile.steps}"
        val key = hex(MessageDigest.getInstance("SHA-256").digest("${request.kind}/$profile/${request.seed}/${request.prompt}/${request.photo}/${request.strength}".toByteArray()))
        current = JSONObject().put("id", request.id).put("matchKey", key).put("kind", request.kind.name).put("profile", profile)
            .put("requestedBackend", request.backend).put("device", Build.MODEL).put("android", Build.VERSION.SDK_INT)
            .put("initialThermal", resources.thermal).put("peakThermal", resources.thermal).put("minAvailableBytes", resources.available)
            .put("peakWorkerRssBytes", 0L).put("peakWorkerCpuPct", JSONObject.NULL).put("peakGpuBusyPct", JSONObject.NULL)
            .put("maxCpuCurrentKhz",0L).put("maxGpuCurrentHz",0L).put("createdAt", System.currentTimeMillis())
    }
    @Synchronized fun observe(stage: String, backend: String, res: Resources) {
        val c = current ?: return
        val now = SystemClock.elapsedRealtime()
        if (stage != lastStage) { phases.put(JSONObject().put("stage", lastStage).put("wallMs", now - lastStageAt)); lastStage = stage; lastStageAt = now }
        c.put("lastStage", stage).put("backend", backend).put("peakThermal", maxOf(c.optInt("peakThermal"), res.thermal))
            .put("minAvailableBytes", minOf(c.optLong("minAvailableBytes"), res.available))
            .put("peakWorkerRssBytes", maxOf(c.optLong("peakWorkerRssBytes"), res.rssBytes))
            .put("workBudget", res.control).put("headroom10s", if (res.headroom.isFinite()) res.headroom else JSONObject.NULL)
        if(res.workerCpuPct.isFinite())c.put("peakWorkerCpuPct",maxOf(c.optDouble("peakWorkerCpuPct",0.0),res.workerCpuPct.toDouble()))
        if(res.gpuBusyPct.isFinite())c.put("peakGpuBusyPct",maxOf(c.optDouble("peakGpuBusyPct",0.0),res.gpuBusyPct.toDouble()))
        c.put("maxCpuCurrentKhz",maxOf(c.optLong("maxCpuCurrentKhz"),res.cpuCurrentKhz)).put("maxGpuCurrentHz",maxOf(c.optLong("maxGpuCurrentHz"),res.gpuCurrentHz)).put("thermalZones",res.thermalZones.ifBlank{"unavailable"})
        liveSummary = "${res.control}\nWorker peak RSS: ${c.optLong("peakWorkerRssBytes") / 1_000_000} MB · stage: $stage"
    }
    @Synchronized fun finish(failure: Throwable?) {
        val c = current ?: return
        val now = SystemClock.elapsedRealtime()
        phases.put(JSONObject().put("stage", lastStage).put("wallMs", now - lastStageAt))
        c.put("elapsedMs", now - started).put("stages", phases).put("success", failure == null).put("error", failure?.message ?: "")
        val old = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (i in maxOf(0, old.length() - 19) until old.length()) updated.put(old.get(i))
        updated.put(c); atomicText(file, updated.toString(2)); current = null
    }
    @Synchronized fun describe(): String {
        val rows = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        return "Actual render records (wall time includes thermal pacing; RSS excludes unreported GPU memory):\n" + rows.toString(2) +
            "\nCompare only successful records with the same matchKey/model versions and comparable initial thermal state. A probe PASS is not Wan performance or compatibility acceptance.\n"
    }
}
