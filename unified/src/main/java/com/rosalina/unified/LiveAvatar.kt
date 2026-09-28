package com.rosalina.unified

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

internal enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED }

internal object AvatarStateResolver {
    fun resolve(s:TaskState):AvatarState {
        if(!s.busy)return AvatarState.IDLE
        val stage=s.stage.lowercase();val voice=s.voiceStage.lowercase()
        if(s.stopping || "interrupt" in stage || "interrupt" in voice)return AvatarState.INTERRUPTED
        if(s.avatarEnergy>.03f || "speaking" in voice)return AvatarState.SPEAKING
        if(stage.startsWith("listening"))return AvatarState.LISTENING
        if("understanding" in stage || "transcrib" in stage || "preparing response" in stage || "warming" in stage || "loading rosalina voice" in voice)return AvatarState.THINKING
        return if(s.kind==TaskKind.VOICE)AvatarState.THINKING else AvatarState.IDLE
    }
}

internal class LiveAvatarView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs) {
    private val density=resources.displayMetrics.density
    private val bitmap:Bitmap?=runCatching{
        val encoded=(0..1).joinToString(""){index->
            context.assets.open("avatar/$index.b64").bufferedReader().use{it.readText()}
        }
        val bytes=Base64.decode(encoded,Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?:error("Avatar JPEG decode returned null")
    }.getOrNull()
    internal val imageLoaded:Boolean get()=bitmap!=null
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(15,11,23)}
    private val borderPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.25f*density}
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=15f*density;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
    private val subPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(213,201,232);textSize=11f*density}
    private var state=AvatarState.IDLE
    private var energy=0f
    private var interruptedAt=0L

    fun bind(task:TaskState) {
        val next=AvatarStateResolver.resolve(task)
        if(next==AvatarState.INTERRUPTED && state!=AvatarState.INTERRUPTED)interruptedAt=SystemClock.uptimeMillis()
        state=next;energy=task.avatarEnergy.coerceIn(0f,1f)
        contentDescription="Rosalina live avatar · "+state.name.lowercase()
        invalidate()
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat();if(w<=1f||h<=1f)return
        canvas.drawRect(0f,0f,w,h,bgPaint)
        val now=SystemClock.uptimeMillis();val t=now/1000f
        val motion=when(state){
            AvatarState.IDLE->floatArrayOf(.0045f*sin(t*2f),1.2f*sin(t*.8f),.28f*sin(t*.55f),0f)
            AvatarState.LISTENING->floatArrayOf(.006f*sin(t*2.2f)+.004f,1.8f*sin(t*.9f),-.35f+.25f*sin(t*.7f),.22f)
            AvatarState.THINKING->floatArrayOf(.004f*sin(t*1.8f),2.2f*sin(t*.65f),.55f*sin(t*.52f),.12f)
            AvatarState.SPEAKING->floatArrayOf(.004f+.012f*energy,(-2.8f*energy)+1.3f*sin(t*2.8f),.25f*sin(t*1.6f),.35f+.65f*energy)
            AvatarState.INTERRUPTED->{val age=((now-interruptedAt).coerceAtLeast(0L))/1000f;val kick=(exp((-4f*age).toDouble())*sin((18f*age).toDouble())).toFloat();floatArrayOf(.002f,4f*kick,-1.2f*kick,.45f*abs(kick))}
        }
        val pad=5f*density;val clip=RectF(pad,pad,w-pad,h-pad);val radius=20f*density
        bitmap?.let{bmp->
            val scale=min(clip.width()/bmp.width,clip.height()/bmp.height)
            val dw=bmp.width*scale;val dh=bmp.height*scale
            val dst=RectF((w-dw)/2f,(h-dh)/2f,(w+dw)/2f,(h+dh)/2f)
            canvas.save()
            val clipPath=Path().apply{addRoundRect(clip,radius,radius,Path.Direction.CW)}
            canvas.clipPath(clipPath)
            val cx=w/2f;val cy=h/2f
            canvas.translate(0f,motion[1]*density)
            canvas.rotate(motion[2],cx,cy)
            canvas.scale(1f+motion[0],1f+motion[0],cx,cy)
            canvas.drawBitmap(bmp,null,dst,imagePaint)
            val fade=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=LinearGradient(0f,h*.60f,0f,h,Color.TRANSPARENT,Color.argb(238,13,10,19),Shader.TileMode.CLAMP)}
            canvas.drawRect(0f,h*.56f,w,h,fade)
            canvas.restore()
        } ?: run {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(213,201,232);textSize=18f*density;textAlign=Paint.Align.CENTER}
            canvas.drawText("Rosalina",w/2f,h/2f,p)
            p.textSize=11f*density;canvas.drawText("Avatar image unavailable · open Diagnostics",w/2f,h/2f+24f*density,p)
        }
        val active=motion[3].coerceIn(0f,1f);borderPaint.color=Color.argb((90+145*active).toInt(),194,166,255);borderPaint.strokeWidth=(1.2f+1.8f*active)*density
        canvas.drawRoundRect(.7f*density,.7f*density,w-.7f*density,h-.7f*density,radius,radius,borderPaint)
        val label=when(state){AvatarState.IDLE->"Rosalina";AvatarState.LISTENING->"Listening";AvatarState.THINKING->"Thinking";AvatarState.SPEAKING->"Speaking";AvatarState.INTERRUPTED->"Interrupted"}
        canvas.drawText(label,16f*density,h-34f*density,labelPaint)
        canvas.drawText(when(state){AvatarState.IDLE->"Companion ready";AvatarState.LISTENING->"I'm listening…";AvatarState.THINKING->"One moment…";AvatarState.SPEAKING->"Voice-reactive motion";AvatarState.INTERRUPTED->"Switching back to you"},16f*density,h-15f*density,subPaint)
        if(isAttachedToWindow)postInvalidateDelayed(if(state==AvatarState.IDLE)50L else 33L)
    }
}
