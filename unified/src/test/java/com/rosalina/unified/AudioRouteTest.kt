package com.rosalina.unified

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class AudioRouteTest {
    @Test fun phoneSpeakerIsARealCommunicationFallback() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioRoutePolicy.chooseType(listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),false))
    }
    @Test fun connectedHeadsetsBeatSpeakerWhenPermissionAllowsThem() {
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioRoutePolicy.chooseType(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BLE_HEADSET),true))
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioRoutePolicy.chooseType(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_USB_HEADSET),false))
    }
    @Test fun bluetoothIsNeverSelectedWithoutNearbyDevicesPermission() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioRoutePolicy.chooseType(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),false))
    }
    @Test fun unsupportedOutputsAreNotInventedAsCommunicationRoutes() {
        assertNull(AudioRoutePolicy.chooseType(listOf(AudioDeviceInfo.TYPE_HDMI,AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),true))
    }
}
