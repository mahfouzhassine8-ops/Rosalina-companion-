package com.rosalina.unified

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Hardware capabilities are observations, not proof that a model used GPU acceleration. */
internal object DeviceFacts {
    fun describe(context:Context):String {
        val commit=runCatching{context.assets.open("source-commit.txt").bufferedReader().use{it.readText().trim()}}.getOrDefault("Not bundled in this QA package")
        val features=runCatching{context.packageManager.systemAvailableFeatures.filter{it.name?.contains("vulkan",ignoreCase=true)==true}.joinToString{ "${it.name}=${it.version}" }}.getOrDefault("Unavailable")
        val soc=if(Build.VERSION.SDK_INT>=31)"${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "Not reported on this Android version"
        return "Source commit: $commit\nSigner SHA-256: ${certificate(context,context.packageName)}\nSoC: $soc; CPU threads reported: ${Runtime.getRuntime().availableProcessors()}\nAndroid-advertised Vulkan features: ${features.ifBlank{"None reported"}}\nActual GPU selection/memory is reported only by the native backend log.\n"
    }
    private fun certificate(context:Context,name:String):String=runCatching {
        val info=context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.joinToString{hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))} ?: "Unavailable"
    }.getOrDefault("Unavailable")
}
