package com.rosalina.motion
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
class MotionApp:Application(){override fun onCreate(){super.onCreate();AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)}}
