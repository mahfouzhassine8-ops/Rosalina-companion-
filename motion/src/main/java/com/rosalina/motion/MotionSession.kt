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
import java.lang.Process
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal data class MotionState(val busy:Boolean=false,val work:String="",val status:String="Choose a photo. Describe its motion.",val progress:Int?=null,
    val videoModel:String="",val textModel:String="",val photo:String="",val result:String="",val details:String="",val started:Long=0,
    val expectedFinish:Long=0,val stopping:Boolean=false,val thermal:String="")
internal object MotionSession {
    private lateinit var app:Context
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(MotionState())
    val state:StateFlow<MotionState> = mutable.asStateFlow()
    private val process=AtomicReference<Process?>(null)
    private val workerPid=AtomicInteger(0)
    private val stopRequested=AtomicBoolean(false)
    private var job:Job?=null
    private var stopJob:Job?=null
    private var pending:Pair<String,MotionSpec>?=null
    private var stopMessage="Stopped by you"
    private const val DECODER_SHA="b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325"
    private const val MAX_RENDER_MS=120*60*1000L
    private val prefs get()=app.getSharedPreferences("motion",Context.MODE_PRIVATE)
    private fun profile(spec:MotionSpec)="${spec.width}x${spec.height}_${spec.seconds}s_${spec.steps}steps"
    private fun totalBaselineMs(spec:MotionSpec)=prefs.getLong("eta_total_${profile(spec)}",0L)
    private fun postBaselineMs(spec:MotionSpec)=prefs.getLong("eta_post_${profile(spec)}",0L)
    private fun recordBaseline(spec:MotionSpec,totalMs:Long,postMs:Long){
        val key=profile(spec)
        val total=MotionProgressMath.smoothMs(prefs.getLong("eta_total_$key",0L),totalMs)
        val editor=prefs.edit().putLong("eta_total_$key",total)
        if(postMs>0)editor.putLong("eta_post_$key",MotionProgressMath.smoothMs(prefs.getLong("eta_post_$key",0L),postMs))
        editor.apply()
    }
    fun init(c:Context) {
        if(::app.isInitialized) return
        app=c.applicationContext
        fun path(key:String)=prefs.getString(key,"").orEmpty().takeIf{it.isNotEmpty()&&File(it).isFile}.orEmpty()
        mutable.value=MotionState(videoModel=path("video"),textModel=path("text"),photo=path("photo"),result=path("result"))
    }
    fun notice(s:String){mutable.update{current->if(current.stopping)current else current.copy(status=s)}}
    fun updateThermal(level:Int){
        val text=when {
            level>=PowerManager.THERMAL_STATUS_SEVERE -> "Thermal: Severe · stopping render to protect the phone"
            level>=PowerManager.THERMAL_STATUS_MODERATE -> "Thermal: Warm · Android/Samsung throttling active"
            level>=PowerManager.THERMAL_STATUS_LIGHT -> "Thermal: Warm"
            else -> "Thermal: Normal"
        }
        mutable.update{it.copy(thermal=text)}
    }
    fun fail(stage:String,t:Throwable,tail:String="") {
        val mem=ActivityManager.MemoryInfo().also{app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        val activePid=workerPid.get().takeIf{it>0}
        val d="Stage: $stage\n${t.javaClass.simpleName}: ${MotionMath.error(t)}\nBuild: ${BuildConfig.VERSION_NAME}\n"+
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\nRAM total=${mem.totalMem}, available=${mem.availMem}\n"+
            "Thermal: ${state.value.thermal.ifBlank{"unknown"}}\nStopping: ${state.value.stopping}\nWorker pid: ${activePid?:"none"}\n"+tail.takeLast(12000)
        mutable.update{it.copy(status="$stage: ${MotionMath.error(t)}",details=d,expectedFinish=0,stopping=false)}
        scope.launch(Dispatchers.IO){runCatching{File(app.filesDir,"last-diagnostic.txt").writeText(d)}}
    }
    private fun terminateWorker(target:Process){
        runCatching{target.destroy()}
        if(runCatching{target.waitFor(350,TimeUnit.MILLISECONDS)}.getOrDefault(false)){
            workerPid.set(0);process.compareAndSet(target,null);return
        }
        workerPid.getAndSet(0).takeIf{it>0}?.let{pid->runCatching{android.os.Process.killProcess(pid)}}
        if(!runCatching{target.waitFor(750,TimeUnit.MILLISECONDS)}.getOrDefault(false)){
            runCatching{target.destroyForcibly()}
            runCatching{target.waitFor(1250,TimeUnit.MILLISECONDS)}
        }
        process.compareAndSet(target,null)
    }
    fun cancel(reason:String="Stopped by you") {
        stopMessage=reason
        pending=null
        if(!state.value.busy){
            mutable.update{it.copy(status=reason,progress=null,expectedFinish=0,stopping=false)}
            return
        }
        stopRequested.set(true)
        mutable.update{it.copy(status="Stopping…",expectedFinish=0,stopping=true)}
        if(stopJob?.isActive==true)return
        val activeJob=job
        val target=process.get()
        stopJob=scope.launch {
            withContext(Dispatchers.IO){target?.let{terminateWorker(it)}}
            activeJob?.cancel(CancellationException(reason))
            withTimeoutOrNull(4000){activeJob?.join()}
            workerPid.set(0)
            process.set(null)
            stopRequested.set(false)
            mutable.update{it.copy(busy=false,work="",progress=null,status=reason,expectedFinish=0,stopping=false,thermal="")}
            app.stopService(Intent(app,MotionService::class.java))
        }
    }
    fun importModel(uri:Uri,part:ModelPart) {
        if(state.value.busy)return
        stopMessage="Stopped by you"
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
            finally{
                withContext(NonCancellable+Dispatchers.IO){temp?.delete()}
                val stopped=stopRequested.getAndSet(false)
                mutable.update{it.copy(busy=false,work="",progress=null,stopping=false,expectedFinish=0,status=if(stopped)stopMessage else it.status)}
                job=null
            }
        }
    }
    fun selectPhoto(uri:Uri) {
        if(state.value.busy)return
        stopMessage="Stopped by you"
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
            }catch(e:CancellationException){notice(stopMessage);throw e}catch(e:Exception){fail("PHOTO",e)}
            finally{
                val stopped=stopRequested.getAndSet(false)
                mutable.update{it.copy(busy=false,work="",stopping=false,expectedFinish=0,status=if(stopped)stopMessage else it.status)}
                job=null
            }
        }
    }
    fun requestRender(prompt:String,spec:MotionSpec) {
        if(state.value.busy)return
        try{
            spec.validate();require(prompt.trim().isNotEmpty()){ "Describe the motion first" }
            require(prompt.toByteArray().size<=16000){"Motion description is too long"}
            require(state.value.photo.isNotBlank()){ "Choose a gallery photo first" }
            require(state.value.videoModel.isNotBlank()&&state.value.textModel.isNotBlank()){ "Import both video model files under Models" }
            pending=prompt.trim() to spec;stopMessage="Stopped by you";stopRequested.set(false);stopJob=null
            val now=SystemClock.elapsedRealtime()
            val baseline=totalBaselineMs(spec)
            val startStatus=if(baseline>0)
                "Starting local video renderer · previous-run estimate ~${MotionProgressMath.formatDuration(baseline/1000)}"
            else "Starting local video renderer · calculating ETA from this phone"
            mutable.update{it.copy(busy=true,work="render",progress=0,status=startStatus,details="",started=now,expectedFinish=if(baseline>0)now+baseline else 0)}
            ContextCompat.startForegroundService(app,Intent(app,MotionService::class.java))
        }catch(e:Exception){pending=null;mutable.update{it.copy(busy=false,work="")};fail("START",e)}
    }
    fun runPending() {
        val request=pending?:return;pending=null
        job=scope.launch {
            val (prompt,spec)=request
            val dir=File(app.filesDir,"jobs/"+UUID.randomUUID()).apply{mkdirs()}
            val log=File(dir,"native.log");var committed=false;var stage="PREPARE";var tmpMp4:File?=null
            var samplingStartedAt=0L
            var samplingFinishedAt=0L
            val historicalPost=postBaselineMs(spec)
            fun renderUpdate(message:String,pct:Int?=null,remainingMs:Long?=null){
                val now=SystemClock.elapsedRealtime()
                mutable.update { current ->
                    if(current.stopping||stopRequested.get())current
                    else {
                        val nextPct=pct?.coerceIn(0,100)?.let{maxOf(current.progress?:0,it)}?:current.progress
                        current.copy(status=message,progress=nextPct,expectedFinish=remainingMs?.let{now+it}?:current.expectedFinish)
                    }
                }
            }
            val deadline=(state.value.started.takeIf{it>0}?:SystemClock.elapsedRealtime())+MAX_RENDER_MS
            try{
                withTimeout(MAX_RENDER_MS+30_000L){withContext(Dispatchers.IO){
                    val mem=ActivityManager.MemoryInfo().also{app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
                    require(!mem.lowMemory && mem.availMem>3L*1024*1024*1024){"Available RAM is low. Close Qwen/Image Lab, then retry. Motion Lab can continue in the background once generation starts."}
                    val pm=app.getSystemService(PowerManager::class.java)
                    require(pm.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"Let the phone cool down before rendering"}
                    val exe=File(app.applicationInfo.nativeLibraryDir,"librosalina-motion.so")
                    require(exe.isFile&&exe.canExecute()){ "Video worker was not extracted during installation" }
                    stage="DECODER CHECK"
                    val tae=File(app.filesDir,"taew2_2.safetensors")
                    fun digest(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{i->val b=ByteArray(1024*1024);while(true){val n=i.read(b);if(n<0)break;md.update(b,0,n)}};return MotionMath.hex(md.digest())}
                    if(!tae.isFile || digest(tae)!=DECODER_SHA){
                        app.assets.open("taew2_2.safetensors").use{i->FileOutputStream(tae).use{o->i.copyTo(o);o.fd.sync()}}
                        require(digest(tae)==DECODER_SHA){"Bundled video decoder is damaged"}
                    }
                    val ref=File(dir,"reference.rgb");referenceRgb(File(state.value.photo),ref,spec)
                    File(dir,"prompt.txt").writeText(prompt)
                    File(dir,"negative.txt").writeText("blurry, distortion, text, watermark, flicker, still frame")
                    stage="VIDEO GENERATION"
                    renderUpdate("Loading video models · ${spec.seconds}s clip · CPU draft",2)
                    val raw=File(dir,"frames.rvf")
                    val args=mutableListOf(exe.path,state.value.videoModel,state.value.textModel,tae.path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,
                        ref.path,raw.path,spec.width.toString(),spec.height.toString(),spec.modelFrames.toString(),spec.steps.toString(),spec.seed.toString(),"4")
                    ensureActive()
                    val thermalStatus=pm.currentThermalStatus
                    val workerThreads=if(thermalStatus>=PowerManager.THERMAL_STATUS_MODERATE)3 else 4
                    args[args.lastIndex]=workerThreads.toString()
                    renderUpdate("Loading video models · ${spec.seconds}s clip · ${workerThreads} CPU threads",2)
                    val p=ProcessBuilder(args)
                        .directory(dir)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                        .start()
                    process.set(p)
                    if(stopRequested.get())throw CancellationException(stopMessage)

                    // Do not read Process.inputStream directly. Android can close that pipe from a
                    // different process-management thread, which caused RC1's
                    // InterruptedIOException: read interrupted by close() on another thread.
                    var logOffset=0L
                    fun consume(line:String){
                        if(line.startsWith("@@PID ")){
                            line.removePrefix("@@PID ").trim().toIntOrNull()?.takeIf{it>0}?.let{workerPid.set(it)}
                        }else if(line.startsWith("@@STAGE ")){
                            val message=line.removePrefix("@@STAGE ")
                            val pct=when {
                                message.startsWith("Loading Wan") -> 2
                                message.startsWith("Encoding reference photo") -> 6
                                message.startsWith("Encoding motion description") -> 10
                                message.startsWith("Motion description encoded") -> MotionProgressMath.SAMPLE_START
                                message.startsWith("Generating motion frames") -> MotionProgressMath.SAMPLE_START
                                message.startsWith("Decoding generated frames") -> MotionProgressMath.SAMPLE_END
                                message.startsWith("Finalizing generated frames") -> 97
                                message.startsWith("Video frames generated") -> 98
                                message.startsWith("Writing generated frames") -> 98
                                message.startsWith("Video frames ready") -> 98
                                else -> null
                            }
                            if(message.startsWith("Generating motion frames")&&samplingStartedAt==0L)
                                samplingStartedAt=SystemClock.elapsedRealtime()
                            if(message.startsWith("Decoding generated frames")&&samplingFinishedAt==0L)
                                samplingFinishedAt=SystemClock.elapsedRealtime()
                            renderUpdate(message,pct,if(message.startsWith("Decoding generated frames"))historicalPost.takeIf{it>0}else null)
                        }else if(line.startsWith("@@SAMPLE ")){
                            val a=line.split(' ')
                            val n=a.getOrNull(1)?.toIntOrNull()
                            val total=a.getOrNull(2)?.toIntOrNull()
                            if(n!=null&&total!=null&&total>0&&n in 0..total){
                                val now=SystemClock.elapsedRealtime()
                                if(samplingStartedAt==0L)samplingStartedAt=now
                                val eta=MotionProgressMath.remainingFromSampling(n,total,samplingStartedAt,now,historicalPost)
                                val label=if(eta==null&&n<2)
                                    "Generating motion · step $n of $total · calibrating ETA…"
                                else "Generating motion · step $n of $total"
                                renderUpdate(label,MotionProgressMath.samplingOverall(n,total),eta)
                            }
                        }
                    }
                    try{
                        while(p.isAlive){
                            ensureActive()
                            if(stopRequested.get())throw CancellationException(stopMessage)
                            if(SystemClock.elapsedRealtime()>=deadline){
                                stopMessage="Stopped after two hours to avoid an unbounded phone render"
                                stopRequested.set(true)
                                mutable.update{it.copy(status=stopMessage,expectedFinish=0,stopping=true)}
                                throw CancellationException(stopMessage)
                            }
                            logOffset=consumeLog(log,logOffset,::consume)
                            p.waitFor(500,TimeUnit.MILLISECONDS)
                        }
                        logOffset=consumeLog(log,logOffset,::consume)
                        ensureActive()
                        check(p.exitValue()==0){"Video worker exited with code ${p.exitValue()}"}
                    }finally{
                        if(p.isAlive)terminateWorker(p) else {workerPid.set(0);process.compareAndSet(p,null)}
                    }
                    stage="MP4 ENCODING";renderUpdate("Encoding ${spec.seconds}-second MP4…",98)
                    val videos=File(app.filesDir,"videos").apply{mkdirs()};val name="Rosalina-${System.currentTimeMillis()}"
                    val part=File(videos,"$name.mp4.part");tmpMp4=part
                    val mp4StartedAt=SystemClock.elapsedRealtime()
                    Mp4Encoder.encode(raw,part,spec){pct->
                        val now=SystemClock.elapsedRealtime()
                        val remaining=if(pct>=5){
                            val spent=now-mp4StartedAt
                            spent*(100-pct)/pct
                        }else null
                        renderUpdate("Encoding MP4 · $pct%",MotionProgressMath.mp4Overall(pct),remaining)
                    }
                    ensureActive()
                    val result=File(videos,"$name.mp4");check(part.renameTo(result)){"Could not save completed MP4"};committed=true
                    val finishedAt=SystemClock.elapsedRealtime()
                    val renderStarted=state.value.started
                    val totalMs=maxOf(0L,finishedAt-renderStarted)
                    val postMs=if(samplingFinishedAt>0L)maxOf(0L,finishedAt-samplingFinishedAt) else 0L
                    recordBaseline(spec,totalMs,postMs)
                    File(videos,"$name.json").writeText(JSONObject().put("prompt",prompt).put("seconds",spec.seconds).put("fps",8).put("width",spec.width).put("height",spec.height)
                        .put("steps",spec.steps).put("seed",spec.seed).put("generatedFrames",spec.modelFrames).put("exportFrames",spec.exportFrames).put("backend","cpu")
                        .put("renderMs",totalMs).put("postSamplingMs",postMs).toString(2))
                    prefs.edit().putString("result",result.path).apply()
                    mutable.update{it.copy(result=result.path,status="Your ${spec.seconds}-second clip is ready · ${MotionProgressMath.formatDuration(totalMs/1000)} total",progress=100,expectedFinish=0)}
                }}
            }catch(e:TimeoutCancellationException){
                stopMessage="Stopped after two hours to avoid an unbounded phone render"
                stopRequested.set(true)
                mutable.update{it.copy(status=stopMessage,expectedFinish=0,stopping=true)}
            }
            catch(e:CancellationException){mutable.update{it.copy(status=stopMessage,expectedFinish=0,stopping=true)}}
            catch(e:Exception){if(stopRequested.get())mutable.update{it.copy(status=stopMessage,expectedFinish=0,stopping=true)} else fail(stage,e,tail(log))}
            finally{
                withContext(NonCancellable+Dispatchers.IO){
                    process.getAndSet(null)?.let{terminateWorker(it)}
                    workerPid.set(0)
                    if(log.isFile)runCatching{log.copyTo(File(app.filesDir,"last-native.log"),overwrite=true)}
                    if(!committed)tmpMp4?.delete()
                    dir.deleteRecursively()
                }
                val stopped=state.value.stopping||stopRequested.get()
                val finalStatus=if(stopped)stopMessage else state.value.status
                if(stopJob?.isActive!=true){
                    stopRequested.set(false)
                    mutable.update{it.copy(busy=false,work="",progress=null,expectedFinish=0,stopping=false,status=finalStatus,thermal="")}
                    app.stopService(Intent(app,MotionService::class.java))
                }
                job=null
            }
        }
    }
    private fun consumeLog(file:File,start:Long,consumer:(String)->Unit):Long {
        if(!file.exists()) return start
        return RandomAccessFile(file,"r").use { r ->
            val safeStart=start.coerceAtMost(r.length())
            r.seek(safeStart)
            while(true){
                val line=r.readLine()?:break
                consumer(line)
            }
            r.filePointer
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
            canvas.drawBitmap(original,null,RectF((s.width-w)/2,(s.height-h)/2,(s.width+w)/2,(s.height+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(s.width*s.height);target.getPixels(pixels,0,s.width,0,0,s.width,s.height)
            FileOutputStream(destination).buffered().use{o->for(p in pixels){o.write((p shr 16) and 255);o.write((p shr 8) and 255);o.write(p and 255)}}
        }finally{original.recycle();target.recycle()}
    }
    fun history():List<File> = File(app.filesDir,"videos").listFiles()?.filter{it.extension=="mp4"}?.sortedByDescending{it.lastModified()}?.take(12)?:emptyList()
    fun openResult(file:File){if(!state.value.busy&&file.isFile)mutable.update{it.copy(result=file.path)}}
}
