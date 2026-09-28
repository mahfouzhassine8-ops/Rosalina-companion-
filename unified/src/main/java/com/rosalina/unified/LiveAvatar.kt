package com.rosalina.unified

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

internal enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED }

internal object AvatarStateResolver {
    fun resolve(s:TaskState):AvatarState {
        if(!s.busy)return AvatarState.IDLE
        val stage=s.stage.lowercase()
        val voice=s.voiceStage.lowercase()
        if(s.stopping || "interrupt" in stage || "interrupt" in voice)return AvatarState.INTERRUPTED
        if(s.avatarEnergy>.03f || "speaking" in voice)return AvatarState.SPEAKING
        if(stage.startsWith("listening"))return AvatarState.LISTENING
        if("understanding" in stage || "transcrib" in stage || "preparing response" in stage || "warming" in stage || "loading rosalina voice" in voice)return AvatarState.THINKING
        return if(s.kind==TaskKind.VOICE)AvatarState.THINKING else AvatarState.IDLE
    }
}

internal class LiveAvatarView @JvmOverloads constructor(
    context:Context,attrs:AttributeSet?=null
):View(context,attrs) {
    private val density=resources.displayMetrics.density
    private val bitmap:Bitmap?=runCatching{BitmapFactory.decodeResource(resources,R.drawable.rosalina_live_avatar)}.getOrNull()
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val panelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(20,15,29)}
    private val borderPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.25f*density}
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=13f*density;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
    private val subPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(213,201,232);textSize=10.5f*density}
    private val matrix=Matrix()
    private var state=AvatarState.IDLE
    private var energy=0f
    private var interruptedAt=0L
    private var lastFrame=0L

    fun bind(task:TaskState) {
        val next=AvatarStateResolver.resolve(task)
        if(next==AvatarState.INTERRUPTED && state!=AvatarState.INTERRUPTED)interruptedAt=SystemClock.uptimeMillis()
        state=next
        energy=task.avatarEnergy.coerceIn(0f,1f)
        contentDescription="Rosalina live avatar · "+state.name.lowercase()
        invalidate()
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat()
        if(w<=1f || h<=1f)return
        val radius=18f*density
        canvas.drawRoundRect(0f,0f,w,h,radius,radius,panelPaint)
        val now=SystemClock.uptimeMillis();val t=now/1000f
        val motion=when(state){
            AvatarState.IDLE->floatArrayOf(.0045f*sin(t*2.0f),1.2f*sin(t*.8f),.28f*sin(t*.55f),0f)
            AvatarState.LISTENING->floatArrayOf(.006f*sin(t*2.2f)+.004f,1.8f*sin(t*.9f),-.35f+.25f*sin(t*.7f),.22f)
            AvatarState.THINKING->floatArrayOf(.004f*sin(t*1.8f),2.2f*sin(t*.65f),.55f*sin(t*.52f),.12f)
            AvatarState.SPEAKING->floatArrayOf(.004f+.012f*energy,(-2.8f*energy)+1.3f*sin(t*2.8f),.25f*sin(t*1.6f),.35f+.65f*energy)
            AvatarState.INTERRUPTED->{
                val age=((now-interruptedAt).coerceAtLeast(0L))/1000f
                val kick=(exp((-4f*age).toDouble())*sin((18f*age).toDouble())).toFloat()
                floatArrayOf(.002f,4f*kick,-1.2f*kick,.45f*abs(kick))
            }
        }
        bitmap?.let{bmp->
            val pad=8f*density
            val availableW=w-pad*2;val availableH=h-pad*2
            val base=min(availableW/bmp.width,availableH/bmp.height)
            val scale=base*(1f+motion[0])
            val dx=(w-bmp.width*scale)/2f
            val dy=(h-bmp.height*scale)/2f+motion[1]*density
            canvas.save()
            val clip=RectF(pad,pad,w-pad,h-pad)
            val clipPath=Path().apply{addRoundRect(clip,radius*.82f,radius*.82f,Path.Direction.CW)}
            canvas.clipPath(clipPath)
            matrix.reset()
            matrix.postScale(scale,scale)
            matrix.postTranslate(dx,dy)
            if(motion[2]!=0f)matrix.postRotate(motion[2],w/2f,h/2f)
            canvas.drawBitmap(bmp,matrix,imagePaint)
            val fade=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                shader=LinearGradient(0f,h*.64f,0f,h,Color.TRANSPARENT,Color.argb(225,13,10,19),Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f,h*.58f,w,h,fade)
            canvas.restore()
        }
        val active=motion[3].coerceIn(0f,1f)
        borderPaint.color=Color.argb((105+130*active).toInt(),194,166,255)
        borderPaint.strokeWidth=(1.25f+1.8f*active)*density
        canvas.drawRoundRect(.7f*density,.7f*density,w-.7f*density,h-.7f*density,radius,radius,borderPaint)

        val label=when(state){AvatarState.IDLE->"Rosalina";AvatarState.LISTENING->"Listening";AvatarState.THINKING->"Thinking";AvatarState.SPEAKING->"Speaking";AvatarState.INTERRUPTED->"Interrupted"}
        canvas.drawText(label,16f*density,h-28f*density,labelPaint)
        canvas.drawText(when(state){
            AvatarState.IDLE->"Live avatar ready"
            AvatarState.LISTENING->"I'm listening…"
            AvatarState.THINKING->"One moment…"
            AvatarState.SPEAKING->"Voice-reactive motion"
            AvatarState.INTERRUPTED->"Switching back to you"
        },16f*density,h-11f*density,subPaint)

        val targetDelay=if(state==AvatarState.IDLE)50L else 33L
        if(isAttachedToWindow && now-lastFrame>=targetDelay){lastFrame=now;postInvalidateDelayed(targetDelay)}
        else if(isAttachedToWindow)postInvalidateDelayed(targetDelay)
    }
}
