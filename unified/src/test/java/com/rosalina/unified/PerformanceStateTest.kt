package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test
class PerformanceStateTest {
    @Test fun seriousContextOverridesFlirt(){val p=PerformanceDirector.decide("I'm grieving; don't tease","I'm sorry to hear that.",true);assertEquals(RosalinaExpression.SAD,p.expression);assertEquals(Gesture.REASSURING,p.gesture);assertNotEquals(Delivery.PLAYFUL,p.delivery)}
    @Test fun flirtRequiresOptIn(){assertNotEquals(RosalinaExpression.TEASING,PerformanceDirector.decide("tease me","Of course.",false).expression);assertEquals(RosalinaExpression.TEASING,PerformanceDirector.decide("tease me","Of course.",true).expression)}
    @Test fun unsupportedLocalWhisperIsNotTaggedOrFaked(){val p=PerformanceDirector.decide("whisper","Hello.",true);assertEquals(Delivery.WHISPER,p.delivery);assertEquals("Hello.",PerformanceDirector.localText("Hello.",p));assertEquals("[whispers] Hello.",PerformanceDirector.onlineText("Hello.",p,"eleven_v3"));assertEquals("Hello.",PerformanceDirector.onlineText("Hello.",p,"eleven_flash_v2_5"))}
    @Test fun nativeReactionAndFaceAgree(){val p=PerformanceDirector.decide("a little chuckle","That was unexpected.",true);assertEquals(RosalinaExpression.HAPPY,p.expression);assertEquals("Hello. [chuckle]",PerformanceDirector.localText("Hello.",p))}
    @Test fun interruptionStopsMouthAndGestureTogether(){val r=CompanionRuntime();r.begin("t",1);val p=PerformanceState(gesture=Gesture.OPEN_HAND,expression=RosalinaExpression.HAPPY);r.preparingSpeech("t","u",p.expression,"test",2,p);r.playback("t","u",true,3);r.energy("t","u",.8f,"PCM",MouthPose(.8f,.3f,.4f));r.interrupt("t",4);assertEquals(MouthPose(),r.snapshot.mouth);assertEquals(Gesture.NONE,r.snapshot.performance.gesture);r.energy("t","u",1f,"late");assertEquals(MouthPose(),r.snapshot.mouth)}
    @Test fun nonfiniteValuesNeverReachRig(){val p=PerformanceState(intensity=Float.NaN,pace=Float.POSITIVE_INFINITY,volume=Float.NEGATIVE_INFINITY).bounded();assertTrue(p.intensity.isFinite());assertTrue(p.pace.isFinite());assertTrue(p.volume.isFinite())}
    @Test fun silenceClosesAllMouthAxes(){assertEquals(MouthPose(),PcmMouth.estimate(0f,.9f));assertEquals(MouthPose(),PcmMouth.estimate(Float.NaN,Float.NaN))}
    @Test fun actualSignalCanProduceDifferentArticulation(){val a=PcmMouth.estimate(.7f,.02f);val b=PcmMouth.estimate(.7f,.3f);assertEquals(a.open,b.open,0f);assertTrue(a.round>b.round);assertTrue(b.wide>a.wide)}
    @Test fun mutedRecordingCanBeUnmutedButIsNotListening(){val r=CompanionRuntime();r.begin("t",0);r.microphone("t",false,true,1);assertTrue(r.snapshot.microphoneAvailable);assertFalse(r.snapshot.microphoneActive);assertEquals(CompanionPhase.IDLE,r.snapshot.phase)}
    @Test fun preparationKeepsPerformanceWithoutStartingPlayback(){val r=CompanionRuntime();r.begin("t",0);val p=PerformanceDirector.decide("tease me","Hello.",true);r.preparingSpeech("t","u",p.expression,"test",1,p);assertEquals(p,r.snapshot.performance);assertFalse(r.snapshot.playbackActive)}
}
