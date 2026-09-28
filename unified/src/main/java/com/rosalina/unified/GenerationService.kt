package com.rosalina.unified

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** One activity-independent service owns all user-started long operations. Never auto-restarts jobs. */
class GenerationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var wake:PowerManager.WakeLock?=null
    private var observer:Job?=null
    private var finishing=false
    private val session by lazy{Session.get(this)}
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("rosalina-tasks","Local Rosalina tasks",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(s:TaskState):Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),flags)
        val stop=PendingIntent.getService(this,1,Intent(this,GenerationService::class.java).setAction("STOP").putExtra("task",s.id),flags)
        val detail="${s.elapsedMs/1000}s elapsed · ${if(s.eta.isBlank())"" else s.eta+" · "}Thermal: ${ThermalPolicy.label(s.thermal)}"
        return NotificationCompat.Builder(this,"rosalina-tasks").setSmallIcon(R.drawable.ic_rosalina)
            .setContentTitle("Rosalina · ${s.kind?.name?.lowercase()?.replaceFirstChar{it.uppercase()} ?: "Local task"}")
            .setContentText(s.stage).setSubText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(s.stage+"\n"+detail))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100,s.percent ?: 0,s.percent==null).addAction(0,"Stop",stop).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {if(intent.getStringExtra("task")==session.state.value.id)session.stop();return START_NOT_STICKY}
        val r=session.currentRequest()
        if(r==null){stopSelf();return START_NOT_STICKY}
        val type=when {
            r.kind==TaskKind.VOICE && Build.VERSION.SDK_INT>=34->ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            r.kind==TaskKind.VOICE->ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            r.kind==TaskKind.IMPORT->ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            r.kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE) && Build.VERSION.SDK_INT>=35->ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT>=34->ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else->0
        }
        try {
            startForeground(100,notification(session.state.value),type)
            if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Rosalina:UnifiedTask").apply{setReferenceCounted(false);acquire(6*60*60*1000L)}
            if(observer==null)observer=scope.launch{session.state.collectLatest{if(it.busy)runCatching{getSystemService(NotificationManager::class.java).notify(100,notification(it))}}}
            session.launchPending(this,intent?.getStringExtra("task") ?: r.id)
        } catch(t:Throwable){session.stop("Foreground service failed: ${t.message}");session.launchPending(this,r.id);finishTask()}
        return START_NOT_STICKY
    }
    fun finishTask(){finishing=true;observer?.cancel();observer=null;if(wake?.isHeld==true)wake?.release();wake=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onTimeout(startId:Int,fgsType:Int){session.stop("Android ended this foreground-processing time window");finishTask()}
    override fun onDestroy(){if(!finishing && session.state.value.busy)session.stop("Android stopped the foreground service");observer?.cancel();scope.cancel();if(wake?.isHeld==true)wake?.release();wake=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
