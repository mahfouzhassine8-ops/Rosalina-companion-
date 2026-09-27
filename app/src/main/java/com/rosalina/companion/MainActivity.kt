package com.rosalina.localai

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var engine: InferenceEngine
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var inputView: EditText
    private lateinit var sendButton: Button
    private lateinit var modelButton: Button
    private var modelReady = false
    private var generationJob: Job? = null
    private val prefs by lazy { getSharedPreferences("rosalina_local_ai", Context.MODE_PRIVATE) }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreTranscript()
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            engine.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
            val savedPath = prefs.getString(KEY_MODEL_PATH, null)
            if (!savedPath.isNullOrBlank() && File(savedPath).isFile) {
                try { loadModel(File(savedPath)) }
                catch (t: Throwable) { setStatus("Model needs reload: ${t.message}") }
            } else {
                setStatus("Ready. Import a GGUF model.")
            }
        }
    }

    private fun buildUi() {
        val bg = Color.rgb(9, 7, 13)
        val panel = Color.rgb(22, 18, 31)
        val textColor = Color.rgb(242, 238, 250)
        val muted = Color.rgb(175, 164, 193)

        val title = TextView(this).apply {
            text = "ROSALINA • LOCAL AI"
            textSize = 23f
            setTextColor(textColor)
            setPadding(dp(18), dp(18), dp(18), dp(4))
        }
        statusView = TextView(this).apply {
            text = "Starting local inference engine…"
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(18), dp(2), dp(18), dp(14))
        }
        modelButton = Button(this).apply {
            text = "Import GGUF"
            setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        }
        val settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                transcriptView.text = ""
                saveTranscript()
            }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
            addView(modelButton, LinearLayout.LayoutParams(0, dp(48), 1.2f))
            addView(settingsButton, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(clearButton, LinearLayout.LayoutParams(0, dp(48), 0.8f))
        }
        transcriptView = TextView(this).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(14), dp(18), dp(22))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(panel)
            addView(transcriptView)
        }
        inputView = EditText(this).apply {
            hint = "Message Rosalina…"
            setHintTextColor(muted)
            setTextColor(textColor)
            maxLines = 6
            minLines = 1
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        sendButton = Button(this).apply {
            text = "Send"
            isEnabled = false
            setOnClickListener {
                val running = generationJob
                if (running != null && running.isActive) {
                    running.cancel()
                    text = "Send"
                    inputView.isEnabled = true
                } else {
                    sendPrompt()
                }
            }
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(inputView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(title)
            addView(statusView)
            addView(toolbar)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(composer)
        }
        setContentView(root)
    }

    private fun importModel(uri: Uri) {
        modelButton.isEnabled = false
        sendButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri).ifBlank { "model.gguf" }
                require(name.endsWith(".gguf", true)) { "Please select a .gguf model file." }
                val modelDir = File(filesDir, "models").apply { mkdirs() }
                val destination = File(modelDir, name)
                val totalBytes = querySize(uri)
                if (totalBytes > 0 && filesDir.usableSpace < totalBytes + 1_073_741_824L) {
                    error("Not enough free space. Keep at least 1 GB beyond model size.")
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(8 * 1024 * 1024)
                        var copied = 0L
                        var lastUi = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 700L && totalBytes > 0) {
                                lastUi = now
                                val pct = ((copied.toDouble() / totalBytes) * 100.0).coerceIn(0.0, 100.0).roundToInt()
                                setStatus("Importing ${name}: ${pct}%")
                            }
                        }
                    }
                } ?: error("Unable to open selected model.")
                prefs.edit().putString(KEY_MODEL_PATH, destination.absolutePath).apply()
                loadModel(destination)
            } catch (t: Throwable) {
                setStatus("Import failed: ${t.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, t.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelButton.isEnabled = true
                    sendButton.isEnabled = modelReady
                }
            }
        }
    }

    private suspend fun loadModel(file: File) {
        setStatus("Loading ${file.name}…")
        when (engine.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Error -> engine.cleanUp()
            else -> Unit
        }
        engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
        engine.loadModel(file.absolutePath)
        val systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)?.trim().orEmpty()
        if (systemPrompt.isNotEmpty()) engine.setSystemPrompt(systemPrompt)
        modelReady = true
        withContext(Dispatchers.Main) {
            modelButton.text = "Change GGUF"
            sendButton.isEnabled = true
        }
        setStatus("LOCAL • ${file.name} • 8K context")
    }

    private fun sendPrompt() {
        if (!modelReady) return
        val prompt = inputView.text.toString().trim()
        if (prompt.isEmpty()) return
        inputView.text.clear()
        transcriptView.append("\nYou: ${prompt}\nRosalina: ")
        inputView.isEnabled = false
        sendButton.text = "Stop"
        val maxTokens = prefs.getInt(KEY_MAX_TOKENS, 1024).coerceIn(64, 4096)
        generationJob = lifecycleScope.launch {
            try {
                engine.sendUserPrompt(prompt, maxTokens).collect { transcriptView.append(it) }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    transcriptView.append("\n[Generation error: ${t.message}]")
                }
            } finally {
                transcriptView.append("\n")
                saveTranscript()
                inputView.isEnabled = true
                sendButton.text = "Send"
                sendButton.isEnabled = modelReady
                generationJob = null
            }
        }
    }

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val systemPrompt = EditText(this).apply {
            hint = "System prompt"
            minLines = 4
            maxLines = 9
            setText(prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT))
        }
        val maxTokens = EditText(this).apply {
            hint = "Max output tokens (64–4096)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(KEY_MAX_TOKENS, 1024).toString())
        }
        container.addView(systemPrompt)
        container.addView(maxTokens)
        AlertDialog.Builder(this)
            .setTitle("Local AI settings")
            .setMessage("System prompt changes apply after the model is reloaded.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val parsed = maxTokens.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: 1024
                prefs.edit()
                    .putString(KEY_SYSTEM_PROMPT, systemPrompt.text.toString())
                    .putInt(KEY_MAX_TOKENS, parsed)
                    .apply()
                Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun restoreTranscript() {
        transcriptView.text = prefs.getString(
            KEY_TRANSCRIPT,
            "Rosalina Local AI V1\n\nImport a GGUF model to begin. Your chat stays on this phone.\n"
        )
    }

    private fun saveTranscript() {
        prefs.edit().putString(KEY_TRANSCRIPT, transcriptView.text.toString()).apply()
    }

    private suspend fun setStatus(value: String) = withContext(Dispatchers.Main) { statusView.text = value }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return -1L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        generationJob?.cancel()
        if (::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TRANSCRIPT = "transcript"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are Rosalina, a private on-device assistant. Be helpful, practical, direct, and clear. " +
            "The user controls this device and may customize your behavior. Do not pretend to have internet access " +
            "or tools that are not actually available."
    }
}
