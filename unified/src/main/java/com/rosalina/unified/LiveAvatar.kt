package com.rosalina.unified

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Base64
import android.view.Choreographer
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

internal enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED, BUSY, ERROR }

internal object AvatarStateResolver {
    fun resolve(s: TaskState): AvatarState {
        if (s.stopping) return AvatarState.INTERRUPTED
        if (!s.busy) return if (s.error.isNotBlank() || s.quarantined) AvatarState.ERROR else AvatarState.IDLE
        val stage = s.stage.lowercase(); val voice = s.voiceStage.lowercase()
        if ("interrupt" in stage || "interrupt" in voice) return AvatarState.INTERRUPTED
        if (s.avatarEnergy > .03f || "speaking" in voice) return AvatarState.SPEAKING
        if ("understanding" in stage || "transcrib" in stage || "preparing response" in stage ||
            "warming" in stage || "loading rosalina voice" in voice) return AvatarState.THINKING
        if (stage.startsWith("listening")) return AvatarState.LISTENING
        return when (s.kind) {
            TaskKind.CHAT, TaskKind.VOICE -> AvatarState.THINKING
            else -> AvatarState.BUSY
        }
    }
}

internal object AvatarAsset {
    @Volatile private var image: Bitmap? = null
    @Volatile var status = "Not decoded"; private set
    @Volatile var isTemplate=false;private set
    const val TEMPLATE_SHA256="3fd9526712304ab6c9411a4be90dbb7ed7e548d485215944b811694b2c272f40"
    fun templateAvailable(context:Context)=runCatching{context.assets.open("avatar-v3/approved-reference").close();true}.getOrDefault(false)
    private fun original(context:Context)=File(context.filesDir,"avatar/original-image")
    private fun decodeOriginal(file:File):Bitmap {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){decoder,info,_->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val longest=maxOf(info.size.width,info.size.height)
            if(longest>2048){
                val scale=2048f/longest
                decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
            }
        }
    }
    @Synchronized fun load(context: Context): Bitmap? {
        image?.let { return it }
        val errors=mutableListOf<String>()
        isTemplate=false
        val private=original(context)
        var decoded:Bitmap?=null;var label=""
        if(private.isFile)runCatching{decodeOriginal(private)}.onSuccess{decoded=it;label="Private original portrait (generic rig)"}
            .onFailure{errors+="Private portrait: ${it.message}"}
        if(decoded==null && templateAvailable(context))runCatching {
            val bytes=context.assets.open("avatar-v3/approved-reference").use{it.readBytes()}
            require(hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))==TEMPLATE_SHA256){"Reference artwork checksum mismatch"}
            val sheet=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?:error("Approved reference cannot decode")
            try {require(sheet.width>=1024 && sheet.height>=1244){"Reference crop dimensions changed"};Bitmap.createBitmap(sheet,0,0,594,1244)}finally{sheet.recycle()}
        }.onSuccess{decoded=it;isTemplate=true;label="Approved template hero crop"}.onFailure{errors+="Template: ${it.message}"}
        if(decoded==null)runCatching {
            val encoded=(0..1).joinToString(""){index->context.assets.open("avatar/$index.b64").bufferedReader().use{it.readText()}}
            val bytes=Base64.decode(encoded,Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?:error("Bundled clean portrait decode returned null")
        }.onSuccess{decoded=it;label="Bundled clean fallback portrait"}.onFailure{errors+="Fallback: ${it.message}"}
        image=decoded
        status=decoded?.let{"$label decoded: ${it.width}x${it.height}; local 2-D mesh; walking/turning assets not supplied"}
            ?:"Avatar unavailable"
        if(errors.isNotEmpty())status+="; "+errors.joinToString("; ")
        return decoded
    }
    /** Copies exact selected encoded bytes into private storage; only the in-memory display decode is sampled. */
    @Synchronized fun importOriginal(context:Context,uri:android.net.Uri):String {
        val folder=File(context.filesDir,"avatar").apply{mkdirs()}
        val target=original(context);val part=File(folder,"original-image.part")
        try {
            context.contentResolver.openInputStream(uri)?.use{input->
                FileOutputStream(part).use{out->
                    val buffer=ByteArray(65536);var total=0L
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=50_000_000L){"Avatar image is larger than 50 MB"};out.write(buffer,0,n)}
                    out.fd.sync()
                }
            } ?:error("Selected avatar image could not be opened")
            var sourceWidth=0;var sourceHeight=0
            val probe=ImageDecoder.decodeBitmap(ImageDecoder.createSource(part)){decoder,info,_->
                sourceWidth=info.size.width;sourceHeight=info.size.height
                require(sourceWidth>=400 && sourceHeight>=400){"Choose the original high-resolution Rosalina portrait (at least 400 px in both dimensions)"}
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                val longest=maxOf(sourceWidth,sourceHeight);val scale=minOf(1f,256f/longest)
                decoder.setTargetSize((sourceWidth*scale).toInt().coerceAtLeast(1),(sourceHeight*scale).toInt().coerceAtLeast(1))
            };probe.recycle()
            android.system.Os.rename(part.path,target.path)
            check(target.isFile){"Could not finalize the private avatar image"}
            image=null;status="Original portrait saved: ${sourceWidth}x${sourceHeight}; reload pending"
            return "Original Rosalina portrait saved privately · ${sourceWidth}×${sourceHeight}"
        } finally { part.delete() }
    }
    @Synchronized fun restoreBundled(context:Context):String {
        original(context).delete();image=null;status="Bundled portrait reload pending"
        return "Bundled Rosalina portrait restored"
    }
    fun hasPrivateOriginal(context:Context)=original(context).isFile
}

internal class LiveAvatarView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs) {
    private var renderer:LayeredAvatarRenderer?=null
    private var asset:RigAssets?=null
    private var failure:String?=null
    private var fallback:Bitmap?=null
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val budget=MotionBudget()
    private var motionEnabled=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).getBoolean("live-avatar",true)
    private var tier=if(motionEnabled)MotionTier.NORMAL else MotionTier.STATIC
    private var snapshot=CompanionSnapshot()
    private var active=true
    private var running=false
    private var lastFrame=0L
    internal var renderedFrames=0L;private set
    internal val imageLoaded:Boolean get()=asset!=null || fallback!=null
    internal val sourceWidth:Int get()=if(asset!=null)594 else fallback?.width ?:0
    internal val sourceHeight:Int get()=if(asset!=null)1082 else fallback?.height ?:0
    internal val motionRunning:Boolean get()=running
    internal fun qualitySummary()="layers=${asset?.layers?.size ?:0}; ${asset?.description ?: "loading/error"}; texture memory=${asset?.bytes ?:0}; fps=${tier.fps}; frames=$renderedFrames; scene-distance views are small source assets, not HD; failure=${failure ?: "none"}"
    init {RigAssets.request(context,this)}
    internal fun artworkReady(value:RigAssets?,error:String?) {
        asset=value;failure=error;renderer=value?.let{LayeredAvatarRenderer(it)}
        if(value==null){
            // A verified asset failure is the only normal route to the emergency bitmap.
            Thread {val image=AvatarAsset.load(context.applicationContext);post{fallback=image;updateClock();invalidate()}}.start()
        }
        updateClock();invalidate()
    }
    private val frame=object:Choreographer.FrameCallback {
        override fun doFrame(time:Long){
            if(!running)return
            if(!canAnimate()){running=false;return}
            val interval=1_000_000_000L/tier.fps.coerceAtLeast(1)
            if(time-lastFrame>=interval){lastFrame=time;invalidate()}
            Choreographer.getInstance().postFrameCallbackDelayed(this,(1000L/tier.fps.coerceAtLeast(1)-2).coerceAtLeast(16))
        }
    }
    private fun canAnimate()=active && motionEnabled && isAttachedToWindow && isShown && windowVisibility==VISIBLE && renderer!=null && tier.fps>0 && ValueAnimator.areAnimatorsEnabled()
    private fun updateClock(){val next=canAnimate();if(next==running)return;running=next
        if(next){lastFrame=0;Choreographer.getInstance().postFrameCallback(frame)}else Choreographer.getInstance().removeFrameCallback(frame)
    }
    fun setActive(value:Boolean){active=value;updateClock();if(value)invalidate()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateClock()}
    override fun onDetachedFromWindow(){running=false;Choreographer.getInstance().removeFrameCallback(frame);super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(isAttachedToWindow)updateClock()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(isAttachedToWindow)updateClock()}
    fun bind(state:TaskState,presentation:CompanionSnapshot?=null){
        val old=snapshot;snapshot=presentation ?:CompanionSnapshot()
        motionEnabled=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).getBoolean("live-avatar",true)
        tier=budget.sample(state.thermal,motionEnabled,SystemClock.elapsedRealtime());updateClock()
        if(old.phase!=snapshot.phase || old.expression!=snapshot.expression || old.utteranceId!=snapshot.utteranceId || !running)invalidate()
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);renderedFrames++
        renderer?.let{it.draw(canvas,width,height,RigMotion.sample(snapshot,tier,SystemClock.elapsedRealtime(),active && motionEnabled && ValueAnimator.areAnimatorsEnabled()));return}
        canvas.drawColor(Color.rgb(10,11,24))
        fallback?.let{b->val scale=min(width.toFloat()/b.width,height.toFloat()/b.height);val w=b.width*scale;val h=b.height*scale
            canvas.drawBitmap(b,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),imagePaint)}
        if(failure!=null){imagePaint.color=Color.rgb(220,210,232);imagePaint.textSize=12*resources.displayMetrics.density
            canvas.drawText("Artwork recovery mode · see Diagnostics",12f*resources.displayMetrics.density,28f*resources.displayMetrics.density,imagePaint)}
    }
}
