package com.rosalina.unified

internal data class PhoneCapabilityProfile(
    val label:String,
    val chatTokenCap:Int,
    val liveTokenCap:Int,
    val endpointFloorMs:Int,
    val clauseChars:Int,
    val overlapListening:Boolean,
    val allowPrewarm:Boolean
) {
    fun summary()="$label · chat≤$chatTokenCap · live≤$liveTokenCap · ${if(overlapListening)"full-duplex" else "turn-duplex"}"
}

internal object PhoneCapabilityPolicy {
    const val LIVE_EMERGENCY_FLOOR_BYTES=2_000_000_000L
    fun canStartLive(resources:Resources)=!resources.low && resources.available>=LIVE_EMERGENCY_FLOOR_BYTES
    fun choose(resources:Resources, mode:String="adaptive"):PhoneCapabilityProfile {
        val normalized=mode.lowercase()
        if(normalized=="cool") return PhoneCapabilityProfile("Phone Cool",512,160,1000,160,false,false)
        if(normalized=="maximum") return PhoneCapabilityProfile("Phone Maximum",1536,512,700,100,true,true)

        val available=resources.available
        val thermal=resources.thermal
        return when {
            resources.low || available<4_500_000_000L || thermal>=3 ->
                PhoneCapabilityProfile("Adaptive · Cool",384,144,1100,180,false,false)
            available<7_000_000_000L || thermal>=2 ->
                PhoneCapabilityProfile("Adaptive · Balanced",768,224,900,145,false,true)
            else ->
                PhoneCapabilityProfile("Adaptive · Full",1280,384,760,110,true,true)
        }
    }
}
