package com.rosalina.studio

import android.app.ActivityManager
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal data class ChatLine(val user: Boolean, val text: String)
internal data class StudioState(
    val busy: Boolean = false, val work: String = "", val status: String = "Your private creative space",
    val progress: Int? = null, val chatPath: String = "", val imagePath: String = "",
    val reference: String = "", val result: String = "", val messages: List<ChatLine> = emptyList(),
    val details: String = "", val suggestedPrompt: String = "", val warmChat: Boolean = false
)
internal data class RenderRequest(val prompt: String, val negative: String, val width: Int,
    val height: Int, val steps: Int, val strength: Float, val seed: Long, val edit: Boolean)

// Application-scoped state survives rotations/folding. The Activity never owns native memory.
internal object StudioSession {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = mutable.asStateFlow()
    private var job: Job? = null
    private var engine: InferenceEngine? = null
    private val worker = AtomicReference<Process?>(null)
    private var request: RenderRequest? = null
    private var stopReason = ""
    private val prefs get() = app.getSharedPreferences("studio", Context.MODE_PRIVATE)
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val messages = runCatching {
            val a = JSONArray(File(app.filesDir,"chat.json").readText())
            (0 until a.length()).map { ChatLine(a.getJSONObject(it).getBoolean("user"), a.getJSONObject(it).getString("text")) }
        }.getOrDefault(emptyList())
        fun valid(key: String) = prefs.getString(key, "").orEmpty().takeIf { it.isNotEmpty() && File(it).isFile }.orEmpty()
        mutable.value = StudioState(chatPath=valid("chat"), imagePath=valid("image"),
            reference=valid("reference"), result=valid("result"), messages=messages,
            status="Offline studio · import an image model to begin")
    }
    fun notice(text: String) { mutable.update { it.copy(status=text) } }
    fun clearDetails() { mutable.update { it.copy(details="") } }
    fun consumeSuggestion() { mutable.update { it.copy(suggestedPrompt="") } }
    fun systemPrompt(): String = prefs.getString("system", DEFAULT_SYSTEM).orEmpty()
    fun saveSystemPrompt(text: String) {
        if (state.value.busy) return
        prefs.edit().putString("system", text.take(6000)).apply()
        job = scope.launch {
            mutable.update { it.copy(busy=true, work="load", status="Applying chat settings…") }
            try { releaseChat() } catch (e: Exception) { failure("CHAT SETTINGS",e) }
            finally { mutable.update { it.copy(busy=false, work="") } }
        }
    }
    private fun failure(stage: String, t: Throwable, tail: String = "") {
        val am=app.getSystemService(ActivityManager::class.java)
        val mem=ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val details="Stage: $stage\n${t.javaClass.simpleName}: ${Limits.error(t)}\n" +
            "Build: ${BuildConfig.VERSION_NAME}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nPhysical RAM: ${mem.totalMem}\nAvailable RAM: ${mem.availMem}\n" + tail.takeLast(12000)
        mutable.update { it.copy(status="$stage: ${Limits.error(t)}", details=details) }
    }
    private suspend fun persistChat() = withContext(Dispatchers.IO) {
        val lines=state.value.messages.takeLast(60)
        val a=JSONArray()
        lines.forEach { a.put(JSONObject().put("user",it.user).put("text",it.text.take(20000))) }
        atomicText(File(app.filesDir,"chat.json"),a.toString())
    }
    private fun atomicText(file: File, text: String) {
        val tmp=File(file.path+".part")
        FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { "Could not commit ${file.name}" }
    }
    private suspend fun chatEngine(): InferenceEngine {
        val path=state.value.chatPath
        require(path.isNotBlank() && File(path).isFile) { "Import the Qwen chat model first" }
        val e=engine ?: AiChat.getInferenceEngine(app).also { engine=it }
        withTimeout(60000) { e.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error
        } }
        val current=e.state.value
        if (current is InferenceEngine.State.Error) throw IOException("Chat engine needs a fresh app session: ${Limits.error(current.exception)}")
        if (current is InferenceEngine.State.Initialized) {
            notice("Loading Qwen · image engine is not running")
            e.loadModel(path)
            systemPrompt().trim().takeIf { it.isNotEmpty() }?.let { e.setSystemPrompt(it) }
        }
        mutable.update { it.copy(warmChat=true) }
        return e
    }
    private suspend fun releaseChat() = withContext(Dispatchers.IO) {
        when (val s=engine?.state?.value) {
            is InferenceEngine.State.ModelReady -> engine?.cleanUp()
            is InferenceEngine.State.Error -> throw IOException("Restart Image Lab before rendering; chat initialization failed: ${Limits.error(s.exception)}")
            null, is InferenceEngine.State.Initialized -> Unit
            else -> throw IOException("Chat is still busy; stop its response first")
        }
        mutable.update { it.copy(warmChat=false) }
    }
    fun send(text: String, improveImagePrompt: Boolean = false) {
        if (state.value.busy || text.isBlank()) return
        if (state.value.chatPath.isBlank()) { notice("Import Qwen under Models to use chat or the prompt helper"); return }
        val actual=if (improveImagePrompt) "Rewrite this description as a concise Stable Diffusion image prompt. Return only the improved prompt, no commentary. Description: $text" else text
        mutable.update { it.copy(busy=true, work="chat", progress=null, status="Preparing local reply…",
            messages=(it.messages + ChatLine(true, if(improveImagePrompt) "Improve image prompt: $text" else text) + ChatLine(false,"" )).takeLast(60)) }
        job=scope.launch {
            try {
                val e=chatEngine()
                notice("Qwen is answering locally…")
                val answer=StringBuilder()
                e.sendUserPrompt(actual, if(improveImagePrompt) 256 else 1024).collect { token ->
                    answer.append(token)
                    mutable.update { it.copy(messages=it.messages.dropLast(1)+ChatLine(false,answer.toString())) }
                }
                if(improveImagePrompt) {
                    val prompt=answer.toString().replace(Regex("(?s)<think>.*?</think>"),"").trim().take(2500)
                    mutable.update { it.copy(suggestedPrompt=prompt) }
                }
                notice("Local chat ready · 8K context")
            } catch(e: CancellationException) { notice("Reply stopped"); throw e }
            catch(e: Exception) { failure("CHAT",e) }
            finally {
                withContext(NonCancellable) { persistChat() }
                mutable.update { it.copy(busy=false, work="",progress=null) }
            }
        }
    }
    fun clearChat() {
        if(state.value.busy) return
        mutable.update { it.copy(busy=true, work="load",status="Starting a fresh chat…") }
        job=scope.launch {
            try { releaseChat(); mutable.update { it.copy(messages=emptyList()) }; persistChat(); notice("New chat ready") }
            catch(e: Exception) { failure("NEW CHAT",e) }
            finally { mutable.update { it.copy(busy=false,work="") } }
        }
    }
    fun cancel() {
        stopReason="Stopped by you"
        worker.get()?.destroyForcibly()
        job?.cancel()
        notice("Stopping…")
    }
    fun importModel(uri: Uri, image: Boolean) {
        if(state.value.busy) return
        mutable.update { it.copy(busy=true, work="import",status="Inspecting model…",progress=0,details="") }
        job=scope.launch {
            var stage="MODEL IMPORT"
            var tmp: File?=null
            try {
                val file=withContext(Dispatchers.IO) {
                    val expected=if(image) Limits.IMAGE_SHA else Limits.CHAT_SHA
                    var total=-1L
                    app.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { c ->
                        if(c.moveToFirst() && !c.isNull(0)) total=c.getLong(0)
                    }
                    val dir=File(app.filesDir,"models").apply { check(mkdirs() || isDirectory) }
                    require(total <= 0 || dir.usableSpace > total + 536870912L) { "Not enough space to import this model" }
                    val target=File(dir,(if(image) "sd15-" else "qwen-")+expected.take(12)+".gguf")
                    if(target.isFile && prefs.getString(if(image) "image.sha" else "chat.sha","")==expected) {
                        // Still verify the existing bytes before reusing an interrupted setup.
                        val old=MessageDigest.getInstance("SHA-256")
                        target.inputStream().use { input ->
                            val b=ByteArray(1024*1024)
                            while(true) { ensureActive(); val n=input.read(b); if(n<0) break; old.update(b,0,n) }
                        }
                        if(hex(old.digest())==expected) return@withContext target
                    }
                    val part=File(dir,UUID.randomUUID().toString()+".part"); tmp=part
                    val digest=MessageDigest.getInstance("SHA-256")
                    var copied=0L; var tick=0L
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(part).use { output ->
                            val b=ByteArray(1024*1024)
                            while(true) {
                                ensureActive(); val n=input.read(b); if(n<0) break
                                output.write(b,0,n); digest.update(b,0,n); copied+=n
                                if(SystemClock.elapsedRealtime()-tick>250) {
                                    tick=SystemClock.elapsedRealtime()
                                    val pct=if(total>0) ((copied*100)/total).toInt().coerceIn(0,99) else null
                                    mutable.update { it.copy(status="Copying + checking model · ${copied/1000000} MB",progress=pct) }
                                }
                            }
                            output.fd.sync()
                        }
                    } ?: throw IOException("Cannot read selected file")
                    stage="MODEL VERIFY"
                    require(total<=0 || total==copied) { "Incomplete file copy; keep the original download" }
                    require(hex(digest.digest())==expected) {
                        if(image) "This is not the verified SD 1.5 Q4 image model. Use Get image model under Models; do not select the Qwen file."
                        else "This is not the verified Qwen Q4_K_M chat model. Select your original working download."
                    }
                    require(part.inputStream().use { String(it.readNBytes(4),Charsets.US_ASCII) }=="GGUF") { "Invalid GGUF header" }
                    check(part.renameTo(target)) { "Could not commit verified model" }
                    target
                }
                val key=if(image) "image" else "chat"
                prefs.edit().putString(key,file.path).putString("$key.sha",if(image) Limits.IMAGE_SHA else Limits.CHAT_SHA).apply()
                mutable.update { if(image) it.copy(imagePath=file.path,status="Image model verified · ready to generate") else it.copy(chatPath=file.path,status="Qwen verified · ready for chat") }
            } catch(e: CancellationException) { notice("Import stopped; original download unchanged"); throw e }
            catch(e: Exception) { failure(stage,e) }
            finally { tmp?.delete(); mutable.update { it.copy(busy=false,work="",progress=null) } }
        }
    }
    private fun hex(b: ByteArray)=b.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun importReference(uri: Uri) {
        if(state.value.busy) return
        mutable.update { it.copy(busy=true,work="import",status="Preparing reference photo…") }
        job=scope.launch {
            try {
                val f=withContext(Dispatchers.IO) {
                    val source=ImageDecoder.createSource(app.contentResolver,uri)
                    val bitmap=ImageDecoder.decodeBitmap(source) { d, info, _ ->
                        require(info.size.width.toLong()*info.size.height<=200000000) { "Image dimensions are too large" }
                        val scale=1024.0/maxOf(info.size.width,info.size.height).coerceAtLeast(1)
                        d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                        if(scale<1) d.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                    }
                    val file=File(app.filesDir,"reference.png")
                    val part=File(app.filesDir,"reference.part")
                    try { FileOutputStream(part).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)); it.fd.sync() }; check(part.renameTo(file)) }
                    finally { bitmap.recycle(); part.delete() }
                    file
                }
                prefs.edit().putString("reference",f.path).apply()
                mutable.update { it.copy(reference=f.path,status="Reference ready · output uses a centered crop") }
            } catch(e: Exception) { failure("REFERENCE PHOTO",e) }
            finally { mutable.update { it.copy(busy=false,work="") } }
        }
    }
    fun prepareRender(r: RenderRequest): Boolean {
        if(state.value.busy) return false
        try {
            Limits.validate(r.width,r.height,r.steps,r.strength)
            require(r.prompt.isNotBlank() && r.prompt.length<=4000 && r.negative.length<=2000) { "Enter an image prompt (up to 4,000 characters)" }
            require(state.value.imagePath.isNotBlank()) { "Import the SD 1.5 image model under Models first" }
            require(!r.edit || state.value.reference.isNotBlank()) { "Choose a reference photo for Edit" }
        } catch(e: Exception) { notice(Limits.error(e)); return false }
        stopReason=""; request=r
        mutable.update { it.copy(busy=true,work="render",progress=null,status="Releasing chat memory…",details="") }
        return true
    }
    fun renderFailedToStart(e: Exception) { request=null; failure("START RENDER",e); mutable.update { it.copy(busy=false,work="") } }
    fun startRender(done: () -> Unit) {
        val r=request ?: run { done(); return }; request=null
        job=scope.launch {
            var taskDir: File?=null
            val tail=StringBuilder()
            try {
                releaseChat()
                val power=app.getSystemService(PowerManager::class.java)
                require(power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Phone is too warm; let it cool before rendering" }
                val mem=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                require(mem.availMem>2500000000L) { "Less than 2.5 GB RAM is available after unloading chat. Close other heavy apps." }
                val selectedModel=state.value.imagePath
                val reference=state.value.reference
                val file=withContext(Dispatchers.IO) {
                    val exe=File(app.applicationInfo.nativeLibraryDir,"librosalina-image.so")
                    require(exe.isFile && exe.canExecute()) { "Native image worker is missing or not executable. Reinstall this test APK." }
                    val dir=File(app.cacheDir,"render-${UUID.randomUUID()}").apply { check(mkdirs()) }; taskDir=dir
                    File(dir,"prompt.txt").writeText(r.prompt); File(dir,"negative.txt").writeText(r.negative)
                    val input=if(r.edit) {
                        val source=BitmapFactory.decodeFile(reference) ?: error("Reference photo is unavailable")
                        val dest=Bitmap.createBitmap(r.width,r.height,Bitmap.Config.ARGB_8888)
                        try {
                            val canvas=Canvas(dest); canvas.drawColor(Color.WHITE)
                            val scale=maxOf(r.width.toFloat()/source.width,r.height.toFloat()/source.height)
                            val x=(r.width-source.width*scale)/2; val y=(r.height-source.height*scale)/2
                            canvas.drawBitmap(source,null,RectF(x,y,x+source.width*scale,y+source.height*scale),Paint(Paint.FILTER_BITMAP_FLAG))
                            val pixels=IntArray(r.width*r.height); dest.getPixels(pixels,0,r.width,0,0,r.width,r.height)
                            FileOutputStream(File(dir,"init.rgb")).buffered().use { out -> pixels.forEach { p -> out.write((p shr 16) and 255); out.write((p shr 8) and 255); out.write(p and 255) } }
                        } finally { source.recycle(); dest.recycle() }
                        File(dir,"init.rgb").path
                    } else "-"
                    val output=File(dir,"output.rimg")
                    val command=listOf(exe.path,selectedModel,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,input,output.path,
                        r.width.toString(),r.height.toString(),r.steps.toString(),r.seed.toString(),r.strength.toString(),minOf(4,Runtime.getRuntime().availableProcessors()).coerceAtLeast(1).toString())
                    val p=ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
                    worker.set(p)
                    if(!isActive) p.destroyForcibly()
                    val started=SystemClock.elapsedRealtime()
                    val watchdog=launch {
                        while(isActive) {
                            delay(3000)
                            if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE) {
                                stopReason="Stopped because Android reported severe heat"; p.destroyForcibly(); break
                            }
                            if(SystemClock.elapsedRealtime()-started>30*60*1000L) {
                                stopReason="Rendering exceeded the 30-minute test limit"; p.destroyForcibly(); break
                            }
                        }
                    }
                    val code=try {
                        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                            if(tail.length>16000) tail.delete(0,tail.length-12000)
                            tail.append(line).append('\n')
                            when {
                                line.startsWith("@@STAGE ") -> mutable.update { it.copy(status=line.removePrefix("@@STAGE "),progress=null) }
                                line.startsWith("@@STEP ") -> {
                                    val n=line.split(" ")
                                    val step=n.getOrNull(1)?.toIntOrNull() ?: 0; val total=n.getOrNull(2)?.toIntOrNull() ?: 0
                                    if(total>0) mutable.update { it.copy(status="Generating · step $step of $total",progress=(step*100/total).coerceIn(0,100)) }
                                }
                            }
                        } }
                        p.waitFor()
                    } finally { watchdog.cancel(); worker.compareAndSet(p,null); if(p.isAlive) p.destroyForcibly() }
                    ensureActive()
                    if(stopReason.isNotBlank()) throw IOException(stopReason)
                    require(code==0) { "Image engine exited with code $code. Open Details for the native error." }
                    val bytes=output.readBytes()
                    require(bytes.size>=16 && String(bytes,0,4,Charsets.US_ASCII)=="RIMG") { "Invalid native output" }
                    val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val w=header.getInt(4); val h=header.getInt(8); val ch=header.getInt(12)
                    require(w==r.width && h==r.height && ch==3 && bytes.size==16+w*h*3) { "Incomplete image output" }
                    val pixels=IntArray(w*h) { i -> val o=16+i*3; Color.rgb(bytes[o].toInt() and 255,bytes[o+1].toInt() and 255,bytes[o+2].toInt() and 255) }
                    val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                    val results=File(app.filesDir,"results").apply { check(mkdirs() || isDirectory) }
                    val result=File(results,"Rosalina-${System.currentTimeMillis()}.png")
                    val part=File(result.path+".part")
                    try { FileOutputStream(part).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)); it.fd.sync() }; check(part.renameTo(result)) }
                    finally { bitmap.recycle(); part.delete() }
                    val metadata=JSONObject().put("prompt",r.prompt).put("negative",r.negative).put("seed",r.seed).put("steps",r.steps)
                        .put("width",w).put("height",h).put("strength",r.strength).put("edit",r.edit).put("engine","stable-diffusion.cpp 3f8527a")
                    atomicText(File(result.path+".json"),metadata.toString(2))
                    result
                }
                prefs.edit().putString("result",file.path).apply()
                mutable.update { it.copy(result=file.path,status="Image ready · saved privately in Rosalina") }
            } catch(e: CancellationException) { notice("Generation stopped · chat and models preserved"); throw e }
            catch(e: Exception) { failure("IMAGE GENERATION",e,tail.toString()) }
            finally {
                worker.getAndSet(null)?.destroyForcibly()
                taskDir?.deleteRecursively()
                mutable.update { it.copy(busy=false,work="",progress=null) }
                done()
            }
        }
    }
    fun results(): List<File> = File(app.filesDir,"results").listFiles()?.filter { it.extension=="png" }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun selectResult(file: File) { if(!state.value.busy) mutable.update { it.copy(result=file.path,status="Saved image selected") } }
    private const val DEFAULT_SYSTEM="You are Rosalina, a private on-device assistant. Be helpful, practical, direct, and clear. Do not claim internet access or tools you do not have."
}
