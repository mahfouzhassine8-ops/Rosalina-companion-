package com.rosalina.unified

import android.content.SharedPreferences
import kotlin.math.min

internal data class LiveLearningSnapshot(
    val turns:Int,
    val interruptions:Int,
    val responseTokenCap:Int,
    val averageFirstTextMs:Long
) {
    val interruptionRate:Float get()=if(turns<=0)0f else interruptions.toFloat()/turns
    fun summary()="Adaptive Live turns=$turns; interruptions=$interruptions; interruption rate=${"%.2f".format(interruptionRate)}; learned response cap=$responseTokenCap; average first text=$averageFirstTextMs ms"
}

internal object LiveLearningPolicy {
    const val DEFAULT_CAP=224
    const val MIN_CAP=128
    const val MAX_CAP=256

    fun nextCap(current:Int,interrupted:Boolean):Int {
        val now=current.coerceIn(MIN_CAP,MAX_CAP)
        return if(interrupted)(now-24).coerceAtLeast(MIN_CAP) else (now+8).coerceAtMost(MAX_CAP)
    }

    fun effectiveCap(configured:Int,learned:Int,enabled:Boolean):Int {
        val normal=configured.coerceIn(64,4096)
        if(!enabled)return min(normal,MAX_CAP)
        return min(normal,learned.coerceIn(MIN_CAP,MAX_CAP))
    }

    fun nextAverage(previous:Long,samples:Int,value:Long):Long {
        if(value<=0)return previous
        if(samples<=0 || previous<=0)return value
        return ((previous*4L)+value)/5L
    }
}

internal class LiveLearner(private val prefs:SharedPreferences) {
    fun enabled()=prefs.getBoolean("adaptive-live-learning",true)
    fun responseLimit(configured:Int)=LiveLearningPolicy.effectiveCap(
        configured,
        prefs.getInt("adaptive-live-response-cap",LiveLearningPolicy.DEFAULT_CAP),
        enabled()
    )

    fun recordTurn(interrupted:Boolean,firstTextMs:Long) {
        if(!enabled())return
        val turns=prefs.getInt("adaptive-live-turns",0)+1
        val interruptions=prefs.getInt("adaptive-live-interruptions",0)+(if(interrupted)1 else 0)
        val current=prefs.getInt("adaptive-live-response-cap",LiveLearningPolicy.DEFAULT_CAP)
        val average=LiveLearningPolicy.nextAverage(
            prefs.getLong("adaptive-live-first-text-ms",0L),
            turns-1,
            firstTextMs
        )
        prefs.edit()
            .putInt("adaptive-live-turns",turns)
            .putInt("adaptive-live-interruptions",interruptions)
            .putInt("adaptive-live-response-cap",LiveLearningPolicy.nextCap(current,interrupted))
            .putLong("adaptive-live-first-text-ms",average)
            .apply()
    }

    fun snapshot()=LiveLearningSnapshot(
        turns=prefs.getInt("adaptive-live-turns",0),
        interruptions=prefs.getInt("adaptive-live-interruptions",0),
        responseTokenCap=prefs.getInt("adaptive-live-response-cap",LiveLearningPolicy.DEFAULT_CAP),
        averageFirstTextMs=prefs.getLong("adaptive-live-first-text-ms",0L)
    )

    fun reset() {
        prefs.edit()
            .remove("adaptive-live-turns")
            .remove("adaptive-live-interruptions")
            .remove("adaptive-live-response-cap")
            .remove("adaptive-live-first-text-ms")
            .apply()
    }
}
