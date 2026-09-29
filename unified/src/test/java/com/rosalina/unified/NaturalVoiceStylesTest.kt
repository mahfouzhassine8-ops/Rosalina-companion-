package com.rosalina.unified
import org.junit.Assert.*
import org.junit.Test
class NaturalVoiceStylesTest {
    @Test fun naturalIsDefaultAndLeavesPerformanceUnchanged(){
        val p=PerformanceState()
        assertEquals(NaturalVoiceStyle.NATURAL,NaturalVoiceStyle.fromKey(null))
        assertEquals(NaturalVoiceStyle.NATURAL,NaturalVoiceStyle.fromKey("squeaky"))
        assertEquals(p,NaturalVoiceStyle.NATURAL.apply(p))
    }
    @Test fun stylesRetainSeriousIntentAndNativeReactions(){
        val p=PerformanceState("reassuring",.2f,.94f,.9f,Delivery.SOFT,Gesture.REASSURING,RosalinaExpression.SAD)
        for(style in NaturalVoiceStyle.entries){val result=style.apply(p)
            assertEquals(p.emotion,result.emotion);assertEquals(p.delivery,result.delivery)
            assertEquals(p.expression,result.expression);assertEquals(p.gesture,result.gesture)
            assertTrue(result.pace in .8f..1.2f);assertTrue(result.volume in .55f..1f)
        }
        val laugh=p.copy(delivery=Delivery.LAUGH)
        assertEquals("Hello [laugh]",PerformanceDirector.localText("Hello",NaturalVoiceStyle.ANIME.apply(laugh)))
    }
    @Test fun eachPresetHasDistinctRestrainedPlaybackControls(){
        assertEquals(NaturalVoiceStyle.entries.size,NaturalVoiceStyle.entries.map{it.apply(PerformanceState()).let{p->p.pace to p.volume}}.distinct().size)
    }
}
