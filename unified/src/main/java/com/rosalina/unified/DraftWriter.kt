package com.rosalina.unified

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/** Keep typing in memory; batch disk writes and flush before navigation/recreation. */
internal class DraftWriter(private val prefs: SharedPreferences) {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = linkedMapOf<String, String>()
    private val write = Runnable { flush() }
    fun put(key: String, value: String) {
        pending[key] = value
        handler.removeCallbacks(write)
        handler.postDelayed(write, 400L)
    }
    fun flush() {
        handler.removeCallbacks(write)
        if (pending.isEmpty()) return
        val editor = prefs.edit()
        pending.forEach { (key, value) -> editor.putString(key, value) }
        pending.clear()
        editor.apply()
    }
}
