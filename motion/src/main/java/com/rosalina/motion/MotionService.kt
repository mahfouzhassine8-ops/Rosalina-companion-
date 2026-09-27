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
 override fun onCreate(){super.onCreate();MotionSession.init(this)
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("motion","Local video generation",NotificationManager.IMPORTANCE_LOW))
  val pm=getSystemService(PowerManager::class.java)
  lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Rosalina:Motion").apply{acquire(121*60*1000L)}
  thermal=PowerManager.OnThermalStatusChangedListener{
   when {
    it>=PowerManager.THERMAL_STATUS_SEVERE -> MotionSession.cancel("Rendering stopped because the phone is too hot. Let it cool down.")
    it>=PowerManager.THERMAL_STATUS_MODERATE -> MotionSession.notice("Phone is warm · Android/Samsung thermal controls are throttling safely in the background")
   }
  }.also{pm.addThermalStatusListener(it)}
 }
 private fun notification(text:String):Notification{
  val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  val open=PendingIntent.getActivity(this,0,Intent(this,MotionActivity::class.java),flags)
  val stop=PendingIntent.getService(this,1,Intent(this,MotionService::class.java).setAction("STOP"),flags)
  return NotificationCompat.Builder(this,"motion").setSmallIcon(R.drawable.ic_motion).setContentTitle("Rosalina · making your video").setContentText(text).setSubText("Background rendering active").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).addAction(0,"Stop",stop).build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){MotionSession.cancel();stopSelf();return START_STICKY}
  val type=if(Build.VERSION.SDK_INT>=35)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
  startForeground(30,notification("Preparing local video renderer"),type)
  val s=MotionSession.state.value
  if(!s.busy||s.work!="render"){stopSelf();return START_NOT_STICKY}
  MotionSession.runPending()
  scope.launch{MotionSession.state.collectLatest{if(it.busy)getSystemService(NotificationManager::class.java).notify(30,notification(it.status))}}
  return START_NOT_STICKY
 }
 override fun onTimeout(startId:Int,fgsType:Int){MotionSession.cancel("Android ended the rendering time window");stopSelf()}
 override fun onDestroy(){
  if(MotionSession.state.value.busy&&MotionSession.state.value.work=="render")MotionSession.cancel("Rendering service stopped; memory released")
  scope.cancel();thermal?.let{getSystemService(PowerManager::class.java).removeThermalStatusListener(it)}
  if(lock?.isHeld==true)lock?.release();super.onDestroy()
 }
 override fun onBind(intent:Intent?):IBinder?=null
}
