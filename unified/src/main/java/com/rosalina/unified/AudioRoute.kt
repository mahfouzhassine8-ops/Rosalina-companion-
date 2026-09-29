package com.rosalina.unified

import android.media.AudioDeviceInfo

/** Deterministic communication-route preference. The platform still has final routing authority. */
internal object AudioRoutePolicy {
    private val bluetooth = setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    fun usable(type:Int, bluetoothAllowed:Boolean):Boolean = when(type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bluetoothAllowed
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> true
        else -> false
    }
    fun rank(type:Int, bluetoothAllowed:Boolean):Int {
        if(!usable(type,bluetoothAllowed)) return 1000
        return when(type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            AudioDeviceInfo.TYPE_USB_HEADSET -> 2
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 4
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 6
            else -> 1000
        }
    }
    fun label(type:Int):String = when(type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        -1 -> "unresolved"
        else -> "type-$type"
    }
    fun chooseType(types:List<Int>, bluetoothAllowed:Boolean):Int? = types
        .filter{usable(it,bluetoothAllowed)}.minByOrNull{rank(it,bluetoothAllowed)}
    fun isBluetooth(type:Int)=type in bluetooth
}
