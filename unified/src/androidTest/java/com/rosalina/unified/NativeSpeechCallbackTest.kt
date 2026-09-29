package com.rosalina.unified

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeSpeechCallbackTest {
    @Test fun dexContainsExactMethodThatSherpaJniLooksUp() {
        val flag=AtomicBoolean(false)
        val callback=NativeSpeechCallback(flag)
        val method=callback.javaClass.getMethod("invoke",FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java,method.returnType)
        assertEquals(1,method.invoke(callback,floatArrayOf(0f)))
        flag.set(true)
        assertEquals(0,method.invoke(callback,floatArrayOf(0f)))
    }
}
