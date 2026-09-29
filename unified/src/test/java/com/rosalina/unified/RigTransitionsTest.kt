package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test

class RigTransitionsTest {
    private fun speech(id:String="a",gesture:Gesture=Gesture.OPEN_HAND)=CompanionSnapshot(taskId="task",utteranceId=id,
        phase=CompanionPhase.SPEAKING,playbackActive=true,changedAt=1000,
        performance=PerformanceState(expression=RosalinaExpression.HAPPY,gesture=gesture),mouth=MouthPose(.7f,.2f,.3f))
    @Test fun expressionChangesEaseAndConverge(){
        val c=RigTransitions();val idle=CompanionSnapshot(taskId="task")
        val initial=c.sample(idle,MotionTier.NORMAL,1000)
        val first=c.sample(speech(),MotionTier.NORMAL,1050)
        assertTrue(first.face.smile>initial.face.smile);assertTrue(first.face.smile<.85f)
        var final=first
        for(t in 1100L..2050L step 50)final=c.sample(speech(),MotionTier.NORMAL,t)
        assertEquals(.85f,final.face.smile,.002f)
    }
    @Test fun interruptedSpeechClosesMouthImmediatelyAndSettlesArms(){
        val c=RigTransitions();val s=speech();c.sample(s,MotionTier.NORMAL,1000)
        var previous=c.sample(s,MotionTier.NORMAL,2000)
        for(t in 2050L..2500L step 50)previous=c.sample(s,MotionTier.NORMAL,t)
        assertTrue(previous.leftArm<0)
        val stop=s.copy(phase=CompanionPhase.INTERRUPTED,playbackActive=false,utteranceId="")
        var p=c.sample(stop,MotionTier.NORMAL,2550)
        assertEquals(MouthPose(),p.mouth);assertEquals(Gesture.NONE,p.gesture)
        assertTrue(p.leftArm>previous.leftArm);assertTrue(p.leftArm<0)
        for(t in 2600L..4000L step 50)p=c.sample(stop,MotionTier.NORMAL,t)
        assertEquals(0f,p.leftArm,.002f)
    }
    @Test fun thermalReductionAndDisabledAnimationRemoveResidualMotion(){
        val c=RigTransitions();val s=speech();c.sample(s,MotionTier.NORMAL,1000)
        val p=c.sample(s,MotionTier.MINIMAL,1050)
        assertEquals(0f,p.leftArm,0f);assertEquals(0f,p.hair,0f);assertTrue(p.mouth.open>0)
        val off=c.sample(s,MotionTier.STATIC,1100,false)
        assertEquals(MouthPose(),off.mouth);assertEquals(0f,off.leftArm,0f);assertEquals(0f,off.blink,0f)
    }
    @Test fun silentAndNewUtteranceFramesNeverKeepPreviousMouth(){
        val c=RigTransitions();c.sample(speech(),MotionTier.NORMAL,1000)
        val silent=speech().copy(mouth=MouthPose())
        assertEquals(MouthPose(),c.sample(silent,MotionTier.NORMAL,1050).mouth)
        val next=speech("b").copy(mouth=MouthPose(.1f))
        assertEquals(next.mouth,c.sample(next,MotionTier.NORMAL,1100).mouth)
    }
    @Test fun cooldownStopsGesturesAcrossRapidClauses(){
        val c=RigTransitions();c.sample(speech(),MotionTier.NORMAL,1000)
        assertEquals(Gesture.NONE,c.sample(speech("b"),MotionTier.NORMAL,2000).gesture)
        assertEquals(Gesture.OPEN_HAND,c.sample(speech("c"),MotionTier.NORMAL,6000).gesture)
    }
    @Test fun resetAndNewTasksDoNotInheritExpressionOrGesture(){
        val c=RigTransitions();c.sample(speech(),MotionTier.NORMAL,1000);c.reset()
        val idle=CompanionSnapshot(taskId="new")
        assertEquals(RigMotion.sample(idle,MotionTier.NORMAL,1050),c.sample(idle,MotionTier.NORMAL,1050))
    }
}
