package com.rosalina.studio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class RenderService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var started=false
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StudioSession.init(this)
        if(intent?.action=="STOP") { StudioSession.cancel(); return START_NOT_STICKY }
        if(started) return START_NOT_STICKY
        started=true
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("render","Local image generation",NotificationManager.IMPORTANCE_LOW))
            val note=notification("Preparing local image engine")
            when {
                Build.VERSION.SDK_INT>=35 -> startForeground(21,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT>=34 -> startForeground(21,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else -> startForeground(21,note)
            }
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Rosalina:ImageRender").apply { acquire(31*60*1000L) }
            scope.launch {
                StudioSession.state.collect { s -> if(s.work=="render") manager.notify(21,notification(s.status)) }
            }
            StudioSession.startRender { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } catch(e: Exception) { StudioSession.renderFailedToStart(e); stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,StudioActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,RenderService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"render").setSmallIcon(R.drawable.ic_studio)
            .setContentTitle("Rosalina is creating on your phone").setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Stop",stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { StudioSession.cancel(); stopSelf() }
    override fun onDestroy() {
        if(StudioSession.state.value.work=="render") StudioSession.cancel()
        if(wake?.isHeld==true) wake?.release()
        scope.cancel()
        super.onDestroy()
    }
}
