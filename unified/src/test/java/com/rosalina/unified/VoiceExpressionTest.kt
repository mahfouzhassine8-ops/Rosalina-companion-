package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class VoiceExpressionTest {
    @Test fun intimatePresetIsNaturalLowerSlowerAndBreathy(){
        val v=VoiceExpressionResolver.resolve("intimate",1f,0f,0f,0f,0f,0f,1f,"","",true)
        assertTrue(v.pitchSemitones in -2f..-.5f)
        assertTrue(v.pace in .82f.. .95f)
        assertTrue(v.breathiness in .2f.. .38f)
        assertTrue(v.tone<0f)
        assertFalse(v.stylized)
    }
    @Test fun adaptiveCanChooseIntimateButNeverChoosesSqueaky(){
        val intimate=VoiceExpressionResolver.resolve("adaptive",.8f,0f,0f,0f,0f,0f,1f,"whisper closer in a sensual voice","",true)
        assertTrue(intimate.name.contains("intimate"))
        assertFalse(intimate.stylized)
        val playful=VoiceExpressionResolver.resolve("adaptive",1f,0f,0f,0f,0f,0f,1f,"be cute and playful","",true)
        assertTrue(playful.pitchSemitones<=4f)
        assertFalse(playful.stylized)
    }
    @Test fun squeakyIsExplicitStylizedHighPitch(){
        val v=VoiceExpressionResolver.resolve("squeaky",1f,0f,0f,0f,0f,0f,1f,"","",true)
        assertTrue(v.pitchSemitones>=6f)
        assertTrue(v.stylized)
    }
    @Test fun realismGuardClampsManualNaturalControls(){
        val v=VoiceExpressionResolver.resolve("natural",1f,99f,99f,99f,99f,99f,9f,"","",true)
        assertEquals(4f,v.pitchSemitones,0f)
        assertEquals(.38f,v.breathiness,0f)
        assertEquals(.72f,v.tone,0f)
        assertEquals(.18f,v.rasp,0f)
        assertEquals(1.25f,v.energy,0f)
        assertEquals(1.18f,v.pace,0f)
    }
    @Test fun disablingRealismAllowsWiderManualRange(){
        val v=VoiceExpressionResolver.resolve("natural",1f,99f,99f,99f,99f,99f,9f,"","",false)
        assertEquals(12f,v.pitchSemitones,0f)
        assertEquals(.75f,v.breathiness,0f)
        assertEquals(1f,v.tone,0f)
        assertEquals(.5f,v.rasp,0f)
        assertEquals(1.45f,v.energy,0f)
        assertEquals(1.4f,v.pace,0f)
    }
    @Test fun neutralDspIsTransparent(){
        val input=floatArrayOf(-.4f,-.1f,0f,.2f,.5f)
        assertArrayEquals(input,VoiceDspProcessor(24000,VoiceExpression("Natural")).process(input),0f)
    }
    @Test fun expressiveDspStaysFiniteAndAvoidsHardClipping(){
        val input=FloatArray(4800){i->kotlin.math.sin(i*.05).toFloat()*.8f}
        val expression=VoiceExpression("test",breathiness=.35f,tone=.6f,rasp=.15f,energy=1.2f)
        val output=VoiceDspProcessor(24000,expression).process(input)
        assertEquals(input.size,output.size)
        assertTrue(output.all{it.isFinite() && it in -.985f.. .985f})
    }
    @Test fun pitchFactorCoversRequestedManualRange(){
        assertEquals(2f,VoiceDspProcessor.pitchFactor(12f),.001f)
        assertEquals(.5f,VoiceDspProcessor.pitchFactor(-12f),.001f)
    }
}
