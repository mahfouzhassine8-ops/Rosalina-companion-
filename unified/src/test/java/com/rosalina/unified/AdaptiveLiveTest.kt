package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLiveTest {
    @Test fun interruptionsShortenFutureTurns(){
        assertEquals(200,LiveLearningPolicy.nextCap(224,true))
        assertEquals(LiveLearningPolicy.MIN_CAP,LiveLearningPolicy.nextCap(LiveLearningPolicy.MIN_CAP,true))
    }
    @Test fun completedTurnsGraduallyExpand(){
        assertEquals(232,LiveLearningPolicy.nextCap(224,false))
        assertEquals(LiveLearningPolicy.MAX_CAP,LiveLearningPolicy.nextCap(LiveLearningPolicy.MAX_CAP,false))
    }
    @Test fun userConfiguredLimitStillWins(){
        assertEquals(96,LiveLearningPolicy.effectiveCap(96,224,true))
        assertEquals(224,LiveLearningPolicy.effectiveCap(1024,224,true))
    }
    @Test fun disablingLearningKeepsConversationalSafetyCap(){
        assertEquals(256,LiveLearningPolicy.effectiveCap(1024,128,false))
    }
    @Test fun latencyAverageIsStable(){
        assertEquals(800,LiveLearningPolicy.nextAverage(0,0,800))
        assertEquals(840,LiveLearningPolicy.nextAverage(800,4,1000))
    }
}
