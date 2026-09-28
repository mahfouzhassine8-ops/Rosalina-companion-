package com.rosalina.unified

internal data class LiveVoiceTuning(
    val endpointMs:Int=820,
    val clauseChars:Int=120,
    val minClauseChars:Int=32
) {
    fun safe()=copy(
        endpointMs=endpointMs.coerceIn(550,1400),
        clauseChars=clauseChars.coerceIn(70,220),
        minClauseChars=minClauseChars.coerceIn(16,64)
    )
}

internal object LiveSpeechChunker {
    private val sentence=Regex("[.!?](?:[\\\"')\\]]*)\\s")
    private val clause=Regex("[,;:](?:[\\\"')\\]]*)\\s")

    fun cut(text:String,tuning:LiveVoiceTuning=LiveVoiceTuning()):Int {
        val t=tuning.safe()
        sentence.find(text)?.let{if(it.range.last+1>=t.minClauseChars)return it.range.last+1}
        clause.find(text)?.let{if(it.range.last+1>=t.minClauseChars)return it.range.last+1}
        if(text.length>=t.clauseChars) {
            val boundary=text.lastIndexOf(' ',t.clauseChars).takeIf{it>=t.minClauseChars}
            return boundary ?:t.clauseChars
        }
        return 0
    }
}
