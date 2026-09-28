package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class RuntimePolicyTest {
    @Test fun batchingKeepsExactUtf8AndReducesMessages() {
        val batch=StreamBatch(50);val output=StringBuilder();var messages=0
        val chunks=(0..99).map{"$it 🌸 "}
        chunks.forEachIndexed{i,s->batch.append(s,i*10L)?.let{output.append(it);messages++}}
        batch.flush()?.let{output.append(it);messages++}
        assertEquals(chunks.joinToString(""),output.toString());assertTrue(messages<=22);assertNull(batch.flush())
    }
    @Test fun firstTextIsImmediate(){assertEquals("Hi",StreamBatch().append("Hi",0))}
    @Test fun severeIsNeverWorkedThrough(){for(gpu in listOf(false,true))for(t in 3..6)assertEquals(0,WorkBudget.percent(t,gpu))}
    @Test fun budgetFallsBeforeSevere(){assertTrue(WorkBudget.percent(0,false)>WorkBudget.percent(1,false));assertTrue(WorkBudget.percent(1,false)>WorkBudget.percent(2,false));assertEquals(30,WorkBudget.percent(0,false,.9f))}
    @Test fun missingHeadroomDoesNotBlockNormal(){assertTrue(WorkBudget.percent(0,false,Float.NaN)>0)}
    @Test fun budgetDoesNotChangeSelectedQuality(){assertEquals(512,RenderProfile.Standard.width);assertEquals(12,RenderProfile.Standard.steps);assertEquals(8,RenderProfile.Draft.steps)}
    @Test fun dutyCycleMatchesRealWallTime(){assertEquals(300,(0L..999L).count{!WorkBudget.shouldPause(it,30)});assertFalse((0L..999L).any{WorkBudget.shouldPause(it,100)})}
    @Test fun backgroundNoiseDoesNotStartVoice(){val gate=VoiceGate();repeat(100){assertFalse(gate.accept(110.0,40))};assertFalse(gate.started)}
    @Test fun sustainedSpeechStartsAndSilenceEnds(){val gate=VoiceGate();repeat(5){assertFalse(gate.accept(2500.0,40))};assertTrue(gate.accept(2500.0,40));repeat(28){gate.accept(100.0,40)};assertTrue(gate.finished())}
    @Test fun unsafeSpeakerRouteCannotTriggerAcousticBarge(){val gate=VoiceGate();repeat(100){assertFalse(gate.accept(5000.0,40,false))};assertFalse(gate.started)}
    @Test fun probableSelfEchoIsNotAUserCommand(){assertTrue(EchoText.resemblesOutput("I can help you with that today","Hello, I can help you with that today."));assertFalse(EchoText.resemblesOutput("Stop and tell me about cooking","The weather is warm today"))}
    @Test fun politeRequestsRouteWithoutMisroutingQuestions(){assertEquals(TaskKind.CREATE,Route.kind("Could you please create a picture of a garden"));assertEquals(TaskKind.ANIMATE,Route.kind("Please animate this photo for eight seconds"));assertEquals(TaskKind.CHAT,Route.kind("Can you tell me how to create a picture?"))}
    @Test fun unknownRouteRemainsManualEvenWithAec(){assertFalse(VoiceSafety.allow(true,false,false,true,true));assertTrue(VoiceSafety.allow(true,false,true,true,true));assertFalse(VoiceSafety.allow(false,true,false,false,false))}
    @Test fun capturePrerollCannotExceedWhisperInputLimit(){val limit=CaptureLimit();assertEquals(6399,limit.accept(6399));repeat(1000){limit.accept(640)};assertEquals(480000,limit.count);assertEquals(0,limit.accept(640));assertTrue(limit.full())}
    @Test fun hostPauseIsNotAdvertisedAsGpuDutyCycle(){for(t in 0..2)assertEquals(100,WorkBudget.percent(t,true))}
}
