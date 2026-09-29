package com.rosalina.unified

/** Auditionable delivery presets, not claims of distinct trained speakers or timbres. */
internal enum class NaturalVoiceStyle(val key:String,val label:String,val pace:Float,val volume:Float) {
    NATURAL("natural","Natural / realistic",1f,1f),
    WARM("warm","Warm / conversational",.98f,.97f),
    ANIME("anime","Soft anime-inspired / natural pitch",1.02f,.96f),
    BRIGHT("bright","Bright / playful",1.04f,1f),
    GROUNDED("grounded","Grounded / measured",.94f,.98f),
    SOFT("soft","Soft / gentle",.95f,.86f),
    INTIMATE("intimate","Intimate / quiet",.93f,.8f);

    fun apply(performance:PerformanceState):PerformanceState {
        val p=performance.bounded()
        // Style never rewrites delivery intent, emotion, reaction tags, or pitch.
        return p.copy(pace=p.pace*pace,volume=p.volume*volume).bounded()
    }
    companion object {
        fun fromKey(key:String?)=entries.firstOrNull{it.key==key} ?: NATURAL
    }
}
