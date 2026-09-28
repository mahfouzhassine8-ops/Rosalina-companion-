package com.rosalina.unified

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** One activity-independent service owns user-started work. A task token fences late cleanup. */
class GenerationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var wake:PowerManager.WakeLock?=null
    private var observer:Job?=null
    private var finishing=false
    private var ownerId=""
    private var ownerStartId=0
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
    /** Called on Main, while the original foreground task remains owned. Never reopens microphone in background. */
    internal fun setMode(taskId:String,kind:TaskKind,microphone:Boolean=false,playback:Boolean=false) {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(ownerId==taskId && !finishing){"Foreground task is no longer owned"}
        var type=when {
            kind==TaskKind.IMPORT->ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE) && Build.VERSION.SDK_INT>=35->ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT>=34->ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else->0
        }
        if(microphone)type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if(playback)type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        startForeground(100,notification(session.state.value.copy(kind=kind)),type)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {
            if(intent.getStringExtra("task")==ownerId && session.state.value.id==ownerId) {
                ownerStartId=startId
                session.stop()
            } else if(!session.state.value.busy)stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val r=session.currentRequest()
        if(r==null){stopSelfResult(startId);return START_NOT_STICKY}
        val token=intent?.getStringExtra("task") ?: r.id
        if(token!=r.id)return START_NOT_STICKY
        ownerId=r.id;ownerStartId=startId;finishing=false
        try {
            setMode(r.id,r.kind,microphone=r.kind==TaskKind.VOICE,playback=r.kind==TaskKind.CHAT && session.prefs.getBoolean("spoken-replies",false))
            if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Rosalina:UnifiedTask").apply{setReferenceCounted(false);acquire(6*60*60*1000L)}
            if(observer==null)observer=scope.launch{session.state.collectLatest{if(it.busy && it.id==ownerId && !finishing)runCatching{getSystemService(NotificationManager::class.java).notify(100,notification(it))}}}
            session.launchPending(this,r.id)
        } catch(t:Throwable) {
            session.stop("Foreground service failed: ${t.message}")
            session.launchPending(this,r.id)
            finishTask(r.id)
        }
        return START_NOT_STICKY
    }
    internal fun finishTask(taskId:String) {
        if(taskId!=ownerId)return
        finishing=true;observer?.cancel();observer=null
        if(wake?.isHeld==true)wake?.release();wake=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(ownerStartId)
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        if(session.state.value.id==ownerId && session.state.value.busy)session.stop("Android ended this foreground-processing time window")
        finishTask(ownerId)
    }
    override fun onDestroy() {
        if(!finishing && session.state.value.id==ownerId && session.state.value.busy)session.stop("Android stopped the foreground service")
        observer?.cancel();scope.cancel();if(wake?.isHeld==true)wake?.release();wake=null
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
