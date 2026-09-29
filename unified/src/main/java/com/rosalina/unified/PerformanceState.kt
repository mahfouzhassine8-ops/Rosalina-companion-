package com.rosalina.unified

/** One immutable decision is carried to speech and to the renderer for the same utterance. */
internal enum class Delivery { NATURAL, SOFT, EMPHATIC, THOUGHTFUL, PLAYFUL, WHISPER, SIGH, CHUCKLE, LAUGH }
internal enum class Gesture { NONE, ATTENTIVE, RESTRAINED, OPEN_HAND, PLAYFUL, REASSURING, WALK, TURN }
internal data class PerformanceState(
    val emotion:String="neutral", val intensity:Float=.35f, val pace:Float=1f, val volume:Float=.95f,
    val delivery:Delivery=Delivery.NATURAL, val gesture:Gesture=Gesture.NONE,
    val expression:RosalinaExpression=RosalinaExpression.NEUTRAL
) {
    fun bounded()=copy(intensity=intensity.finite(.35f).coerceIn(0f,1f),pace=pace.finite(1f).coerceIn(.8f,1.2f),volume=volume.finite(.95f).coerceIn(.55f,1f))
    fun atPhase(phase:CompanionPhase)=when(phase) {
        CompanionPhase.LISTENING->copy(gesture=Gesture.ATTENTIVE,expression=RosalinaExpression.LISTENING,intensity=.15f)
        CompanionPhase.THINKING->copy(gesture=Gesture.NONE,expression=RosalinaExpression.THINKING,intensity=.12f)
        CompanionPhase.INTERRUPTED->copy(gesture=Gesture.NONE,intensity=0f,expression=RosalinaExpression.NEUTRAL)
        CompanionPhase.IDLE->PerformanceState()
        CompanionPhase.SPEAKING->this
    }
    fun summary()="emotion=$emotion; intensity=$intensity; pace=$pace; volume=$volume; delivery=$delivery; gesture=$gesture; expression=${expression.label}"
    private fun Float.finite(fallback:Float)=if(isFinite())this else fallback
}
internal object PerformanceDirector {
    private fun has(text:String,pattern:String)=Regex(pattern,RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun decide(user:String,spoken:String,flirty:Boolean,pace:Float=1f):PerformanceState {
        // Requests for help or distress take precedence over playful wording in the same turn.
        val serious=has(user,"\\b(grief|grieving|depressed|anxious|panic|emergency|funeral|hurt|worried|scared)\\b") ||
            has(spoken,"\\b(i'm sorry|that sounds difficult|sorry to hear|here to listen)\\b")
        val p=when {
            serious->PerformanceState("reassuring",.2f,.94f,.9f,Delivery.SOFT,Gesture.REASSURING,RosalinaExpression.SAD)
            has(user,"\\b(?:please |a |little |small )chuckle\\b")->PerformanceState("happy",.45f,1f,.95f,Delivery.CHUCKLE,Gesture.OPEN_HAND,RosalinaExpression.HAPPY)
            has(user,"\\b(?:please laugh|with a laugh|make me laugh)\\b") && has(spoken,"[!]|\\blaugh\\b")->PerformanceState("happy",.55f,1.02f,.95f,Delivery.LAUGH,Gesture.OPEN_HAND,RosalinaExpression.HAPPY)
            has(user,"\\b(?:give me a|let out a|with a|please) (?:small )?sigh\\b")->PerformanceState("subdued",.2f,.94f,.85f,Delivery.SIGH,Gesture.RESTRAINED,RosalinaExpression.EYES_CLOSED)
            has(user,"\\b(whisper|whispering)\\b")->PerformanceState("soft",.2f,.93f,.8f,Delivery.WHISPER,Gesture.RESTRAINED,if(flirty)RosalinaExpression.SHY else RosalinaExpression.SPEAKING)
            has(user,"\\b(softly|gentle|gently|softer)\\b")->PerformanceState("gentle",.2f,.95f,.82f,Delivery.SOFT,Gesture.RESTRAINED,RosalinaExpression.SPEAKING)
            has(user,"\\b(walk|walking)\\b")->PerformanceState("natural",.3f,1f,.94f,Delivery.NATURAL,Gesture.WALK,RosalinaExpression.HAPPY)
            has(user,"\\b(turn around|turning around)\\b")->PerformanceState("natural",.3f,1f,.94f,Delivery.NATURAL,Gesture.TURN,RosalinaExpression.HAPPY)
            has(spoken,"\\b(not sure|perhaps|let me think|i wonder|might be)\\b")->PerformanceState("thoughtful",.25f,.97f,.93f,Delivery.THOUGHTFUL,Gesture.NONE,RosalinaExpression.THINKING)
            flirty && has(user,"\\b(wink|winking)\\b")->PerformanceState("playful",.4f,1.02f,.94f,Delivery.PLAYFUL,Gesture.PLAYFUL,RosalinaExpression.WINK)
            flirty && has(user,"\\b(tease|teasing|mischievous|flirt)\\b")->PerformanceState("teasing",.45f,1.02f,.94f,Delivery.PLAYFUL,Gesture.PLAYFUL,RosalinaExpression.TEASING)
            flirty && has(spoken,"\\b(blush|blushing)\\b")->PerformanceState("shy",.25f,.96f,.85f,Delivery.SOFT,Gesture.RESTRAINED,RosalinaExpression.BLUSH)
            flirty && has(user,"\\b(shy|coy)\\b")->PerformanceState("shy",.25f,.95f,.85f,Delivery.SOFT,Gesture.RESTRAINED,RosalinaExpression.SHY)
            has(spoken,"\\b(wow|what a surprise|unexpected)\\b")->PerformanceState("surprised",.55f,1.04f,.98f,Delivery.EMPHATIC,Gesture.OPEN_HAND,RosalinaExpression.SURPRISED)
            has(spoken,"\\b(glad|great news|wonderful|congratulations|lovely)\\b")->PerformanceState("happy",.45f,1.02f,.97f,Delivery.PLAYFUL,Gesture.OPEN_HAND,RosalinaExpression.HAPPY)
            else->PerformanceState(expression=RosalinaExpression.SPEAKING)
        }
        return p.copy(pace=p.pace*pace).bounded()
    }
    /** Only engine-native tags are sent. Unsupported effects are not faked using pitch shifts. */
    fun localText(text:String,p:PerformanceState):String=when(p.delivery) {
        Delivery.SIGH->"$text [sigh]"; Delivery.CHUCKLE->"$text [chuckle]";Delivery.LAUGH->"$text [laugh]"
        else->text
    }
    fun onlineText(text:String,p:PerformanceState,model:String):String {
        if(model!="eleven_v3")return text
        val tag=when(p.delivery){Delivery.WHISPER->"[whispers]";Delivery.SIGH->"[sighs]";Delivery.CHUCKLE,Delivery.LAUGH->"[laughs]";else->""}
        return if(tag.isBlank())text else "$tag $text"
    }
}

/** PCM-derived articulation estimates, not asserted phonemes or engine-provided visemes. */
internal data class MouthPose(val open:Float=0f,val wide:Float=0f,val round:Float=0f) {
    fun bounded()=MouthPose(open.safe(),wide.safe(),round.safe())
    private fun Float.safe()=if(isFinite())coerceIn(0f,1f) else 0f
}
internal object PcmMouth {
    fun estimate(energy:Float,zeroCrossingRate:Float):MouthPose {
        val e=energy.takeIf{it.isFinite()}?.coerceIn(0f,1f) ?:0f
        if(e<.02f)return MouthPose()
        val z=zeroCrossingRate.takeIf{it.isFinite()}?.coerceIn(0f,1f) ?:0f
        return MouthPose(e,(z*5f).coerceIn(0f,1f)*e,(1f-z*8f).coerceIn(0f,1f)*e*.65f)
    }
}
