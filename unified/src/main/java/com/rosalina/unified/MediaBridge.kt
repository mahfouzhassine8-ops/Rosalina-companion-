package com.rosalina.unified
import java.io.File
internal typealias MotionSpec = com.rosalina.motion.MotionSpec
internal object Mp4Encoder {
    suspend fun encode(raw:File,target:File,spec:MotionSpec,progress:(Int)->Unit) = com.rosalina.motion.Mp4Encoder.encode(raw,target,spec,progress)
}
