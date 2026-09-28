package com.rosalina.unified

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal data class OnlineState(
    val allowed:Boolean,
    val connected:Boolean,
    val validated:Boolean,
    val metered:Boolean
) {
    fun summary():String {
        val access=if(allowed)"allowed" else "disabled"
        val link=when{!connected->"offline";validated->"validated internet";else->"network without validation"}
        return "Online enhancements=$access; $link; metered=$metered"
    }
}

internal object OnlineEnhancements {
    fun state(context:Context,prefs:SharedPreferences):OnlineState {
        val allowed=prefs.getBoolean("online-enhancements",true)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val network=cm.activeNetwork
        val caps=network?.let{cm.getNetworkCapabilities(it)}
        val connected=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
        val validated=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        return OnlineState(allowed,connected,validated,cm.isActiveNetworkMetered)
    }
}
