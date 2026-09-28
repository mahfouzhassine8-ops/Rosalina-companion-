package com.rosalina.unified

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.*
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sqrt

class RosalinaApp:Application() {
    override fun onCreate(){super.onCreate();if(getProcessName()==packageName)Session.get(this)}
}
internal class Session private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:Session?=null
        fun get(context:Context):Session=instance ?: synchronized(this){instance ?: Session(context.applicationContext).also{instance=it}}
        const val DEFAULT_SYSTEM="You are Rosalina, a private on-device assistant. Be helpful, practical, direct, and clear. Do not claim internet access. The application can route explicit create, edit and animate requests to its local engines."
    }
    val prefs=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE)
    val models=ModelStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val thermal=ThermalManager(context)
    private val lease=EngineLease()
    private val chat=EngineRpc(context,ChatService::class.java)
    private val speech=EngineRpc(context,SpeechService::class.java)
    private val transcriptFile=File(context.filesDir,"conversation.json")
    private val turns=mutableListOf<Pair<String,String>>()
    private val mutable=MutableStateFlow(TaskState(result=prefs.getString("result","").orEmpty(),stage=if(prefs.getString("active-id","").isNullOrBlank())"Ready" else "Previous work was interrupted by Android. No task was restarted."))
    val state:StateFlow<TaskState> = mutable.asStateFlow()
    @Volatile private var request:TaskRequest?=null
    private var activeJob:Job?=null
    @Volatile private var stopReason=""
    @Volatile var finishListening=false
    private var speechMetrics=""
    init {
        runCatching {val a=JSONArray(transcriptFile.readText());for(i in 0 until a.length()){val o=a.getJSONObject(i);turns.add(o.getString("role") to o.getString("text"))}}
        prefs.edit().remove("active-id").apply();refreshResources()
    }
    @Synchronized fun transcript():List<Pair<String,String>> = turns.toList()
    @Synchronized private fun addTurn(role:String,text:String) {
        turns.add(role to text)
        val a=JSONArray();turns.forEach{(r,t)->a.put(JSONObject().put("role",r).put("text",t))};atomicText(transcriptFile,a.toString())
    }
    fun clearConversation(){if(state.value.busy)return;scope.launch{chat.shutdown();synchronized(this@Session){turns.clear();atomicText(transcriptFile,"[]")};mutable.update{it.copy(answer="",stage="Conversation cleared")}}}
    private fun update(id:String,change:(TaskState)->TaskState){mutable.update{if(it.id==id)change(it)else it}}
    fun notice(text:String){mutable.update{it.copy(stage=text)}}
    fun refreshResources(){val r=thermal.read();mutable.update{it.copy(thermal=r.thermal,thermalAt=r.measuredAt,availableBytes=r.available,totalBytes=r.total)}}
    @Synchronized fun begin(r:TaskRequest):Boolean {
        if(state.value.quarantined){notice("Force-stop Rosalina before starting another native worker");return false}
        if(!lease.acquire(r.id))return false
        request=r;stopReason="";finishListening=false
        mutable.value=TaskState(id=r.id,kind=r.kind,busy=true,stage="Preparing",result=state.value.result,eta=if(r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE))"ETA calibrating…" else "")
        prefs.edit().putString("active-id",r.id).commit()
        try {ContextCompat.startForegroundService(context,Intent(context,GenerationService::class.java).putExtra("task",r.id))}
        catch(t:Throwable){lease.release(r.id);request=null;prefs.edit().remove("active-id").apply();mutable.update{it.copy(busy=false,error=t.toString(),stage="Android could not start foreground work")};return false}
        return true
    }
    fun currentRequest()=request
    fun stop(reason:String="Stopped by you") {
        if(!state.value.busy)return
        stopReason=reason;mutable.update{it.copy(stopping=true,stage="Stopping…",percent=null,eta="")};activeJob?.cancel(CancellationException(reason))
    }
    fun interruptAndListen() {
        scope.launch {
            if(state.value.busy){stop();withTimeoutOrNull(8000){activeJob?.join()}}
            if(!state.value.busy)begin(TaskRequest(kind=TaskKind.VOICE)) else notice("The previous task is still releasing its engine")
        }
    }
    fun launchPending(service:GenerationService,taskId:String) {
        val r=request ?: run{service.finishTask();return}
        if(r.id!=taskId || activeJob?.isActive==true)return
        if(state.value.stopping){finish(r,service,null);return}
        val job=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var failure:Throwable?=null;val started=SystemClock.elapsedRealtime()
            val ticker=scope.launch(Dispatchers.IO) {
                while(isActive && state.value.id==r.id && state.value.busy) {
                    val resources=thermal.read()
                    update(r.id){it.copy(elapsedMs=SystemClock.elapsedRealtime()-started,availableBytes=resources.available,totalBytes=resources.total,thermal=resources.thermal,thermalAt=resources.measuredAt)}
                    if(ThermalPolicy.blocks(resources.thermal) && r.kind!=TaskKind.IMPORT) {stop("Stopped safely: Android reported ${ThermalPolicy.label(resources.thermal)} heat");break}
                    delay(1000)
                }
            }
            try {
                when(r.kind) {
                    TaskKind.IMPORT->{chat.shutdown();speech.shutdown();models.import(ModelKey.valueOf(r.modelKey),Uri.parse(r.uri)){text,p->update(r.id){it.copy(stage=text,percent=p)}}}
                    TaskKind.CHAT->performChat(r,r.prompt,prefs.getBoolean("spoken-replies",false))
                    TaskKind.VOICE->performVoice(r)
                    else->render(r)
                }
            } catch(t:Throwable){failure=t}
            finally {
                ticker.cancel()
                withContext(NonCancellable) {
                    speech.shutdown()
                    if(failure!=null || r.kind !in listOf(TaskKind.CHAT,TaskKind.VOICE))chat.shutdown()
                    finish(r,service,failure)
                }
            }
        }
        activeJob=job;job.start()
    }
    private fun finish(r:TaskRequest,service:GenerationService,failure:Throwable?) {
        val message=when {failure is CancellationException->stopReason.ifBlank{"Stopped"};failure!=null->failure.message ?: failure.javaClass.simpleName;state.value.result.isNotBlank() && r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE)->"Ready · saved privately";else->"Ready"}
        update(r.id){it.copy(busy=false,stopping=false,stage=message,percent=null,step=0,total=0,eta="",pid=0,error=if(failure!=null && failure !is CancellationException)failure.stackTraceToString()else it.error,quarantined=failure is WorkerQuarantined)}
        runCatching{atomicText(File(context.filesDir,"last-diagnostics.txt"),diagnostics())}
        if(lease.release(r.id)){request=null;prefs.edit().remove("active-id").apply()}
        service.finishTask()
    }
    private suspend fun performChat(r:TaskRequest,prompt:String,readAloud:Boolean)=coroutineScope {
        require(prompt.isNotBlank() && prompt.length<=8000){"Use a prompt between 1 and 8,000 characters"}
        val model=models.requirePath(ModelKey.CHAT);val history=SpeechText.history(transcript());addTurn("You",prompt)
        val queue=Channel<String>(4)
        val speechJob=if(readAloud)launch {
            var unavailable=false
            for(text in queue) {
                if(unavailable)continue
                try {
                    val result=speech.call(Bundle().apply{putString("operation","speak");putString("text",text);putInt("speaker",prefs.getInt("speaker",3));putFloat("speed",prefs.getFloat("speed",1f))}){event->
                        if(event.getString("type")=="stage")update(r.id){it.copy(voiceStage=event.getString("text").orEmpty())}
                    }
                    speechMetrics="Kokoro: first audio ${result.getLong("firstAudioMs")} ms; output ${result.getLong("audioMs")} ms; elapsed ${result.getLong("elapsedMs")} ms"
                } catch(e:CancellationException){throw e}
                catch(t:Throwable){unavailable=true;update(r.id){it.copy(voiceStage="Voice unavailable · ${t.message}",error="Speech: ${t.stackTraceToString()}")};speech.shutdown()}
            }
        } else null
        val answer=StringBuilder();var spoken=0
        fun visible()=answer.toString().replace(Regex("<think>.*?(?:</think>|$)",RegexOption.DOT_MATCHES_ALL),"").replace(Regex("```.*?(?:```|$)",RegexOption.DOT_MATCHES_ALL),"").replace(Regex("[\\*`#]"),"")
        try {
            chat.call(Bundle().apply{putString("model",model.path);putString("prompt",prompt);putString("system",prefs.getString("system",DEFAULT_SYSTEM));putString("history",history)}) {event->
                when(event.getString("type")) {
                    "stage"->update(r.id){it.copy(stage=event.getString("text").orEmpty(),pid=event.getInt("pid"),backend="Qwen · CPU · isolated chat process")}
                    "token"->{
                        answer.append(event.getString("text").orEmpty());update(r.id){it.copy(answer=answer.toString(),pid=event.getInt("pid"))}
                        if(readAloud){val v=visible();if(spoken<=v.length){val left=v.substring(spoken);val cut=SpeechText.cut(left);if(cut>0){queue.send(left.substring(0,cut).trim());spoken+=cut}}}
                    }
                }
            }
            if(readAloud){val v=visible();if(spoken<v.length)queue.send(v.substring(spoken).trim())}
        } finally {
            queue.close()
            if(answer.isNotBlank())addTurn("Rosalina",answer.toString())
        }
        speechJob?.join();update(r.id){it.copy(answer="",voiceStage="")}
    }
    private suspend fun performVoice(r:TaskRequest) {
        require(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Microphone permission is required for Voice"}
        models.requirePath(ModelKey.STT);models.requirePath(ModelKey.TTS);models.requirePath(ModelKey.CHAT)
        speech.shutdown();update(r.id){it.copy(stage="Listening · tap Finish when done",backend="Microphone · local PCM")}
        val dir=File(context.filesDir,"voice-input").apply{mkdirs()};val pcm=File(dir,"${r.id}.pcm")
        val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val record=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,6400))
        try {
            require(record.state==AudioRecord.STATE_INITIALIZED){"Microphone could not be initialized"}
            record.startRecording();val buffer=ShortArray(640);var count=0;var voiced=false;var silence=0
            FileOutputStream(pcm).use{out->
                while(count<480000 && !finishListening) {
                    currentCoroutineContext().ensureActive();val n=record.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING);require(n>0){"Microphone read failed: $n"}
                    val bytes=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN);bytes.asShortBuffer().put(buffer,0,n);out.write(bytes.array());count+=n
                    val energy=sqrt((0 until n).sumOf{buffer[it].toDouble()*buffer[it]}/n)
                    if(energy>650){voiced=true;silence=0}else if(voiced)silence+=n
                    if(voiced && silence>20000 && count>16000)break
                };out.fd.sync()
            }
            record.stop()
            update(r.id){it.copy(stage="Transcribing on device",backend="Whisper tiny.en · CPU")}
            val result=speech.call(Bundle().apply{putString("operation","transcribe");putString("pcm",pcm.path)}){event->if(event.getString("type")=="stage")update(r.id){it.copy(stage=event.getString("text").orEmpty(),pid=event.getInt("pid"))}}
            speechMetrics="Whisper: ${result.getLong("inferenceMs")} ms for ${result.getLong("audioMs")} ms audio"
            speech.shutdown()
            val text=result.getString("transcript").orEmpty();val routed=Route.kind(text)
            if(routed==TaskKind.CHAT)performChat(r,text,true) else {
                addTurn("You",text)
                val routedRequest=r.copy(kind=routed,prompt=text,photo=prefs.getString("photo","").orEmpty(),seconds=Route.seconds(text))
                render(routedRequest)
            }
        } finally {runCatching{record.stop()};record.release();pcm.delete()}
    }
    private suspend fun render(r:TaskRequest) {
        chat.shutdown();speech.shutdown()
        val resources=thermal.read()
        require(!resources.low){"Android reports low memory. Available RAM: ${String.format("%.2f",resources.available/1e9)} GB. Close other large applications before retrying."}
        require(!ThermalPolicy.blocks(resources.thermal)){"Android thermal status: ${ThermalPolicy.label(resources.thermal)}"}
        require(r.prompt.isNotBlank()){"Describe what Rosalina should make"}
        val video=r.kind==TaskKind.ANIMATE;val edit=r.kind==TaskKind.EDIT
        val w=if(video)r.width else r.profile.width;val h=if(video)r.height else r.profile.height
        require(r.seconds in listOf(6,8,10));require(r.strength in .1f.. .9f)
        val steps=if(video)12 else r.profile.steps
        val threads=if(resources.thermal==2)2 else if(video)2 else r.profile.threads
        val dir=File(context.filesDir,"jobs/${r.id}").apply{mkdirs()}
        var quarantine=false
        try {
            File(dir,"prompt.txt").writeText(r.prompt);File(dir,"negative.txt").writeText("")
            val input=if(video || edit){require(r.photo.isNotBlank()){"Choose a photo first"};prepareRgb(File(r.photo),File(dir,"input.rgb"),w,h);File(dir,"input.rgb").path}else "-"
            val output=File(dir,if(video)"output.rvf" else "output.rimg")
            val args=if(video)listOf(models.requirePath(ModelKey.VIDEO).path,models.requirePath(ModelKey.VIDEO_TEXT).path,decoder().path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,input,output.path,w.toString(),h.toString(),(r.seconds*8+1).toString(),steps.toString(),r.seed.toString(),threads.toString())
                else listOf(models.requirePath(ModelKey.IMAGE).path,File(dir,"prompt.txt").path,File(dir,"negative.txt").path,input,output.path,w.toString(),h.toString(),steps.toString(),r.seed.toString(),r.strength.toString(),threads.toString())
            val engine=if(video)"motion" else "image";var sampled=false
            suspend fun runBackend(backend:String) {
                update(r.id){it.copy(stage="Preparing",backend=if(backend=="cpu")"CPU" else "Vulkan candidate · awaiting native initialization",percent=null)}
                val exe=File(context.applicationInfo.nativeLibraryDir,"librosalina-$engine${if(backend=="cpu")"" else "-vulkan"}.so")
                NativeWorker(thermal).run(listOf(exe.path)+args,dir,steps){stage,percent,step,total,pid,tail,res->
                    if(step>0)sampled=true
                    val assigned=Regex("@@BACKEND ([^\\r\\n]+)").findAll(tail).lastOrNull()?.groupValues?.get(1)
                    update(r.id){it.copy(stage=stage,percent=percent,step=step,total=total,pid=pid,logTail=tail,backend=assigned ?: it.backend,availableBytes=res.available,totalBytes=res.total,thermal=res.thermal,thermalAt=res.measuredAt)}
                }
            }
            try{runBackend(r.backend)}catch(e:CancellationException){throw e}catch(e:WorkerQuarantined){quarantine=true;throw e}catch(t:Throwable){
                if(r.backend!="cpu" && !sampled && !ThermalPolicy.blocks(thermal.read().thermal)) {
                    update(r.id){it.copy(stage="GPU initialization failed · retrying on CPU",error="Vulkan initialization: ${t.message}\n${it.logTail}")}
                    output.delete();runBackend("cpu")
                }else throw t
            }
            currentCoroutineContext().ensureActive()
            update(r.id){it.copy(stage=if(video)"Saving MP4" else "Saving image",percent=null,pid=0)}
            val results=File(context.filesDir,"results").apply{mkdirs()};val result=File(results,"Rosalina-${System.currentTimeMillis()}.${if(video)"mp4" else "png"}")
            val part=File(result.path+".part")
            try {
                if(video) {
                    val spec=MotionSpec(r.seconds,w,h,steps,r.seed)
                    Mp4Encoder.encode(output,part,spec){percent->update(r.id){it.copy(stage="Saving MP4 · encoding frames",percent=percent)}}
                    verifyVideo(part,spec)
                } else saveImage(output,part,w,h)
                currentCoroutineContext().ensureActive();check(part.renameTo(result)){"Could not finalize generated result"}
            } finally {part.delete()}
            atomicText(File(result.path+".json"),JSONObject().put("request",r.toString()).put("backend",state.value.backend).put("nativeSource","3f8527a46c54ecf4cb4ed6003da8e8982283c73c").put("device",Build.MODEL).put("elapsedMs",state.value.elapsedMs).toString(2))
            prefs.edit().putString("result",result.path).apply();update(r.id){it.copy(result=result.path,stage="Ready · saved privately",percent=null)}
        } catch(e:WorkerQuarantined){quarantine=true;throw e} finally {if(!quarantine)dir.deleteRecursively()}
    }
    private suspend fun prepareRgb(photo:File,target:File,w:Int,h:Int) {
        currentCoroutineContext().ensureActive()
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(photo.path,options)
        require(options.outWidth>0 && options.outHeight>0){"Selected photo cannot be decoded"}
        options.inJustDecodeBounds=false;options.inSampleSize=maxOf(1,minOf(options.outWidth/w,options.outHeight/h))
        val source=BitmapFactory.decodeFile(photo.path,options) ?: error("Selected photo cannot be decoded")
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val scale=maxOf(w.toFloat()/source.width,h.toFloat()/source.height);val x=(w-source.width*scale)/2;val y=(h-source.height*scale)/2
            Canvas(bitmap).drawBitmap(source,null,RectF(x,y,x+source.width*scale,y+source.height*scale),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
            FileOutputStream(target).buffered().use{out->for(p in pixels){out.write((p shr 16)and 255);out.write((p shr 8)and 255);out.write(p and 255)}}
        } finally {source.recycle();bitmap.recycle()}
    }
    private fun saveImage(raw:File,target:File,w:Int,h:Int) {
        require(raw.length()==16L+w.toLong()*h*3){"Image output is incomplete"}
        val bytes=raw.readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x474d4952 && b.int==w && b.int==h && b.int==3){"Unexpected native image header"}
        val pixels=IntArray(w*h){i->val p=16+i*3;Color.rgb(bytes[p].toInt()and 255,bytes[p+1].toInt()and 255,bytes[p+2].toInt()and 255)}
        val image=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        try{FileOutputStream(target).use{check(image.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{image.recycle()}
    }
    private fun verifyVideo(file:File,spec:MotionSpec) {
        val e=MediaExtractor()
        try {
            e.setDataSource(file.path);val track=(0 until e.trackCount).firstOrNull{e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true} ?: error("Generated MP4 has no video track")
            e.selectTrack(track);var count=0;var previous=-1L
            while(e.sampleTime>=0){val now=e.sampleTime;require(now>previous){"MP4 timestamps are not increasing"};previous=now;count++;if(!e.advance())break}
            require(count==spec.exportFrames){"MP4 frame count mismatch: $count instead of ${spec.exportFrames}"}
        } finally {e.release()}
    }
    private suspend fun decoder():File {
        val expected="b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325"
        val file=File(context.filesDir,"models/decoder/taew2_2.safetensors");file.parentFile?.mkdirs()
        fun digest(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{input->val b=ByteArray(262144);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};return hex(md.digest())}
        if(file.exists() && digest(file)==expected)return file
        val part=File(file.path+".part")
        try{context.assets.open("taew2_2.safetensors").use{input->FileOutputStream(part).use{input.copyTo(it);it.fd.sync()}};require(digest(part)==expected){"Bundled video decoder checksum mismatch"};check(part.renameTo(file))}finally{part.delete()}
        return file
    }
    fun importPhoto(uri:Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val source=ImageDecoder.createSource(context.contentResolver,uri)
                val bitmap=ImageDecoder.decodeBitmap(source){decoder,info,_->decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;val max=maxOf(info.size.width,info.size.height);if(max>2048)decoder.setTargetSize(info.size.width*2048/max,info.size.height*2048/max)}
                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.png");file.parentFile?.mkdirs()
                try {FileOutputStream(file).use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync()}}finally{bitmap.recycle()}
                prefs.edit().putString("photo",file.path).apply();notice("Photo selected")
            } catch(t:Throwable){notice("Photo import failed: ${t.message}")}
        }
    }
    fun diagnostics():String {
        val s=state.value
        return "ROSALINA UNIFIED CANDIDATE\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage: ${context.packageName}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.SDK_INT}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nRAM total: ${s.totalBytes}; available: ${s.availableBytes}\nThermal: ${s.thermal} ${ThermalPolicy.label(s.thermal)}; sampled elapsedRealtime=${s.thermalAt}\nTask: ${s.id} ${s.kind}; stage: ${s.stage}; worker PID: ${s.pid}\nBackend: ${s.backend}\nProgress: ${s.step}/${s.total}; percent=${s.percent}; elapsed=${s.elapsedMs} ms\nETA: ${s.eta}\nVoice: ${s.voiceStage}\n$speechMetrics\n${models.diagnostic()}\nError: ${s.error}\nNative log tail:\n${s.logTail}\nReal Samsung output acceptance: NOT VERIFIED BY BUILD\n"
    }
}
