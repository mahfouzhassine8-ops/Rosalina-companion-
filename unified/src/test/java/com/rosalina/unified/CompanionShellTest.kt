package com.rosalina.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompanionShellTest {
    @Test fun legacyTabsMigrateIntoNewShell(){
        assertEquals(ShellSection.COMPANION,ShellSection.parse(null,"Chat"))
        assertEquals(ShellSection.PHOTO,ShellSection.parse(null,"Create"))
        assertEquals(ShellSection.PHOTO,ShellSection.parse(null,"Edit"))
        assertEquals(ShellSection.ANIMATE,ShellSection.parse(null,"Animate"))
    }
    @Test fun explicitShellStateWins(){
        assertEquals(ShellSection.SETTINGS_MODELS,ShellSection.parse("SETTINGS_MODELS","Chat"))
    }
    @Test fun thinkingBlocksNeverReachVisibleChat(){
        val raw="<think>private reasoning</think>\nHello there."
        val visible=SpeechText.visible(raw)
        assertEquals("Hello there.",visible)
        assertFalse(visible.contains("<think>"))
    }
}
