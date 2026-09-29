package com.rosalina.unified

import kotlin.math.exp

/** View-owned smoothing. It consumes presentation events and never advances a session. */
internal class RigTransitions {
    private var previous:RigPose?=null
    private var lastFrame=0L
    private var task=""
    private var utterance=""
    private var playback=false
    private var gestureStart=0L
    private var lastGesture:Long?=null
    private var allowGesture=false

    fun reset(){previous=null;lastFrame=0;task="";utterance="";playback=false;lastGesture=null;allowGesture=false}

    fun sample(s:CompanionSnapshot,tier:MotionTier,now:Long,animate:Boolean=true):RigPose {
        if(task!=s.taskId){reset();task=s.taskId}
        val talking=s.phase==CompanionPhase.SPEAKING && s.playbackActive
        val newSpeech=talking && (!playback || utterance!=s.utteranceId)
        if(newSpeech){
            gestureStart=now
            allowGesture=tier.secondary && animate && s.performance.gesture!=Gesture.NONE &&
                (lastGesture==null || now-lastGesture!!>=4500)
            if(allowGesture)lastGesture=now
        }
        val input=if(talking)s.copy(changedAt=gestureStart,performance=if(allowGesture)s.performance else s.performance.copy(gesture=Gesture.NONE)) else s
        val target=RigMotion.sample(input,tier,now,animate)
        val old=previous
        // Disabled animation, severe thermal stops, and resume never retain an animated pose.
        if(!animate || tier==MotionTier.STATIC || old==null || now<lastFrame || now-lastFrame>500){
            previous=target;lastFrame=now;utterance=s.utteranceId;playback=talking
            return target
        }
        val dt=(now-lastFrame).coerceIn(0,100).toFloat()
        val faceAmount=(1-exp(-dt/140f)).coerceIn(0f,1f)
        val bodyAmount=(1-exp(-dt/180f)).coerceIn(0f,1f)
        val mouthAmount=(1-exp(-dt/35f)).coerceIn(0f,1f)
        fun mix(a:Float,b:Float,k:Float)=a+(b-a)*k
        val a=old.face;val b=target.face
        val face=FacePose(mix(a.leftEye,b.leftEye,faceAmount),mix(a.rightEye,b.rightEye,faceAmount),
            mix(a.browLeft,b.browLeft,faceAmount),mix(a.browRight,b.browRight,faceAmount),
            mix(a.browLift,b.browLift,faceAmount),mix(a.smile,b.smile,faceAmount),
            mix(a.blush,b.blush,faceAmount),mix(a.head,b.head,faceAmount))
        // Close immediately on silence/stop and never carry articulation across utterance generations.
        val mouth=if(!talking || newSpeech || target.mouth.open==0f)target.mouth else MouthPose(
            mix(old.mouth.open,target.mouth.open,mouthAmount),mix(old.mouth.wide,target.mouth.wide,mouthAmount),
            mix(old.mouth.round,target.mouth.round,mouthAmount)).bounded()
        val result=target.copy(face=face,head=mix(old.head,target.head,faceAmount),mouth=mouth,
            leftArm=if(tier.secondary)mix(old.leftArm,target.leftArm,bodyAmount) else 0f,
            hand=if(tier.secondary)mix(old.hand,target.hand,bodyAmount) else 0f,
            rightArm=if(tier.secondary)mix(old.rightArm,target.rightArm,bodyAmount) else 0f)
        previous=result;lastFrame=now;utterance=s.utteranceId;playback=talking
        return result
    }
}
