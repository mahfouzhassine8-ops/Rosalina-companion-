package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test

class SpeechCompatibilityTest {
    @Test fun samsungAndroid37UsesPlatformSpeech(){assertTrue(SpeechCompatibility.preferPlatform("samsung",37,false))}
    @Test fun olderSamsungKeepsNativeSpeech(){assertFalse(SpeechCompatibility.preferPlatform("samsung",35,false))}
    @Test fun otherModernDeviceKeepsNativeUntilCrash(){assertFalse(SpeechCompatibility.preferPlatform("google",37,false))}
    @Test fun nativeCrashForcesPlatformEverywhere(){assertTrue(SpeechCompatibility.preferPlatform("google",35,true))}
}
