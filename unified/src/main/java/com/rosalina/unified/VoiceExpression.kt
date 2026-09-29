package com.rosalina.unified

internal data class VoiceExpression(
    val name:String,
    val pitchSemitones:Float=0f,
    val breathiness:Float=0f,
    val tone:Float=0f,
    val rasp:Float=0f,
    val energy:Float=1f,
    val pace:Float=1f,
    val intensity:Float=1f,
    val stylized:Boolean=false,
    val performance:PerformanceState?=null
) {
    fun safe(realismGuard:Boolean=true):VoiceExpression {
        val pitchLimit=if(stylized || !realismGuard)12f else 4f
        val breathLimit=if(stylized || !realismGuard).75f else .38f
        val raspLimit=if(stylized || !realismGuard).5f else .18f
        return copy(
            pitchSemitones=pitchSemitones.coerceIn(-pitchLimit,pitchLimit),
            breathiness=breathiness.coerceIn(0f,breathLimit),
            tone=tone.coerceIn(if(realismGuard && !stylized)-.72f else -1f,if(realismGuard && !stylized).72f else 1f),
            rasp=rasp.coerceIn(0f,raspLimit),
            energy=energy.coerceIn(if(realismGuard && !stylized).72f else .55f,if(realismGuard && !stylized)1.25f else 1.45f),
            pace=pace.coerceIn(if(realismGuard && !stylized).82f else .7f,if(realismGuard && !stylized)1.18f else 1.4f),
            intensity=intensity.coerceIn(0f,1f)
        )
    }
    fun summary():String {
        val p=(if(pitchSemitones>=0) "+" else "")+"%.1f".format(pitchSemitones)
        return name+" · pitch "+p+" st · breath "+(breathiness*100).toInt()+"% · tone "+"%.2f".format(tone)+" · rasp "+(rasp*100).toInt()+"% · energy "+"%.2f".format(energy)+" · pace "+"%.2f".format(pace)+"×"+if(stylized)" · stylized" else ""
    }
}

internal object VoiceExpressionResolver {
    private val intimate=Regex("\\b(intimate|sensual|seductive|sexy|naughty|bedroom|whisper|whispering|closer|kiss|kissing|desire|tempt|tease me|turn me on)\\b",RegexOption.IGNORE_CASE)
    private val playful=Regex("\\b(playful|cute|silly|giggle|giggling|tease|teasing|excited|yay|hehe|haha|lol)\\b",RegexOption.IGNORE_CASE)
    private val gentle=Regex("\\b(gentle|gently|soft|softly|calm|calmly|comfort|relax|sleepy|tender)\\b",RegexOption.IGNORE_CASE)
    private val serious=Regex("\\b(serious|firm|stern|important|listen carefully|warning)\\b",RegexOption.IGNORE_CASE)

    private fun preset(name:String):VoiceExpression=when(name.lowercase()) {
        "warm"->VoiceExpression("Warm",pitchSemitones=-.4f,breathiness=.05f,tone=-.32f,rasp=.02f,energy=.96f,pace=.98f)
        "breathy"->VoiceExpression("Breathy",pitchSemitones=.15f,breathiness=.30f,tone=-.12f,energy=.88f,pace=.94f)
        "deep"->VoiceExpression("Low & smoky",pitchSemitones=-3f,breathiness=.10f,tone=-.42f,rasp=.11f,energy=.9f,pace=.92f)
        "bright"->VoiceExpression("Bright & playful",pitchSemitones=2f,breathiness=.025f,tone=.38f,energy=1.06f,pace=1.04f)
        "squeaky"->VoiceExpression("Squeaky",pitchSemitones=7f,breathiness=.01f,tone=.62f,energy=1.05f,pace=1.04f,stylized=true)
        "intimate"->VoiceExpression("Intimate",pitchSemitones=-1.1f,breathiness=.30f,tone=-.34f,rasp=.055f,energy=.82f,pace=.88f)
        "natural"->VoiceExpression("Natural")
        else->VoiceExpression("Adaptive")
    }

    fun resolve(
        mode:String,intensity:Float,pitchTrim:Float,breathTrim:Float,toneTrim:Float,raspTrim:Float,
        energyTrim:Float,pace:Float,userText:String,spokenText:String,realismGuard:Boolean=true
    ):VoiceExpression {
        val combined=userText+" "+spokenText
        val raw=if(mode.equals("adaptive",true)) {
            when {
                intimate.containsMatchIn(combined)->preset("intimate").copy(name="Adaptive · intimate")
                playful.containsMatchIn(combined)->preset("bright").copy(name="Adaptive · playful")
                gentle.containsMatchIn(combined)->preset("breathy").copy(name="Adaptive · gentle")
                serious.containsMatchIn(combined)->VoiceExpression("Adaptive · grounded",pitchSemitones=-.8f,tone=-.2f,rasp=.025f,energy=1.03f,pace=.96f)
                else->VoiceExpression("Adaptive · natural")
            }
        } else preset(mode)
        val amount=intensity.coerceIn(0f,1f)
        fun blend(value:Float,neutral:Float)=neutral+(value-neutral)*amount
        return raw.copy(
            pitchSemitones=blend(raw.pitchSemitones,0f)+pitchTrim,
            breathiness=blend(raw.breathiness,0f)+breathTrim,
            tone=blend(raw.tone,0f)+toneTrim,
            rasp=blend(raw.rasp,0f)+raspTrim,
            energy=blend(raw.energy,1f)+energyTrim,
            pace=blend(raw.pace,1f)*pace
        ).safe(realismGuard)
    }
}
