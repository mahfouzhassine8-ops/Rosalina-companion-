package com.rosalina.motion
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
class MotionService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private var lock:PowerManager.WakeLock?=null
 private var thermal:PowerManager.OnThermalStatusChangedListener?=null
 private var stateJob:Job?=null
 private var tickerJob:Job?=null
 override fun onCreate(){super.onCreate();MotionSession.init(this)
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("motion","Local video generation",NotificationManager.IMPORTANCE_LOW))
  val pm=getSystemService(PowerManager::class.java)
  lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Rosalina:Motion").apply{acquire(121*60*1000L)}
  MotionSession.updateThermal(pm.currentThermalStatus)
  thermal=PowerManager.OnThermalStatusChangedListener{level->
   MotionSession.updateThermal(level)
   if(level>=PowerManager.THERMAL_STATUS_SEVERE&&MotionSession.state.value.busy&&MotionSession.state.value.work=="render")
    MotionSession.cancel("Rendering stopped because the phone is too hot. Let it cool down.")
  }.also{pm.addThermalStatusListener(it)}
 }
 private fun notification(state:MotionState):Notification{
  val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  val open=PendingIntent.getActivity(this,0,Intent(this,MotionActivity::class.java),flags)
  val stop=PendingIntent.getService(this,1,Intent(this,MotionService::class.java).setAction("STOP"),flags)
  val now=SystemClock.elapsedRealtime()
  val elapsed=if(state.started>0)maxOf(0L,(now-state.started)/1000) else 0L
  val remaining=if(state.expectedFinish>now)(state.expectedFinish-now)/1000 else null
  val meta=buildString{
   state.progress?.let{append("$it% · ")}
   append(MotionProgressMath.formatDuration(elapsed)).append(" elapsed")
   if(remaining!=null)append(" · ~").append(MotionProgressMath.formatDuration(remaining)).append(" left")
   else if(state.busy&&state.work=="render"&&!state.stopping)append(" · ETA calibrating")
   if(state.thermal.isNotBlank())append(" · ").append(state.thermal.removePrefix("Thermal: "))
  }
  return NotificationCompat.Builder(this,"motion")
   .setSmallIcon(R.drawable.ic_motion)
   .setContentTitle("Rosalina · making your video")
   .setContentText(state.status)
   .setSubText(meta)
   .setContentIntent(open)
   .setOngoing(true)
   .setOnlyAlertOnce(true)
   .setCategory(NotificationCompat.CATEGORY_PROGRESS)
   .setProgress(100,state.progress?:0,state.progress==null)
   .setUsesChronometer(state.started>0)
   .setWhen(if(state.started>0)System.currentTimeMillis()-(now-state.started) else System.currentTimeMillis())
   .addAction(0,"Stop",stop)
   .build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){
   MotionSession.cancel()
   scope.launch{
    repeat(20){
     if(!MotionSession.state.value.busy){stopSelf();return@launch}
     delay(250)
    }
    stopSelf()
   }
   return START_NOT_STICKY
  }
  val type=if(Build.VERSION.SDK_INT>=35)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
  startForeground(30,notification(MotionSession.state.value),type)
  val s=MotionSession.state.value
  if(!s.busy||s.work!="render"){stopSelf();return START_NOT_STICKY}
  MotionSession.runPending()
  stateJob?.cancel()
  tickerJob?.cancel()
  stateJob=scope.launch{MotionSession.state.collectLatest{if(it.busy)getSystemService(NotificationManager::class.java).notify(30,notification(it))}}
  tickerJob=scope.launch{
   while(isActive){
    delay(5000)
    val current=MotionSession.state.value
    if(!current.busy)break
    getSystemService(NotificationManager::class.java).notify(30,notification(current))
   }
  }
  return START_NOT_STICKY
 }
 override fun onTimeout(startId:Int,fgsType:Int){MotionSession.cancel("Android ended the rendering time window");stopSelf()}
 override fun onDestroy(){
  if(MotionSession.state.value.busy&&MotionSession.state.value.work=="render"&&!MotionSession.state.value.stopping)
   MotionSession.cancel("Rendering service stopped; memory released")
  scope.cancel();thermal?.let{getSystemService(PowerManager::class.java).removeThermalStatusListener(it)}
  if(lock?.isHeld==true)lock?.release();super.onDestroy()
 }
 override fun onBind(intent:Intent?):IBinder?=null
}
