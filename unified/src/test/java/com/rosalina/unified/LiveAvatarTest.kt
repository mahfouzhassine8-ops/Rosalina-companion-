package com.rosalina.unified

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAvatarTest {
    @Test fun idleWhenNoTask(){
        assertEquals(AvatarState.IDLE,AvatarStateResolver.resolve(TaskState()))
    }
    @Test fun listeningFollowsLiveStage(){
        assertEquals(AvatarState.LISTENING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Listening · LIVE · speak naturally")))
    }
    @Test fun thinkingFollowsUnderstanding(){
        assertEquals(AvatarState.THINKING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Understanding you · LIVE")))
    }
    @Test fun audioEnergyMakesAvatarSpeak(){
        assertEquals(AvatarState.SPEAKING,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Listening · LIVE",avatarEnergy=.2f)))
    }
    @Test fun interruptionWinsOverSpeechEnergy(){
        assertEquals(AvatarState.INTERRUPTED,AvatarStateResolver.resolve(TaskState(kind=TaskKind.VOICE,busy=true,stage="Listening · LIVE · interrupted",avatarEnergy=.8f)))
    }
}
