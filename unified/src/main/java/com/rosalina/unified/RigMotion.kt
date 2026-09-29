package com.rosalina.unified

import kotlin.math.*

/** Parameters are visual controls, not an expression label painted over a fixed image. */
internal data class FacePose(val leftEye:Float=1f,val rightEye:Float=1f,val browLeft:Float=0f,
    val browRight:Float=0f,val browLift:Float=0f,val smile:Float=0f,val blush:Float=0f,val head:Float=0f)
internal object FaceLibrary {
    fun pose(e:RosalinaExpression)=when(e) {
        RosalinaExpression.NEUTRAL->FacePose(.94f,.94f,smile=.1f)
        RosalinaExpression.HAPPY->FacePose(.74f,.74f,-3f,3f,-1.5f,.85f,.1f,1f)
        RosalinaExpression.SHY->FacePose(.63f,.68f,-4f,4f,1f,.35f,.55f,3f)
        RosalinaExpression.TEASING->FacePose(.6f,.95f,7f,-8f,-1f,.7f,.15f,-3f)
        RosalinaExpression.SAD->FacePose(.8f,.78f,-10f,10f,-2f,-.75f,0f,1.5f)
        RosalinaExpression.SURPRISED->FacePose(1.1f,1.1f,-4f,4f,-5f,.05f,0f,-1f)
        RosalinaExpression.THINKING->FacePose(.77f,.94f,8f,-5f,-1f,-.15f,0f,-2.5f)
        RosalinaExpression.BLUSH->FacePose(.9f,.85f,-3f,3f,0f,.5f,.9f,2f)
        RosalinaExpression.LISTENING->FacePose(1f,.98f,-2f,2f,-1f,.1f,0f,1f)
        RosalinaExpression.SPEAKING->FacePose(.93f,.94f,0f,0f,0f,.25f,0f,0f)
        RosalinaExpression.EYES_CLOSED->FacePose(.025f,.025f,0f,0f,0f,.35f,.1f,1f)
        RosalinaExpression.WINK->FacePose(.025f,1f,3f,-4f,-1f,.7f,.2f,-2f)
    }
}
internal data class RigPose(val face:FacePose,val blink:Float,val breath:Float,val sway:Float,
    val head:Float,val leftArm:Float,val hand:Float,val rightArm:Float,val hair:Float,
    val mouth:MouthPose,val gesture:Gesture,val actionTime:Float)
internal object RigMotion {
    fun sample(s:CompanionSnapshot,tier:MotionTier,now:Long,animate:Boolean=true):RigPose {
        val performance=s.performance.bounded().atPhase(s.phase);val face=FaceLibrary.pose(performance.expression)
        val t=now/1000.0;val elapsed=((now-s.changedAt).coerceAtLeast(0)/1000f)
        val talking=s.phase==CompanionPhase.SPEAKING && s.playbackActive
        val moving=animate && tier!=MotionTier.STATIC && s.phase!=CompanionPhase.INTERRUPTED
        // Nonuniform blink spacing; this never advances an engine state or randomly changes emotion.
        val cycle=(t/4.7).toInt();val within=t-cycle*4.7;val blinkStart=3.5+.35*sin(cycle*1.73)
        val blink=if(moving && within in blinkStart..blinkStart+.19)sin((within-blinkStart)/.19*PI).toFloat() else 0f
        val secondary=moving && tier.secondary
        val breath=if(moving)(sin(t*1.31)*.0028).toFloat() else 0f
        val sway=if(secondary)(sin(t*.49)*.7).toFloat() else 0f
        // A restrained gesture envelope occurs once at real playback onset, then rests.
        val envelope=if(talking && secondary && elapsed<3.6f)sin((elapsed/3.6f)*PI).toFloat() else 0f
        val amount=performance.intensity.coerceIn(0f,1f)*envelope
        val arms=when(performance.gesture){Gesture.OPEN_HAND->3.8f;Gesture.PLAYFUL->2.8f;Gesture.REASSURING->1.3f;Gesture.NONE->0f;else->.7f}
        val mouth=if(talking && animate)s.mouth.bounded() else MouthPose()
        return RigPose(face,blink,breath,sway,face.head+if(moving)(sin(t*.73)*.45).toFloat() else 0f,
            -amount*arms,amount*2.8f,amount*1.4f,if(secondary)(sin(t*.91+.9)*.9).toFloat() else 0f,
            mouth,if(secondary && talking)performance.gesture else Gesture.NONE,elapsed)
    }
}
