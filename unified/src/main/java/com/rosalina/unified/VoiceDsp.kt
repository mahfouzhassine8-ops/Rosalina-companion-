package com.rosalina.unified

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

internal class VoiceDspProcessor(private val sampleRate:Int,private val expression:VoiceExpression) {
    private var low=0f
    private var envelope=0f
    private var breathEnvelope=0f
    private var previousNoise=0f
    private var noiseState=0x13579BDF
    private var fryPhase=0.0
    private fun noise():Float {
        var x=noiseState;x=x xor (x shl 13);x=x xor (x ushr 17);x=x xor (x shl 5);noiseState=x
        return ((x ushr 8) and 0x00ffffff)/8388607.5f-1f
    }
    fun process(input:FloatArray):FloatArray {
        val e=expression
        if(e.breathiness==0f && e.tone==0f && e.rasp==0f && e.energy==1f)return input.copyOf()
        val output=FloatArray(input.size);val toneAmount=abs(e.tone);val twoPi=2.0*PI
        for(i in input.indices) {
            val x=input[i].coerceIn(-1f,1f)
            envelope=envelope*.994f+abs(x)*.006f
            val target=if(envelope>.018f)1f else 0f
            breathEnvelope+=(target-breathEnvelope)*.012f
            low+=.1f*(x-low)
            var y=if(e.tone<0f) x*(1f-.34f*toneAmount)+low*(.34f*toneAmount) else x+(x-low)*(.24f*toneAmount)
            if(e.breathiness>0f && breathEnvelope>.02f) {
                val n=noise();val airy=n-previousNoise*.78f;previousNoise=n
                y+=airy*e.breathiness*(.004f+.018f*envelope)*breathEnvelope
            }
            if(e.rasp>0f && envelope>.02f) {
                val drive=1f+e.rasp*1.5f
                y=(tanh((y*drive).toDouble())/drive).toFloat()
                fryPhase+=twoPi*(40.0+e.rasp*12.0)/sampleRate
                if(fryPhase>twoPi)fryPhase-=twoPi
                y+=(sin(fryPhase)*e.rasp*(.003+.009*envelope)).toFloat()
            }
            y*=e.energy
            output[i]=tanh((y*1.045f).toDouble()).toFloat().coerceIn(-.985f,.985f)
        }
        return output
    }
    companion object {
        fun pitchFactor(semitones:Float)=Math.pow(2.0,(semitones.coerceIn(-12f,12f)/12f).toDouble()).toFloat().coerceIn(.5f,2f)
    }
}
