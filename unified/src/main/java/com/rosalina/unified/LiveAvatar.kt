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

internal class LiveAvatarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val bitmap = AvatarAsset.load(context)
    private val template=AvatarAsset.isTemplate
    internal val imageLoaded: Boolean get() = bitmap != null
    internal val sourceWidth: Int get() = bitmap?.width ?: 0
    internal val sourceHeight: Int get() = bitmap?.height ?: 0
    private val rig = AvatarRig()
    private val templateRig=TemplateRig()
    private val motionBudget=MotionBudget()
    private var tier=MotionTier.NORMAL
    private var presentation:CompanionSnapshot?=null
    private val privacyCover=Paint().apply{color=Color.rgb(11,12,25)}
    internal fun qualitySummary()="source=${sourceWidth}x${sourceHeight}; tier=$tier; target fps=${tier.fps}; rendered frames=$renderedFrames; exact walking/turning clips unavailable"
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint().apply { color = Color.rgb(15, 11, 23) }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.25f * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * density; typeface = Typeface.DEFAULT_BOLD }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(213, 201, 232); textSize = 11f * density }
    private val clip = RectF()
    private val destination = RectF()
    private val clipPath = Path()
    private var state = AvatarState.IDLE
    private var targetEnergy = 0f
    private var energy = 0f
    private var thermal = -1
    private val origin = SystemClock.uptimeMillis()
    private var active = true
    private var running = false
    private var lastFrame = 0L
    internal var renderedFrames = 0L; private set
    internal val motionRunning: Boolean get() = running
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (!canAnimate()) { running = false; return }
            val interval = 1_000_000_000L / tier.fps.coerceAtLeast(1)
            if (frameTimeNanos - lastFrame >= interval) { lastFrame = frameTimeNanos; invalidate() }
            Choreographer.getInstance().postFrameCallbackDelayed(this,(1000L/tier.fps.coerceAtLeast(1)-2).coerceAtLeast(16))
        }
    }

    private fun canAnimate() = active && isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
        ValueAnimator.areAnimatorsEnabled() && bitmap != null && tier.fps>0
    private fun updateClock() {
        val next = canAnimate()
        if (next == running) return
        running = next
        if (next) { lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame) }
        else Choreographer.getInstance().removeFrameCallback(frame)
        invalidate()
    }
    fun setActive(value: Boolean) { active = value; updateClock() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateClock() }
    override fun onDetachedFromWindow() { running = false; Choreographer.getInstance().removeFrameCallback(frame); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (isAttachedToWindow) updateClock() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isAttachedToWindow) updateClock() }

    fun bind(task: TaskState, events:CompanionSnapshot?=null) {
        val previousExpression=presentation?.expression
        presentation=events
        tier=motionBudget.sample(task.thermal,true,SystemClock.elapsedRealtime())
        val next = if(events==null)AvatarStateResolver.resolve(task) else when(events.phase){
            CompanionPhase.IDLE->if(task.error.isNotBlank())AvatarState.ERROR else AvatarState.IDLE
            CompanionPhase.LISTENING->AvatarState.LISTENING;CompanionPhase.THINKING->AvatarState.THINKING
            CompanionPhase.SPEAKING->AvatarState.SPEAKING;CompanionPhase.INTERRUPTED->AvatarState.INTERRUPTED}

        val changed = state != next || previousExpression!=events?.expression || (tier!=MotionTier.STATIC && targetEnergy != (events?.energy ?:task.avatarEnergy))
        if (state != next) { state = next; contentDescription = "Rosalina live avatar · ${state.name.lowercase()}" }
        targetEnergy = (events?.energy ?:task.avatarEnergy).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if(events!=null && !events.playbackActive){targetEnergy=0f;energy=0f}
        thermal = task.thermal
        updateClock()
        if (changed && !running) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 5f * density
        clip.set(pad, pad, w - pad, h - pad)
        clipPath.reset(); clipPath.addRoundRect(clip, 20f * density, 20f * density, Path.Direction.CW)
        bitmap?.let {
            val scale = min(clip.width() / it.width, clip.height() / it.height).coerceAtLeast(0f)
            val dw = it.width * scale; val dh = it.height * scale
            destination.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        }
        fadePaint.shader = LinearGradient(0f, h * .60f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(238, 13, 10, 19), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Subtract Long timestamps BEFORE conversion: large device uptimes otherwise lose sub-frame precision.
        renderFrame(canvas, (SystemClock.uptimeMillis() - origin) / 1000f, running)
    }

    /** Same renderer used by the screen and deterministic Android pixel regression tests. */
    internal fun renderFrame(canvas: Canvas, seconds: Float, motion: Boolean = true) {
        if (width <= 1 || height <= 1) return
        renderedFrames++
        energy += (targetEnergy - energy) * .28f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        bitmap?.let { bmp ->
            canvas.save(); canvas.clipPath(clipPath)
            if(template){
                val expression=when(state){AvatarState.LISTENING->RosalinaExpression.LISTENING;AvatarState.THINKING->RosalinaExpression.THINKING;else->presentation?.expression ?:RosalinaExpression.NEUTRAL}
                templateRig.update(seconds,energy,expression,destination.left,destination.top,destination.width(),destination.height(),motion,tier.secondary)
                canvas.drawBitmapMesh(bmp,templateRig.columns,templateRig.rows,templateRig.vertices,0,null,0,imagePaint)
                // The reference sheet has obsolete navigation and buttons baked into its pixels.
                // Cover only those UI rectangles; never present the old media tools as app options.
                fun cover(l:Float,t:Float,r:Float,b:Float){canvas.drawRoundRect(destination.left+l/594f*destination.width(),destination.top+t/1244f*destination.height(),destination.left+r/594f*destination.width(),destination.top+b/1244f*destination.height(),12f*density,12f*density,privacyCover)}
                cover(13f,237f,173f,524f);cover(85f,1096f,492f,1223f)
            } else {
                rig.update(seconds, energy, state == AvatarState.LISTENING, state == AvatarState.THINKING,
                    destination.left, destination.top, destination.width(), destination.height(), motion)
                canvas.drawBitmapMesh(bmp, rig.columns, rig.rows, rig.vertices, 0, null, 0, imagePaint)
            }
            canvas.drawRect(0f, height * .56f, width.toFloat(), height.toFloat(), fadePaint)
            canvas.restore()
        }
        borderPaint.color = Color.argb(if (state == AvatarState.IDLE || state == AvatarState.ERROR) 100 else 205, 194, 166, 255)
        canvas.drawRoundRect(.7f * density, .7f * density, width - .7f * density, height - .7f * density, 20f * density, 20f * density, borderPaint)
        val title = when (state) {
            AvatarState.IDLE -> "Rosalina"; AvatarState.LISTENING -> "Listening"; AvatarState.THINKING -> "Thinking"
            AvatarState.SPEAKING -> "Speaking"; AvatarState.INTERRUPTED -> "Interrupted"; AvatarState.BUSY -> "Working"; AvatarState.ERROR -> "Needs attention"
        }
        val detail = when {
            bitmap == null -> "Avatar unavailable · open Diagnostics"
            state == AvatarState.ERROR -> "Check the status message"
            !ValueAnimator.areAnimatorsEnabled() -> "System animations are off"
            state == AvatarState.SPEAKING -> if(presentation?.energySource=="unavailable")"Playback active · energy unavailable" else "Playback-linked mouth motion"
            tier.ordinal>=MotionTier.REDUCED.ordinal -> "Reduced motion"
            else -> "Local 2-D portrait"
        }
        // Keep state text above the bottom Companion glass instead of drawing it behind controls.
        canvas.drawText(title, clip.left + 14f * density, clip.top + 27f * density, labelPaint)
        canvas.drawText(detail, clip.left + 14f * density, clip.top + 46f * density, subPaint)
    }
}
