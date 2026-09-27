package com.rosalina.motion

import android.app.ActivityManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal data class MotionState(val busy:Boolean=false,val work:String="",val status:String="Choose a photo. Describe its motion.",val progress:Int?=null,
    val videoModel:String="",val textModel:String="",val photo:String="",val result:String="",val details:String="",val started:Long=0)
internal object MotionSession {
    private lateinit var app:Context
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(MotionState())
    val state:StateFlow<MotionState> = mutable.asStateFlow()
    private val process=AtomicReference<Process?>(null)
    private var job:Job?=null
    private var pending:Pair<String,MotionSpec>?=null
    private var stopMessage="Stopped by you"
    private val prefs get()=app.getSharedPreferences("motion",Context.MODE_PRIVATE)
    fun init(c:Context) {
        if(::app.isInitialized) return
        app=c.applicationContext
        fun path(key:String)=prefs.getString(key,"").orEmpty().takeIf{it.isNotEmpty()&&File(it).isFile}.orEmpty()
        mutable.value=MotionState(videoModel=path("video"),textModel=path("text"),photo=path("photo"),result=path("result"))
    }
    fun notice(s:String){mutable.update{it.copy(status=s)}}
    fun fail(stage:String,t:Throwable,tail:String="") {
        val mem=ActivityManager.MemoryInfo().also{app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        val d="Stage: $stage\n${t.javaClass.simpleName}: ${MotionMath.error(t)}\nBuild: ${BuildConfig.VERSION_NAME}\n"+
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\nRAM total=${mem.totalMem}, available=${mem.availMem}\n"+tail.takeLast(12000)
        mutable.update{it.copy(status="$stage: ${MotionMath.error(t)}",details=d)}
        scope.launch(Dispatchers.IO){runCatching{File(app.filesDir,"last-diagnostic.txt").writeText(d)}}
    }
    fun cancel(reason:String="Stopped by you") {
        stopMessage=reason;pending=null;process.get()?.destroyForcibly();job?.cancel()
        if(job==null) mutable.update{it.copy(busy=false,work="",progress=null,status=reason)}
        else notice("Stopping and releasing video memory…")
    }
    fun importModel(uri:Uri,part:ModelPart) {
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,work="import",status="Inspecting ${part.label}",progress=0,details="",started=SystemClock.elapsedRealtime())}
        job=scope.launch {
            var temp:File?=null
            try {
                val target=withContext(Dispatchers.IO) {
                    val dir=File(app.filesDir,"models").apply{check(mkdirs()||isDirectory)}
                    var total=-1L
                    app.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()&&!c.isNull(0))total=c.getLong(0)}
                    require(total<=0 || dir.usableSpace>total+512L*1024*1024){"Not enough free space for a verified model copy"}
                    val f=File(dir,part.name.lowercase()+"-"+part.sha.take(12)+".gguf")
                    temp=File(dir,UUID.randomUUID().toString()+".part")
                    val md=MessageDigest.getInstance("SHA-256");var bytes=0L;var last=0L
                    app.contentResolver.openInputStream(uri)?.use{input->
                        FileOutputStream(temp!!).use{output->
                            val b=ByteArray(1024*1024)
                            while(true){ensureActive();val n=input.read(b);if(n<0)break;output.write(b,0,n);md.update(b,0,n);bytes+=n
                                val now=SystemClock.elapsedRealtime()
                                if(now-last>500){last=now;val pct=if(total>0)(bytes*100/total).toInt().coerceIn(0,100) else null
                                    mutable.update{it.copy(status="Copying and checking ${part.name.lowercase()} · ${bytes/(1024*1024)} MiB",progress=pct)}}
                            }
                            output.fd.sync()
                        }
                    }?:error("Android could not open this download")
                    require(total<=0 || bytes==total){"Download or copy is incomplete"}
                    require(MotionMath.hex(md.digest())==part.sha){"Wrong or incomplete file. Select ${part.fileName}; Qwen and SD 1.5 are not video models."}
                    check(temp!!.renameTo(f)){"Could not finish verified model import"};temp=null;f
                }
                val key=if(part==ModelPart.VIDEO)"video" else "text"
                prefs.edit().putString(key,target.absolutePath).apply()
                mutable.update{if(part==ModelPart.VIDEO)it.copy(videoModel=target.path) else it.copy(textModel=target.path)}
                notice("${part.label} · checksum verified and imported")
            }catch(e:CancellationException){notice(stopMessage);throw e}
            catch(e:Exception){fail("MODEL IMPORT",e)}
            finally{withContext(NonCancellable+Dispatchers.IO){temp?.delete()};mutable.update{it.copy(busy=false,work="",progress=null)};job=null}
        }
    }
    fun selectPhoto(uri:Uri) {
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,work="photo",status="Preparing your gallery photo…",details="")}
        job=scope.launch {
            try {
                val p=withContext(Dispatchers.IO){
                    val source=ImageDecoder.createSource(app.contentResolver,uri)
                    val image=ImageDecoder.decodeBitmap(source){decoder,info,_->
                        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                        val ratio=max(info.size.width,info.size.height)/1600.0
                        if(ratio>1)decoder.setTargetSize(max(1,(info.size.width/ratio).toInt()),max(1,(info.size.height/ratio).toInt()))
                    }
                    val target=File(app.filesDir,"photo-${UUID.randomUUID()}.png")
                    try{FileOutputStream(target).use{check(image.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{image.recycle()}
                    target
                }
                val old=state.value.photo;prefs.edit().putString("photo",p.path).apply()
                mutable.update{it.copy(photo=p.path,status="Photo ready · describe what should move")}
                if(old.isNotEmpty())withContext(Dispatchers.IO){File(old).delete()}
            }catch(e:CancellationException){throw e}catch(e:Exception){fail("PHOTO",e)}
            finally{mutable.update{it.copy(busy=false,work="")};job=null}
        }
    }
    fun requestRender(prompt:String,spec:MotionSpec) {
        if(state.value.busy)return
        try{
            spec.validate();require(prompt.trim().isNotEmpty()){ "Describe the motion first" }
            require(prompt.toByteArray().size<=16000){"Motion description is too long"}
            require(state.value.photo.isNotBlank()){ "Choose a gallery photo first" }
            require(state.value.videoModel.isNotBlank()&&state.value.textModel.isNotBlank()){ "Import both video model files under Models" }
            pending=prompt.trim() to spec;stopMessage="Stopped by you"
            mutable.update{it.copy(busy=true,work="render",progress=null,status="Starting local video renderer…",details="",started=SystemClock.elapsedRealtime())}
            ContextCompat.startForegroundService(app,Intent(app,MotionService::class.java))
        }catch(e:Exception){pending=null;mutable.update{it.copy(busy=false,work="")};fail("START",e)}
    }
    fun runPending() {
        val request=pending?:return;pending=null
        job=scope.launch {
            val (prompt,spec)=request
            val dir=File(app.filesDir,"jobs/"+UUID.randomUUID()).apply{mkdirs()}
            val log=File(dir,"native.log");var committed=false;var stage="PREPARE";var tmpMp4:File?=null
            try{
                withTimeout(120*60*1000L){withContext(Dispatchers.IO){
                    val mem=ActivityManager.MemoryInfo().also{app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
                    require(!mem.lowMemory && mem.availMem>3L*1024*1024*1024){"Available RAM is low. Close the original Qwen/Image Lab apps, then retry."}
                    val pm=app.getSystemService(PowerManager::class.java)
                    require(pm.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"Let the phone cool down before rendering"}
                    val exe=File(app.applicationInfo.nativeLibraryDir,"librosalina-motion.so")
                    require(exe.isFile&&exe.canExecute()){ "Video worker was not extracted during installation" }
                    stage="DECODER CHECK"
                    val tae=File(app.filesDir,"taew2_2.safetensors")
                    val expected=app.assets.open("tae.sha256").bufferedReader().use{it.readText().trim()}
                    fun digest(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{i->val b=ByteArray(1024*1024);while(true){val n=i.read(b);if(n<0)break;md.update(b,0,n)}};return MotionMath.hex(md.digest())}
                    if(!tae.isFile || digest(tae)!=expected){
                        app.assets.open("taew2_2.safetensors").use{i->FileOutputStream(tae).use{o->i.copyTo(o);o.fd.sync()}}
                        require(digest(tae)==expected){"Bundled video decoder is damaged"}
                    }
                    val ref=File(dir,"reference.rgb");referenceRgb(File(state.value.photo),ref,spec)
                    File(dir,"prompt.txt").writeText(prompt)
                    File(dir,"negative.txt").writeText("blurry, distortion, text, watermark, flicker, still frame")
                    stage="VIDEO GENERATION"
                    notice("Loading video models · ${spec.seconds}s clip · CPU draft")
                    val raw=File(dir,"frames.rvf")
                    val args=listOf(exe.path,state.value.videoModel,state.value.textModel,tae.path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,
                        ref.path,raw.path,spec.width.toString(),spec.height.toString(),spec.modelFrames.toString(),spec.steps.toString(),spec.seed.toString(),"4")
                    val p=ProcessBuilder(args).directory(dir).redirectErrorStream(true).start();process.set(p)
                    try{
                        log.bufferedWriter().use{writer->p.inputStream.bufferedReader().useLines{lines->lines.forEach{line->
                            ensureActive();writer.appendLine(line);writer.flush()
                            if(line.startsWith("@@STAGE "))notice(line.removePrefix("@@STAGE "))
                            else if(line.startsWith("@@STEP ")){val a=line.split(' ');val n=a.getOrNull(1)?.toIntOrNull();val total=a.getOrNull(2)?.toIntOrNull()
                                if(n!=null&&total!=null&&total>0)mutable.update{it.copy(status="Generating motion · step $n of $total",progress=(n*100/total).coerceIn(0,100))}
                            }
                        }}}
                        ensureActive();check(p.waitFor()==0){"Video worker stopped before completing the clip"}
                    }finally{if(p.isAlive)p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);process.compareAndSet(p,null)}
                    stage="MP4 ENCODING";notice("Encoding ${spec.seconds}-second MP4…")
                    val videos=File(app.filesDir,"videos").apply{mkdirs()};val name="Rosalina-${System.currentTimeMillis()}"
                    val part=File(videos,"$name.mp4.part");tmpMp4=part
                    Mp4Encoder.encode(raw,part,spec){pct->mutable.update{it.copy(progress=pct)}}
                    ensureActive()
                    val result=File(videos,"$name.mp4");check(part.renameTo(result)){"Could not save completed MP4"};committed=true
                    File(videos,"$name.json").writeText(JSONObject().put("prompt",prompt).put("seconds",spec.seconds).put("fps",8).put("width",spec.width).put("height",spec.height)
                        .put("steps",spec.steps).put("seed",spec.seed).put("generatedFrames",spec.modelFrames).put("exportFrames",spec.exportFrames).put("backend","cpu").toString(2))
                    prefs.edit().putString("result",result.path).apply()
                    mutable.update{it.copy(result=result.path,status="Your ${spec.seconds}-second clip is ready · 8 fps draft",progress=100)}
                }}
            }catch(e:TimeoutCancellationException){fail("TIME LIMIT",IllegalStateException("Stopped after two hours to avoid an unbounded phone render"),tail(log))}
            catch(e:CancellationException){notice(stopMessage);throw e}
            catch(e:Exception){fail(stage,e,tail(log))}
            finally{
                withContext(NonCancellable+Dispatchers.IO){
                    process.getAndSet(null)?.let{it.destroyForcibly();it.waitFor(10,TimeUnit.SECONDS)}
                    if(log.isFile)runCatching{log.copyTo(File(app.filesDir,"last-native.log"),overwrite=true)}
                    if(!committed)tmpMp4?.delete();dir.deleteRecursively()
                }
                mutable.update{it.copy(busy=false,work="",progress=null)};job=null
                app.stopService(Intent(app,MotionService::class.java))
            }
        }
    }
    private fun tail(f:File):String=runCatching{RandomAccessFile(f,"r").use{r->r.seek(max(0L,r.length()-16000));val b=ByteArray((r.length()-r.filePointer).toInt());r.readFully(b);String(b)}}.getOrDefault("")
    private fun referenceRgb(source:File,destination:File,s:MotionSpec) {
        val original=BitmapFactory.decodeFile(source.path)?:error("Gallery reference is no longer readable")
        val target=Bitmap.createBitmap(s.width,s.height,Bitmap.Config.ARGB_8888)
        try{
            val canvas=Canvas(target);canvas.drawColor(Color.BLACK)
            val scale=minOf(s.width.toFloat()/original.width,s.height.toFloat()/original.height)
            val w=original.width*scale;val h=original.height*scale
            // Preserve the entire input image. Letterboxing may be reinterpreted by the generative model.
            canvas.drawBitmap(original,null,RectF((s.width-w)/2,(s.height-h)/2,(s.width+w)/2,(s.height+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(s.width*s.height);target.getPixels(pixels,0,s.width,0,0,s.width,s.height)
            FileOutputStream(destination).buffered().use{o->for(p in pixels){o.write((p shr 16) and 255);o.write((p shr 8) and 255);o.write(p and 255)}}
        }finally{original.recycle();target.recycle()}
    }
    fun history():List<File> = File(app.filesDir,"videos").listFiles()?.filter{it.extension=="mp4"}?.sortedByDescending{it.lastModified()}?.take(12)?:emptyList()
    fun openResult(file:File){if(!state.value.busy&&file.isFile)mutable.update{it.copy(result=file.path)}}
}
