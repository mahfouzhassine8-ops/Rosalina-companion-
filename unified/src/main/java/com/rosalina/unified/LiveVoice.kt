package com.rosalina.unified

internal data class LiveVoiceTuning(
    val endpointMs:Int=820,
    val clauseChars:Int=96,
    val minClauseChars:Int=14,
    val firstChunkChars:Int=44
) {
    fun safe()=copy(
        endpointMs=endpointMs.coerceIn(550,1400),
        clauseChars=clauseChars.coerceIn(64,180),
        minClauseChars=minClauseChars.coerceIn(12,48),
        firstChunkChars=firstChunkChars.coerceIn(32,72)
    )
}

internal object LiveSpeechChunker {
    private val sentence=Regex("[.!?](?:[\\\"')\\]]*)\\s")
    private val clause=Regex("[,;:](?:[\\\"')\\]]*)\\s")

    private fun wordBoundary(text:String,target:Int,min:Int):Int {
        val at=text.lastIndexOf(' ',target.coerceAtMost(text.lastIndex))
        return at.takeIf{it>=min} ?:0
    }

    fun cut(text:String,tuning:LiveVoiceTuning=LiveVoiceTuning(),eager:Boolean=false):Int {
        val t=tuning.safe()
        sentence.find(text)?.let{if(it.range.last+1>=t.minClauseChars)return it.range.last+1}
        clause.find(text)?.let{if(it.range.last+1>=t.minClauseChars)return it.range.last+1}
        val target=if(eager)t.firstChunkChars else t.clauseChars
        if(text.length>=target) {
            val boundary=wordBoundary(text,target,t.minClauseChars)
            if(boundary>0)return boundary+1
            if(text.length>=target+16)return target
        }
        return 0
    }
}
