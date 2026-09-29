package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class CompanionRuntimeTest {
    @Test fun exactNavigationAndExpressionSets() {
        assertEquals(listOf("Chat","Live Voice","Settings"),CompanionDestination.entries.map{it.label})
        assertEquals(listOf("Neutral","Happy","Shy","Teasing","Sad","Surprised","Thinking","Blush","Listening","Speaking","Eyes Closed","Wink"),RosalinaExpression.entries.map{it.label})
    }
    @Test fun preparingSpeechIsNotSpeakingAndStopRejectsLateCallbacks() {
        val r=CompanionRuntime();r.begin("a",0);r.microphone("a",true,false,1)
        assertEquals(CompanionPhase.LISTENING,r.snapshot.phase)
        r.processing("a",true,2);r.preparingSpeech("a","u",RosalinaExpression.HAPPY,"test",3)
        assertEquals(CompanionPhase.THINKING,r.snapshot.phase);assertEquals(0f,r.snapshot.energy,0f)
        r.energy("a","u",1f,"not yet playing");assertEquals(0f,r.snapshot.energy,0f)
        r.playback("a","u",true,4);r.energy("a","u",.6f,"PCM")
        assertEquals(CompanionPhase.SPEAKING,r.snapshot.phase);assertEquals(.6f,r.snapshot.energy,0f)
        r.interrupt("a",5);r.playback("a","u",true,6);r.energy("a","u",1f,"late")
        assertEquals(CompanionPhase.INTERRUPTED,r.snapshot.phase);assertFalse(r.snapshot.playbackActive);assertEquals(0f,r.snapshot.energy,0f)
        r.listen("a",7);assertEquals(CompanionPhase.LISTENING,r.snapshot.phase)
        r.end("a",8);r.playback("a","u",true,9);assertEquals(CompanionPhase.IDLE,r.snapshot.phase)
    }
    @Test fun mutedMicrophoneIsNotPresentedAsListening() {
        val r=CompanionRuntime();r.begin("task",1);r.microphone("task",false,true,2)
        assertEquals(CompanionPhase.IDLE,r.snapshot.phase);assertTrue(r.snapshot.muted)
        r.microphone("task",true,false,3);assertEquals(CompanionPhase.LISTENING,r.snapshot.phase)
    }
    @Test fun successorTaskIgnoresPreviousGeneration() {
        val r=CompanionRuntime();r.begin("old",1);r.preparingSpeech("old","u",RosalinaExpression.NEUTRAL,"test",2)
        r.begin("new",3);r.playback("old","u",true,4);r.end("old",5)
        assertEquals("new",r.snapshot.taskId);assertFalse(r.snapshot.playbackActive)
    }
    @Test fun motionDegradesImmediatelyAndRecoversOnlyAfterStableInterval() {
        val p=MotionBudget();assertEquals(MotionTier.NORMAL,p.sample(0,true,0))
        assertEquals(MotionTier.MINIMAL,p.sample(3,true,1));assertEquals(MotionTier.MINIMAL,p.sample(0,true,2))
        assertEquals(MotionTier.MINIMAL,p.sample(0,true,19_000));assertEquals(MotionTier.NORMAL,p.sample(0,true,20_002))
        assertEquals(MotionTier.STATIC,p.sample(4,true,20_003))
    }
    @Test fun defaultPersonalityDoesNotChangeProtectedSystemPrompt(){assertEquals("original",CompanionPersonality.system("original",false))}
}
