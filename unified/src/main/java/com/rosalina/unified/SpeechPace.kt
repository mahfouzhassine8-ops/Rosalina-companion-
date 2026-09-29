package com.rosalina.unified

/** Time stretching keeps pitch neutral; mouth observations remain indexed by source playback frames. */
internal object SpeechPace {
    fun bounded(value:Float)=if(value.isFinite())value.coerceIn(.8f,1.2f) else 1f
    fun durationMillis(frames:Int,rate:Int,pace:Float):Long {
        require(frames>=0 && rate>0)
        return kotlin.math.ceil(frames*1000.0/rate/bounded(pace)).toLong()
    }
}
