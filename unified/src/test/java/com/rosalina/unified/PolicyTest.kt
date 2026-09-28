package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
 @Test fun thermalAllowsNormalLightModerate(){for(s in listOf(-1,0,1,2))assertFalse(ThermalPolicy.blocks(s))}
 @Test fun severeThrottlesCriticalAndAboveStop(){assertFalse(ThermalPolicy.blocks(3));for(s in 4..6)assertTrue(ThermalPolicy.blocks(s))}
 @Test fun routesExplicitImageRequests(){assertEquals(TaskKind.CREATE,Route.kind("Create a picture of a garden"));assertEquals(TaskKind.CREATE,Route.kind("draw an image of a red vase"))}
 @Test fun routesEditAndMotion(){assertEquals(TaskKind.EDIT,Route.kind("Edit this photo and remove the rain"));assertEquals(TaskKind.ANIMATE,Route.kind("Animate this photo for six seconds"))}
 @Test fun normalConversationStaysChat(){assertEquals(TaskKind.CHAT,Route.kind("How can I create a picture?"));assertEquals(TaskKind.CHAT,Route.kind("Tell me about animation"))}
 @Test fun durationsAreBounded(){assertEquals(6,Route.seconds("six seconds"));assertEquals(8,Route.seconds("eight seconds"));assertEquals(10,Route.seconds("10 seconds"));assertEquals(6,Route.seconds("200 seconds"))}
 @Test fun tensorCountersAreNeverSampling(){val p=ProgressParser(12);assertFalse(p.consume("@@STEP 100 1000"));assertFalse(p.consume("tensor 4/12"));assertFalse(p.consume("@@SAMPLE 10 700 2"));assertNull(p.percent)}
 @Test fun actualSamplingAndStages(){val p=ProgressParser(8);assertTrue(p.consume("@@SAMPLE 4 8 1.2"));assertEquals(50,p.percent);assertTrue(p.consume("@@STAGE Decoding"));assertEquals("Decoding",p.stage);assertNull(p.percent)}
 @Test fun progressNeverRewindsWithinPhase(){val p=ProgressParser(12);p.consume("@@SAMPLE 4 12 2");assertFalse(p.consume("@@SAMPLE 3 12 1"));assertFalse(p.consume("@@SAMPLE 5 11 1"));assertEquals(4,p.step)}
 @Test fun oldCleanupCannotReleaseNewWork(){val l=EngineLease();assertTrue(l.acquire("first"));assertFalse(l.acquire("second"));assertTrue(l.release("first"));assertTrue(l.acquire("second"));assertFalse(l.release("first"));assertEquals("second",l.current())}
 @Test fun standardQualityIsNotSilentlyReduced(){assertEquals(512,RenderProfile.Standard.width);assertEquals(512,RenderProfile.Standard.height);assertEquals(12,RenderProfile.Standard.steps);assertEquals(384,RenderProfile.Draft.width);assertEquals(8,RenderProfile.Draft.steps)}
 @Test fun voiceHistoryIsBounded(){val history=SpeechText.history((1..100).map{"You" to "x".repeat(5000)});assertTrue(history.length<=6000)}
 @Test fun sentenceChunksBeginBeforeEntireResponse(){assertTrue(SpeechText.cut("This is Rosalina speaking now. Here comes a second sentence")>0)}
}
