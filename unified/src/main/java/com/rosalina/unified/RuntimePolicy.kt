package com.rosalina.unified

import kotlin.math.max

/** Coalesce IPC, not model work. The first text and final remainder are never delayed. */
internal class StreamBatch(private val intervalMs:Long=50) {
    private val pending=StringBuilder()
    private var last=Long.MIN_VALUE
    fun append(text:String,now:Long):String? {
        pending.append(text)
        if(last==Long.MIN_VALUE || now-last>=intervalMs || pending.length>=512) {
            last=now
            return flush()
        }
        return null
    }
    fun flush():String? = if(pending.isEmpty())null else pending.toString().also{pending.setLength(0)}
}

/** Sustained work budget, not a resolution/quality change. Severe still stops, never throttles through. */
internal object WorkBudget {
    fun percent(thermal:Int,gpu:Boolean,headroom:Float=Float.NaN):Int {
        if(ThermalPolicy.blocks(thermal))return 0
        val effective=if(headroom.isFinite() && headroom>=0.85f)max(thermal,2)else thermal
        return if(gpu)when(effective){2->65;1->85;else->100}
            else when(effective){2->30;1->55;else->75}
    }
    fun shouldPause(elapsedMs:Long,percent:Int):Boolean = elapsedMs%1000 >= percent.coerceIn(0,100)*10L
}

/** Energy gate for local voice capture. Speech recognition, not this gate, supplies words. */
internal class VoiceGate {
    private var noise=120.0
    private var speechMs=0
    private var quietMs=0
    var started=false;private set
    fun accept(rms:Double,millis:Int,allowed:Boolean=true):Boolean {
        if(!allowed){speechMs=0;quietMs=0;return false}
        val threshold=max(500.0,noise*3.2)
        if(rms>threshold){speechMs+=millis;quietMs=0}else {
            if(!started){noise=(noise*.97+rms*.03).coerceIn(40.0,600.0);speechMs=max(0,speechMs-millis*2)}
            quietMs+=millis
        }
        val onset=!started && speechMs>=240
        if(onset)started=true
        return onset
    }
    fun finished()=started && quietMs>=1100
}

internal object EchoText {
    private fun words(text:String)=text.lowercase().replace(Regex("[^a-z0-9 ]")," ").split(Regex("\\s+")).filter{it.isNotBlank()}
    fun resemblesOutput(input:String,output:String):Boolean {
        val a=words(input);val b=words(output).takeLast(160)
        if(a.size<4 || b.isEmpty())return false
        val normalized=a.joinToString(" ")
        if(b.joinToString(" ").contains(normalized))return true
        return a.size>=6 && a.count{it in b}.toDouble()/a.size>=.9
    }
}
