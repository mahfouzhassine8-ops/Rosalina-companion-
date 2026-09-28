package com.rosalina.unified

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Private, bounded crash evidence. Never suppresses Android's fatal exception handler. */
internal object CrashJournal {
    private val installed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private fun folder(context: Context) = File(context.filesDir, "failure-journal")

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (recording.compareAndSet(false, true)) {
                    val process = Application.getProcessName()
                    val suffix = process.substringAfter(':', "main").replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    runCatching {
                        atomicText(File(folder(context), "fatal-$suffix.txt"),
                            "Rosalina ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                                "time=${System.currentTimeMillis()} process=$process pid=${Process.myPid()} thread=${thread.name}\n" +
                                error.stackTraceToString().take(24_000))
                    }
                }
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(2) }
            }
        }
    }

    fun recordTaskFailure(context: Context, task: String, error: Throwable) {
        runCatching {
            val suffix=Application.getProcessName().substringAfter(':', "main").replace(Regex("[^a-zA-Z0-9_-]"), "_")
            atomicText(File(folder(context), "last-task-$suffix.txt"),
                "time=${System.currentTimeMillis()} task=$task\n${error.stackTraceToString().take(24_000)}")
        }
    }

    /** Called only on a diagnostics IO dispatcher, never on a rendering/streaming path. */
    fun describe(context: Context): String = buildString {
        appendLine("PRIVATE FAILURE JOURNAL (not uploaded)")
        val records = folder(context).listFiles()?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }?.take(5).orEmpty()
        if (records.isEmpty()) appendLine("No captured failures in this build")
        for (file in records) {
            appendLine(file.name)
            appendLine(runCatching { file.reader().use { it.readText().take(24_000) } }.getOrDefault("Unreadable record"))
        }
        appendLine("Android recent exit records (historical; not necessarily a current failure)")
        runCatching {
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exits.isEmpty()) appendLine("No exit records available")
            for (exit in exits) {
                appendLine("time=${exit.timestamp} process=${exit.processName} pid=${exit.pid} reason=${exit.reason} status=${exit.status} pss=${exit.pss}KiB rss=${exit.rss}KiB")
                appendLine(exit.description.orEmpty().take(1000))
                // ANR/native trace availability is controlled by Android; absence is not proof of no crash.
                if (exit.reason == 5) runCatching { exit.traceInputStream?.use { appendLine("Native tombstone available (protobuf); not decoded in this text report") } }
                if (exit.reason == 6) {
                    runCatching { exit.traceInputStream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(12_000); val n = reader.read(buffer)
                        if (n > 0) appendLine(String(buffer, 0, n))
                    } }
                }
            }
        }.onFailure { appendLine("Exit history unavailable: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
