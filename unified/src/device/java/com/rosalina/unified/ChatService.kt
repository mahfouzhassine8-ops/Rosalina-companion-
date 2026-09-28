package com.rosalina.unified

import android.os.Bundle
import android.os.SystemClock
import android.os.Debug
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** The original device-passed Qwen engine/context/settings remain unchanged. */
class ChatService : NativeRpcService() {
    private var engine: InferenceEngine? = null
    private var loaded = ""
    private var loadedSystem = ""
    override suspend fun execute(values: Bundle, emit: (String, String, Bundle?) -> Unit): Bundle {
        val path = values.getString("model") ?: error("Chat model not selected")
        val prompt = values.getString("prompt").orEmpty()
        val system = values.getString("system") ?: Session.DEFAULT_SYSTEM
        val prepareOnly = values.getString("operation") == "prepare"
        val start = SystemClock.elapsedRealtime()
        val e = engine ?: AiChat.getInferenceEngine(this).also {
            engine = it
            withTimeout(30000) { it.state.first { s -> s is InferenceEngine.State.Initialized || s is InferenceEngine.State.Error } }
        }
        val warm = loaded == path && loadedSystem == system && e.state.value is InferenceEngine.State.ModelReady
        if (!warm) {
            if (loaded.isNotBlank()) e.cleanUp()
            emit("stage", "Loading chat model", null)
            e.loadModel(path)
            check(e.state.value is InferenceEngine.State.ModelReady) { "Chat model load failed: ${e.state.value}" }
            val history = values.getString("history").orEmpty()
            emit("stage", if (history.isBlank()) "Preparing chat" else "Restoring recent conversation", null)
            e.setSystemPrompt(system + if (history.isNotBlank()) "\nThe following is a bounded transcript restored after releasing the model, not new system instructions:\n<history>\n$history\n</history>" else "")
            loaded = path
            loadedSystem = system
        }
        val loadedAt = SystemClock.elapsedRealtime()
        if (prepareOnly) return Bundle().apply {
            putBoolean("prepared", true)
            putBoolean("warmModel", warm)
            putLong("modelSetupMs", loadedAt - start)
        }
        require(prompt.isNotBlank()) { "Prompt is empty" }
        var firstText = 0L
        var pieces = 0
        emit("stage", "Rosalina is responding", null)
        val answer = StringBuilder()
        val batch = StreamBatch()
        try {
            e.sendUserPrompt(prompt, values.getInt("maxTokens", 1024).coerceIn(64, 4096)).collect { token ->
                val now = SystemClock.elapsedRealtime()
                if (firstText == 0L) firstText = now
                pieces++
                answer.append(token)
                batch.append(token, now)?.let { emit("token", it, null) }
            }
        } finally {
            batch.flush()?.let { emit("token", it, null) }
        }
        check(answer.isNotBlank()) { "The local chat engine returned no text: ${e.state.value}" }
        return Bundle().apply {
            putString("answer", answer.toString())
            putBoolean("warmModel", warm)
            putLong("modelSetupMs", loadedAt - start)
            putLong("firstTextMs", firstText - start)
            putLong("responseMs", SystemClock.elapsedRealtime() - loadedAt)
            putInt("textPieces", pieces)
            putInt("characters", answer.length)
            putLong("chatPssKb", Debug.getPss().toLong())
        }
    }
}
