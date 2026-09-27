package com.rosalina.motion
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
@RunWith(AndroidJUnit4::class)
class CodecTest {
 @Test fun encodesExactSixAndTenSecondClips()=runBlocking {
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    for(seconds in listOf(6,10)){
      val s=MotionSpec(seconds=seconds);val raw=File(c.cacheDir,"codec-$seconds.rvf");val mp4=File(c.cacheDir,"codec-$seconds.mp4")
      FileOutputStream(raw).buffered().use{o->
        o.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31465652).putInt(s.width).putInt(s.height).putInt(s.modelFrames).putInt(s.fps).array())
        for(n in 0 until s.modelFrames){val frame=ByteArray(s.width*s.height*3);for(i in 0 until s.width*s.height){frame[i*3]=(n*3).toByte();frame[i*3+1]=(i%s.width).toByte();frame[i*3+2]=90};o.write(frame)}
      }
      Mp4Encoder.encode(raw,mp4,s){}
      val r=MediaMetadataRetriever();try{r.setDataSource(mp4.path);assertTrue(kotlin.math.abs(seconds*1000L-r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong())<=150)}finally{r.release()}
      val e=MediaExtractor();try{e.setDataSource(mp4.path);e.selectTrack(0);var frames=0;while(e.sampleTime>=0){frames++;if(!e.advance())break};assertEquals(s.exportFrames,frames)}finally{e.release()}
      raw.delete();mp4.delete()
    }
 }
}
