package com.rosalina.motion

internal data class MotionSpec(val seconds:Int=6,val width:Int=256,val height:Int=256,val steps:Int=12,val seed:Long=42) {
    val fps:Int get()=8
    val exportFrames:Int get()=seconds*fps
    val modelFrames:Int get()=exportFrames+1 // Wan requires 4n+1; only the trailing alignment frame is removed.
    fun validate() {
        require(seconds in listOf(6,8,10)) { "Choose 6, 8 or 10 seconds" }
        require((width to height) in listOf(256 to 256,320 to 192,192 to 320)) { "Choose a supported draft aspect ratio" }
        require(steps in 8..30) { "Use 8–30 sampling steps" }
        require(modelFrames in 49..81 && (modelFrames-1)%4==0)
    }
}
internal enum class ModelPart(val fileName:String,val sha:String,val url:String,val label:String) {
    VIDEO("Wan2.2-TI2V-5B-Q4_K_S.gguf","ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb",
        "https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF/resolve/main/Wan2.2-TI2V-5B-Q4_K_S.gguf","Video model · about 3.12 GB"),
    TEXT("umt5-xxl-encoder-Q4_K_S.gguf","4a3176f32fd70c0a335b4419fcbf8c86cc875e23498c0fc06f5b4aa0930889e0",
        "https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_S.gguf","Motion prompt encoder · about 3.50 GB")
}
internal object MotionMath {
    fun timestampUs(frame:Int,fps:Int):Long { require(frame>=0 && fps>0);return frame*1_000_000L/fps }
    fun error(t:Throwable)=t.message?.trim()?.takeIf{it.isNotBlank()} ?: t.javaClass.simpleName
    fun expectedBytes(w:Int,h:Int,n:Int):Long {
        require(w in 64..320 && h in 64..320 && n in 5..81)
        return 20L+w.toLong()*h*n*3L
    }
    fun hex(b:ByteArray)=b.joinToString(""){ "%02x".format(it.toInt() and 255) }
    fun y(r:Int,g:Int,b:Int)=(((66*r+129*g+25*b+128) shr 8)+16).coerceIn(0,255).toByte()
    fun u(r:Int,g:Int,b:Int)=(((-38*r-74*g+112*b+128) shr 8)+128).coerceIn(0,255).toByte()
    fun v(r:Int,g:Int,b:Int)=(((112*r-94*g-18*b+128) shr 8)+128).coerceIn(0,255).toByte()
}


internal object MotionProgressMath {
    const val SAMPLE_START = 10
    const val SAMPLE_END = 90

    fun samplingOverall(step:Int,total:Int):Int {
        if(total<=0) return SAMPLE_START
        val n=step.coerceIn(0,total)
        return SAMPLE_START + ((SAMPLE_END-SAMPLE_START)*n/total)
    }

    fun mp4Overall(percent:Int):Int = 96 + (4*percent.coerceIn(0,100)/100)

    fun smoothMs(previous:Long,actual:Long):Long {
        require(actual>=0)
        return if(previous>0) (previous*2 + actual)/3 else actual
    }

    fun remainingFromSampling(step:Int,total:Int,samplingStartedAt:Long,now:Long,postBaselineMs:Long):Long? {
        if(step<2 || total<=0 || step>total || samplingStartedAt<=0 || now<=samplingStartedAt) return null
        val averagePerStep=(now-samplingStartedAt)/step
        val samplingRemaining=(total-step).toLong()*averagePerStep
        val fallbackPost=maxOf(30_000L,averagePerStep*maxOf(1,total/10))
        return maxOf(0L,samplingRemaining + if(postBaselineMs>0) postBaselineMs else fallbackPost)
    }

    fun formatDuration(seconds:Long):String {
        val s=maxOf(0L,seconds)
        val h=s/3600
        val m=(s%3600)/60
        val sec=s%60
        return if(h>0) "${h}h ${m}m" else if(m>0) "${m}m ${sec}s" else "${sec}s"
    }
}
