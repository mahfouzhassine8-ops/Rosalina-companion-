package com.rosalina.unified

import java.util.UUID
internal enum class TaskKind { CHAT, CREATE, EDIT, ANIMATE, VOICE, IMPORT }
internal data class RenderProfile(val label:String,val width:Int,val height:Int,val steps:Int,val threads:Int) {
    companion object {
        val Draft=RenderProfile("Phone Safe · candidate",384,384,8,2)
        val Standard=RenderProfile("Standard",512,512,12,4)
    }
}
internal data class TaskRequest(
    val id:String=UUID.randomUUID().toString(),val kind:TaskKind,val prompt:String="",val photo:String="",
    val profile:RenderProfile=RenderProfile.Draft,val seconds:Int=6,val width:Int=256,val height:Int=256,
    val seed:Long=42,val strength:Float=.65f,val backend:String="auto",val modelKey:String="",val uri:String="",val voiceStyle:String?=null
)
internal data class TaskState(
    val id:String="",val kind:TaskKind?=null,val busy:Boolean=false,val stopping:Boolean=false,
    val stage:String="Ready",val percent:Int?=null,val step:Int=0,val total:Int=0,val elapsedMs:Long=0,
    val eta:String="",val thermal:Int=-1,val thermalAt:Long=0,val availableBytes:Long=0,val totalBytes:Long=0,
    val pid:Int=0,val backend:String="Not selected",val logTail:String="",val error:String="",
    val result:String="",val answer:String="",val voiceStage:String="",val quarantined:Boolean=false,
    val lastPid:Int=0,val lastStage:String="",val lastStep:Int=0,val lastTotal:Int=0,val lastPercent:Int?=null,
    val revision:Long=0,val workHint:String="",val avatarEnergy:Float=0f
)
internal object ThermalPolicy {
    /** Severe is a throttling signal. Critical+ is the hard render stop. */
    fun blocks(status:Int)=status>=4
    fun label(status:Int)=when(status){
        0->"Normal"
        1->"Light"
        2->"Moderate · Android reports throttling possible"
        3->"Severe · Android thermal status; throttling"
        4->"Critical · render stop"
        5->"Emergency · render stop"
        6->"Shutdown · render stop"
        else->"Unavailable"
    }
}
internal object Route {
    private val polite=Regex("^(?:(?:please|hey rosalina|rosalina)[, ]+|(?:can|could|would) you (?:please )?)+",RegexOption.IGNORE_CASE)
    private val create=Regex("^(?:/image\\s+|(?:create|generate|draw|make)\\s+(?:me\\s+)?(?:a|an|the)?\\s*(?:picture|image|photo|illustration|painting)\\b)",RegexOption.IGNORE_CASE)
    private val edit=Regex("^(?:/edit\\s+|(?:edit|transform)\\s+(?:this|the|my|a)\\s+(?:photo|image|picture)\\b)",RegexOption.IGNORE_CASE)
    private val animate=Regex("^(?:/animate\\s+|animate\\s+(?:this|the|my|a)\\s+(?:photo|image|picture)\\b)",RegexOption.IGNORE_CASE)
    fun kind(text:String):TaskKind {
        val p=text.trim().replace(polite,"")
        return when{animate.containsMatchIn(p)->TaskKind.ANIMATE;edit.containsMatchIn(p)->TaskKind.EDIT;create.containsMatchIn(p)->TaskKind.CREATE;else->TaskKind.CHAT}
    }
    fun seconds(text:String)=Regex("\\b(6|8|10|six|eight|ten)\\s*(?:seconds?|s)\\b",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()?.let{when(it){"six"->6;"eight"->8;"ten"->10;else->it.toInt()}} ?:6
}
internal class ProgressParser(private val requestedSteps:Int) {
    var stage="Preparing";private set
    var percent:Int?=null;private set
    var step=0;private set
    var total=0;private set
    fun consume(line:String):Boolean {
        if(line.startsWith("@@STAGE ")){stage=line.removePrefix("@@STAGE ").take(180);percent=null;step=0;total=0;return true}
        if(line.startsWith("@@SAMPLE ")) {
            val p=line.split(' ');val n=p.getOrNull(1)?.toIntOrNull() ?:return false;val t=p.getOrNull(2)?.toIntOrNull() ?:return false
            if(t !in 1..requestedSteps || n !in 1..t || (total!=0 && (total!=t || n<step)))return false
            step=n;total=t;stage="Sampling · step $n of $t";percent=n*100/t;return true
        }
        return false
    }
}
internal class EngineLease {
    private var owner:String?=null
    @Synchronized fun acquire(id:String):Boolean{if(owner!=null)return false;owner=id;return true}
    @Synchronized fun release(id:String):Boolean{if(owner!=id)return false;owner=null;return true}
    @Synchronized fun current():String?=owner
}
internal object SpeechText {
    private val punctuation=Regex("[.!?](?:[\\\"')\\]]*)\\s")
    private val thinking=Regex("<think>.*?(?:</think>|$)",RegexOption.DOT_MATCHES_ALL)
    private val code=Regex("```.*?(?:```|$)",RegexOption.DOT_MATCHES_ALL)
    private val marks=Regex("[\\*`#]")
    fun visible(text:String)=text.replace(thinking,"").trimStart()
    fun spoken(text:String)=visible(text).replace(code,"").replace(marks,"")
    fun cut(text:String):Int {
        val match=punctuation.find(text)
        if(match!=null && match.range.last>=20)return match.range.last+1
        if(text.length>=220)return text.lastIndexOf(' ',220).takeIf{it>=60} ?:220
        return 0
    }
    fun history(turns:List<Pair<String,String>>)=turns.takeLast(12).joinToString("\n"){(role,text)->"$role: ${text.take(1200)}"}.takeLast(6000)
}
