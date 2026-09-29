package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test
class LayeredAvatarStateTest {
    @Test fun allTwelveExpressionsHaveDistinctVisualParameters(){assertEquals(12,RosalinaExpression.entries.map{FaceLibrary.pose(it)}.distinct().size)}
    @Test fun listeningCannotUseSpeechMouthOrTalkingGesture(){
        val s=CompanionSnapshot(phase=CompanionPhase.LISTENING,performance=PerformanceState(gesture=Gesture.WALK),mouth=MouthPose(1f,1f,1f))
        val p=RigMotion.sample(s,MotionTier.NORMAL,10000);assertEquals(MouthPose(),p.mouth);assertEquals(Gesture.NONE,p.gesture)
    }
    @Test fun interruptStopsMouthArmsAndHairTogether(){
        val s=CompanionSnapshot(phase=CompanionPhase.INTERRUPTED,playbackActive=true,performance=PerformanceState(gesture=Gesture.OPEN_HAND),mouth=MouthPose(1f),changedAt=1000)
        val p=RigMotion.sample(s,MotionTier.NORMAL,1500);assertEquals(MouthPose(),p.mouth);assertEquals(0f,p.leftArm,0f);assertEquals(0f,p.hair,0f);assertEquals(0f,p.breath,0f)
    }
    @Test fun severeDropsSecondaryMotionButRetainsRealMouth(){
        val s=CompanionSnapshot(phase=CompanionPhase.SPEAKING,playbackActive=true,performance=PerformanceState(gesture=Gesture.WALK),mouth=MouthPose(.4f,.1f,.2f),changedAt=1000)
        val p=RigMotion.sample(s,MotionTier.MINIMAL,2500);assertEquals(s.mouth,p.mouth);assertEquals(Gesture.NONE,p.gesture);assertEquals(0f,p.hair,0f);assertEquals(0f,p.leftArm,0f)
    }
    @Test fun staticTierDoesNotInventSpeech(){val p=RigMotion.sample(CompanionSnapshot(),MotionTier.STATIC,5000);assertEquals(MouthPose(),p.mouth);assertEquals(0f,p.blink,0f)}
    @Test fun nonFiniteRpcMouthIsBounded(){assertEquals(MouthPose(0f,1f,0f),MouthPose(Float.NaN,3f,-4f).bounded())}
    @Test fun userAnimationOffStopsSpeakingMotionWithoutChangingRuntime() {
        val s=CompanionSnapshot(phase=CompanionPhase.SPEAKING,playbackActive=true,performance=PerformanceState(gesture=Gesture.WALK),mouth=MouthPose(.7f,.2f,.3f),changedAt=1000)
        val p=RigMotion.sample(s,MotionTier.STATIC,2000,false)
        assertEquals(MouthPose(),p.mouth);assertEquals(0f,p.breath,0f);assertEquals(0f,p.hair,0f);assertEquals(0f,p.blink,0f);assertEquals(Gesture.NONE,p.gesture)
        assertTrue(s.playbackActive);assertEquals(CompanionPhase.SPEAKING,s.phase)
    }
}
