package com.rosalina.unified

/** Presentation is derived from engine events, never from an animation timer. */
internal enum class CompanionPhase { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED }
internal enum class RosalinaExpression(val label:String) {
    NEUTRAL("Neutral"), HAPPY("Happy"), SHY("Shy"), TEASING("Teasing"), SAD("Sad"),
    SURPRISED("Surprised"), THINKING("Thinking"), BLUSH("Blush"), LISTENING("Listening"),
    SPEAKING("Speaking"), EYES_CLOSED("Eyes Closed"), WINK("Wink")
}
internal enum class CompanionDestination(val label:String) {
    CHAT("Chat"), LIVE_VOICE("Live Voice"), SETTINGS("Settings")
}
internal data class CompanionSnapshot(
    val taskId:String="", val utteranceId:String="", val phase:CompanionPhase=CompanionPhase.IDLE,
    val microphoneActive:Boolean=false, val muted:Boolean=false, val processing:Boolean=false,
    val playbackActive:Boolean=false, val energy:Float=0f, val energySource:String="unavailable",
    val expression:RosalinaExpression=RosalinaExpression.NEUTRAL,
    val engine:String="", val changedAt:Long=0, val lastEvent:String="Idle", val lastInterruption:Long=0
)
/** Task and utterance generations prevent late TTS callbacks from reviving cancelled speech. */
internal class CompanionRuntime(private val changed:()->Unit={}) {
    @Volatile var snapshot=CompanionSnapshot(); private set
    @Synchronized private fun put(next:CompanionSnapshot) { snapshot=next;changed() }
    @Synchronized fun begin(id:String,now:Long) { put(CompanionSnapshot(taskId=id,changedAt=now,lastEvent="Task started")) }
    @Synchronized fun microphone(id:String,active:Boolean,muted:Boolean,now:Long) {
        val s=snapshot;if(s.taskId!=id)return
        put(resolve(s.copy(microphoneActive=active,muted=muted,lastEvent=if(muted)"Microphone muted" else if(active)"Microphone active" else "Microphone released"),now))
    }
    @Synchronized fun processing(id:String,active:Boolean,now:Long) {
        val s=snapshot;if(s.taskId!=id)return
        put(resolve(s.copy(processing=active,lastEvent=if(active)"Accepted input / inference" else "Inference complete"),now))
    }
    @Synchronized fun listen(id:String,now:Long) {
        val s=snapshot;if(s.taskId!=id)return
        put(resolve(s.copy(processing=false,lastEvent="Waiting for user utterance"),now))
    }
    @Synchronized fun preparingSpeech(id:String,utterance:String,expression:RosalinaExpression,engine:String,now:Long) {
        val s=snapshot;if(s.taskId!=id)return
        put(resolve(s.copy(utteranceId=utterance,playbackActive=false,energy=0f,energySource="unavailable",expression=expression,engine=engine,lastEvent="Speech synthesis preparing"),now))
    }
    @Synchronized fun playback(id:String,utterance:String,playing:Boolean,now:Long) {
        val s=snapshot;if(s.taskId!=id || s.utteranceId!=utterance)return
        put(resolve(s.copy(playbackActive=playing,energy=if(playing)s.energy else 0f,lastEvent=if(playing)"Playback started" else "Playback completed"),now))
    }
    @Synchronized fun energy(id:String,utterance:String,value:Float,source:String) {
        val s=snapshot;if(s.taskId!=id || s.utteranceId!=utterance || !s.playbackActive)return
        val safe=if(value.isFinite())value.coerceIn(0f,1f) else 0f
        put(s.copy(energy=safe,energySource=source))
    }
    @Synchronized fun interrupt(id:String,now:Long) {
        val s=snapshot;if(s.taskId!=id)return
        // Invalidate the utterance before invoking stop on either engine.
        put(s.copy(utteranceId="",phase=CompanionPhase.INTERRUPTED,playbackActive=false,processing=false,
            energy=0f,energySource="unavailable",changedAt=now,lastEvent="User interruption",lastInterruption=now))
    }
    @Synchronized fun end(id:String,now:Long) {
        if(snapshot.taskId!=id)return
        put(CompanionSnapshot(changedAt=now,lastEvent="Session ended",lastInterruption=snapshot.lastInterruption))
    }
    private fun resolve(s:CompanionSnapshot,now:Long):CompanionSnapshot {
        val phase=when {s.playbackActive->CompanionPhase.SPEAKING;s.processing->CompanionPhase.THINKING
            s.microphoneActive && !s.muted->CompanionPhase.LISTENING;else->CompanionPhase.IDLE}
        return s.copy(phase=phase,changedAt=if(phase==snapshot.phase)snapshot.changedAt else now)
    }
    fun summary():String {val s=snapshot;return "phase=${s.phase}; microphone=${s.microphoneActive}; muted=${s.muted}; playback=${s.playbackActive}; expression=${s.expression.label}; lip input=${s.energySource}; transition=${s.changedAt} ${s.lastEvent}; last interruption=${s.lastInterruption}"}
}

internal object ExpressionDirector {
    fun choose(text:String,style:String,flirty:Boolean):RosalinaExpression {
        val t=text.lowercase();val v=style.lowercase()
        return when {
            listOf("sorry","that sounds difficult","i'm here to listen").any{it in t}->RosalinaExpression.SAD
            "reassur" in v || "warm" in v -> if(flirty)RosalinaExpression.BLUSH else RosalinaExpression.HAPPY
            "teas" in v && flirty ->RosalinaExpression.TEASING
            "play" in v ->if(flirty)RosalinaExpression.WINK else RosalinaExpression.HAPPY
            "uncertain" in v || "think" in v->RosalinaExpression.THINKING
            "excited" in v || t.endsWith("!")->RosalinaExpression.HAPPY
            "surpris" in v->RosalinaExpression.SURPRISED
            "shy" in v && flirty->RosalinaExpression.SHY
            else->RosalinaExpression.SPEAKING
        }
    }
}

internal enum class MotionTier(val fps:Int,val secondary:Boolean) {
    NORMAL(20,true), LIGHT(15,true), REDUCED(10,false), MINIMAL(6,false), STATIC(0,false)
}
/** Immediate reduction, deliberately slow recovery. This controls decoration, not thermal safeguards. */
internal class MotionBudget {
    private var tier=MotionTier.NORMAL;private var recoverSince:Long?=null;private var recoveryTarget:MotionTier?=null;private var disabled=false
    fun sample(thermal:Int,enabled:Boolean,now:Long):MotionTier {
        val target=if(!enabled)MotionTier.STATIC else when {thermal>=4->MotionTier.STATIC;thermal>=3->MotionTier.MINIMAL
            thermal>=2->MotionTier.REDUCED;thermal==1->MotionTier.LIGHT;else->MotionTier.NORMAL}
        if(!enabled){tier=MotionTier.STATIC;disabled=true;recoverSince=null;recoveryTarget=null;return tier}
        if(disabled){disabled=false;tier=target;recoverSince=null;recoveryTarget=null;return tier}
        if(target.ordinal>=tier.ordinal){tier=target;recoverSince=null;recoveryTarget=null}
        else {
            if(recoveryTarget!=target){recoveryTarget=target;recoverSince=now}
            val since=recoverSince ?:now
            if(now-since>=20_000){tier=target;recoverSince=null;recoveryTarget=null}
        }
        return tier
    }
}

internal object CompanionPersonality {
    fun system(base:String,flirty:Boolean):String=if(!flirty)base else base+"\n"+
        "Optional companion style: Rosalina is a fictional adult AI companion. Be warm, affectionate, playful and gently teasing when welcomed. Keep romance non-explicit, respect a request to stop, and switch to straightforward help for serious topics. Do not pressure the user, claim human feelings or exclusivity, or discourage real-world relationships."
}
