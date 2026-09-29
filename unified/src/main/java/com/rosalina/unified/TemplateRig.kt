package com.rosalina.unified

import kotlin.math.*

/** Local deformation of the supplied hero pixels. Not a walking cycle or a 3-D turn. */
internal class TemplateRig {
    val columns=48;val rows=96
    val vertices=FloatArray((columns+1)*(rows+1)*2)
    private fun mask(x:Float,y:Float,cx:Float,cy:Float,rx:Float,ry:Float):Float {
        val d=sqrt(((x-cx)/rx).pow(2)+((y-cy)/ry).pow(2))
        val v=((1-d)/.45f).coerceIn(0f,1f);return v*v*(3-2*v)
    }
    // Immutable skinning weights: no square roots or allocations on the frame clock.
    private val points=Array((columns+1)*(rows+1)){i->
        val x=(i%(columns+1))/columns.toFloat();val y=(i/(columns+1))/rows.toFloat()
        floatArrayOf(x,y,mask(x,y,.56f,.155f,.26f,.135f),
            mask(x,y,.28f,.43f,.12f,.22f)+mask(x,y,.80f,.42f,.13f,.26f),
            mask(x,y,.55f,.63f,.34f,.35f),mask(x,y,.52f,.34f,.25f,.07f),
            mask(x,y,.34f,.59f,.21f,.12f),mask(x,y,.568f,.241f,.060f,.018f),
            mask(x,y,.470f,.197f,.064f,.020f),mask(x,y,.625f,.187f,.065f,.022f))
    }
    fun update(time:Float,energy:Float,face:RosalinaExpression,left:Float,top:Float,w:Float,h:Float,motion:Boolean,secondary:Boolean) {
        val t=time.takeIf{it.isFinite()} ?:0f;val e=energy.takeIf{it.isFinite()}?.coerceIn(0f,1f) ?:0f
        val breath=if(motion)sin(t*1.45f) else 0f
        val blink=if(motion)AvatarRig.blink(t) else 0f
        val eyesClosed=face==RosalinaExpression.EYES_CLOSED
        val wink=face==RosalinaExpression.WINK
        val smile=when(face){RosalinaExpression.HAPPY, RosalinaExpression.TEASING, RosalinaExpression.BLUSH->1f;RosalinaExpression.SAD->-1f;else->0f}
        val tilt=if(motion).006f*sin(t*.65f)+(if(face==RosalinaExpression.LISTENING)-.006f else 0f) else 0f
        val sway=if(motion && secondary).0024f*sin(t*.8f) else 0f
        var i=0
        val ct=cos(tilt);val st=sin(tilt)
        val gestures=if(motion && secondary && e>0f).0025f*sin(t*2.1f)*e else 0f
        for(p in points){
            val x=p[0];val y=p[1];val head=p[2];val hair=p[3];val body=p[4]
            val shoulder=p[5];val hand=p[6];val mouth=p[7];val le=p[8];val re=p[9]
            val closeLeft=if(eyesClosed || wink)1f else blink
            val closeRight=if(eyesClosed)1f else blink
            val px=(x-.565f)*594f;val py=(y-.27f)*1244f
            val dx=(ct*px-st*py-px)/594f*head
            val dy=(st*px+ct*py-py)/1244f*head
            vertices[i++]=left+(x+dx+sway*body+sway*.6f*hair+gestures*hand)*w
            vertices[i++]=top+(y+dy-.0017f*breath*shoulder-
                (if(motion).92f*((y-.197f)*le*closeLeft+(y-.187f)*re*closeRight) else 0f)+
                (if(motion).0025f*e*mouth-smile*.0015f*mouth*abs((x-.568f)/.060f) else 0f)+gestures*.35f*hand)*h
        }
    }
}
