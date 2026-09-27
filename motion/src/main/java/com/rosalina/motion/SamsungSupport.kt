package com.rosalina.motion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal object ThermalPolicy {
    fun workerThreads(status:Int)=when {
        status>=PowerManager.THERMAL_STATUS_SEVERE -> 2
        status>=PowerManager.THERMAL_STATUS_MODERATE -> 3
        else -> 4
    }
    fun shouldAbort(status:Int)=status>=PowerManager.THERMAL_STATUS_CRITICAL
}

internal object SamsungSupport {
    const val THERMAL_GUARDIAN_PACKAGE="com.samsung.android.thermalguardian"
    private const val DEVICE_CARE_PACKAGE="com.samsung.android.lool"

    fun isSamsungDevice()=Build.MANUFACTURER.equals("samsung",ignoreCase=true)

    fun thermalGuardianInstalled(context:Context):Boolean =
        runCatching{context.packageManager.getPackageInfo(THERMAL_GUARDIAN_PACKAGE,0);true}.getOrDefault(false)

    fun thermalName(status:Int)=when(status){
        PowerManager.THERMAL_STATUS_NONE -> "thermal normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "thermal light"
        PowerManager.THERMAL_STATUS_MODERATE -> "thermal moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "thermal severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "thermal critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "thermal emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "thermal shutdown"
        else -> "thermal status $status"
    }

    fun openThermalGuardian(context:Context):Boolean {
        val launch=context.packageManager.getLaunchIntentForPackage(THERMAL_GUARDIAN_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(launch!=null)return runCatching{context.startActivity(launch);true}.getOrDefault(false)
        val store=Intent(Intent.ACTION_VIEW,Uri.parse("https://galaxystore.samsung.com/detail/$THERMAL_GUARDIAN_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching{context.startActivity(store);true}.getOrDefault(false)
    }

    fun openNeverSleepingApps(context:Context):Boolean {
        if(isSamsungDevice()){
            val samsung=Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage(DEVICE_CARE_PACKAGE)
                .putExtra("activity_type",2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(runCatching{context.startActivity(samsung);true}.getOrDefault(false))return true
        }
        val fallback=Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching{context.startActivity(fallback);true}.getOrDefault(false)
    }
}
