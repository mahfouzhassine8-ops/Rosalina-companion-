package com.rosalina.unified

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx 1.13.8 JNI looks up invoke(float[]) -> java.lang.Integer.
 * Kotlin 2 invokedynamic lambdas can expose only invoke(Object) -> Object after D8,
 * causing NoSuchMethodError followed by a fatal JNI abort. A named Function1
 * implementation supplies the exact typed bridge. Keep this callback allocation-
 * light and exception-free; synthesis PCM/DSP/playback handling happens outside it.
 */
@Keep
internal class NativeSpeechCallback(private val cancelled:AtomicBoolean) : (FloatArray)->Int {
    override fun invoke(samples:FloatArray):Int=if(cancelled.get())0 else 1
}
