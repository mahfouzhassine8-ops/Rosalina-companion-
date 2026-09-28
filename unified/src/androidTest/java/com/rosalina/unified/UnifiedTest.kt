package com.rosalina.unified
import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
@RunWith(AndroidJUnit4::class)
class UnifiedTest {
 private val context:Context get()=InstrumentationRegistry.getInstrumentation().targetContext
 @Test fun activityRecreationPreservesDraft(){context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).edit().putString("tab","Edit").putString("draft-Edit","Keep this exact draft").commit();ActivityScenario.launch(MainActivity::class.java).use{s->s.recreate();s.onActivity{a->assertEquals("Keep this exact draft",a.findViewById<android.widget.EditText>(1001).text.toString())}}}
 @Test fun stopReapsOwnedWorker()=runBlocking {val dir=File(context.cacheDir,"stop-qa").apply{mkdirs()};val started=System.currentTimeMillis();val job=launch(Dispatchers.IO){NativeWorker(ThermalManager(context)).run(listOf("/system/bin/sleep","30"),dir,8){_,_,_,_,_,_,_->}};delay(400);job.cancelAndJoin();assertTrue(System.currentTimeMillis()-started<5000);dir.deleteRecursively()}
 @Test fun wrongModelChecksumDoesNotInstallOrLeavePartial()=runBlocking {val f=File(context.cacheDir,"wrong.gguf").apply{writeText("not a model")};val store=ModelStore(context);try{store.import(ModelKey.IMAGE,Uri.fromFile(f)){_,_->};fail("Checksum mismatch was accepted")}catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("SHA-256 mismatch"))};assertNull(store.path(ModelKey.IMAGE));assertFalse(File(context.filesDir,"models").listFiles().orEmpty().any{it.name.endsWith(".part")});f.delete()}
 @Test fun mp4HasExactFramesForAllUserDurations()=runBlocking {
  for(seconds in listOf(6,8,10)) {
   val spec=MotionSpec(seconds=seconds);val raw=File(context.cacheDir,"codec-$seconds.rvf");val mp4=File(context.cacheDir,"codec-$seconds.mp4")
   raw.outputStream().buffered().use{out->out.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31465652).putInt(spec.width).putInt(spec.height).putInt(spec.modelFrames).putInt(8).array());for(n in 0 until spec.modelFrames){val frame=ByteArray(spec.width*spec.height*3);for(i in 0 until spec.width*spec.height){frame[i*3]=(n*3).toByte();frame[i*3+1]=(i%spec.width).toByte();frame[i*3+2]=90};out.write(frame)}}
   Mp4Encoder.encode(raw,mp4,spec){}
   val extractor=MediaExtractor();try{extractor.setDataSource(mp4.path);extractor.selectTrack(0);var count=0;while(extractor.sampleTime>=0){count++;if(!extractor.advance())break};assertEquals(spec.exportFrames,count)}finally{extractor.release()};raw.delete();mp4.delete()
  }
 }
}
