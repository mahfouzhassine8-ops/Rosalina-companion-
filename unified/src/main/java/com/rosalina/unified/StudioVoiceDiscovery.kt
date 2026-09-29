package com.rosalina.unified

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** User-started discovery only. Advertisements are untrusted suggestions, never auto-paired. */
internal class StudioVoiceDiscovery(context:Context,private val found:(String,String)->Unit,private val status:(String)->Unit) {
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var submitted=0
    private val seen=mutableSetOf<String>()
    private val stopTask=Runnable{stop();status("Scan complete. A manual HTTPS address works without discovery.")}
    private val listener=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String){handler.post{if(active)status("Scanning advertised Studio services…")}}
        override fun onDiscoveryStopped(type:String){}
        override fun onStartDiscoveryFailed(type:String,error:Int){handler.post{stop();status("Discovery unavailable; enter the Mac's HTTPS address manually.")}}
        override fun onStopDiscoveryFailed(type:String,error:Int){}
        override fun onServiceLost(info:NsdServiceInfo){}
        override fun onServiceFound(info:NsdServiceInfo){handler.post{
            if(!active || submitted>=10 || !seen.add(info.serviceName))return@post
            submitted++
            @Suppress("DEPRECATION")
            runCatching{nsd.resolveService(info,object:NsdManager.ResolveListener {
                override fun onResolveFailed(info:NsdServiceInfo,error:Int){}
                override fun onServiceResolved(info:NsdServiceInfo){handler.post{
                    if(!active)return@post
                    val endpoint=info.attributes["url"]?.toString(Charsets.UTF_8)?.let{runCatching{StudioEndpoint.parse(it)}.getOrNull()} ?:return@post
                    val label=info.serviceName.filter{it.code>=32}.take(60)
                    found(label,endpoint.root)
                }}
            })}
        }}
    }
    fun start(){stop();active=true;submitted=0;seen.clear()
        runCatching{nsd.discoverServices("_rosalina-studio._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)}
            .onFailure{active=false;status("Discovery unavailable; use the manual address.")}
        if(active)handler.postDelayed(stopTask,8000)
    }
    fun stop(){handler.removeCallbacks(stopTask);if(active){active=false;runCatching{nsd.stopServiceDiscovery(listener)}}}
}
