package com.rosalina.motion
import org.junit.Assert.*
import org.junit.Test
class MotionSpecTest {
 @Test fun durationsAndAlignment(){for(s in listOf(6,8,10)){val p=MotionSpec(seconds=s);p.validate();assertEquals(0,(p.modelFrames-1)%4);assertEquals(s*1_000_000L,MotionMath.timestampUs(p.exportFrames,p.fps));assertEquals(p.exportFrames+1,p.modelFrames)}}
 @Test(expected=IllegalArgumentException::class) fun rejectsShorterClips(){MotionSpec(seconds=5).validate()}
 @Test(expected=IllegalArgumentException::class) fun rejectsHugeFrames(){MotionSpec(width=1024,height=1024).validate()}
 @Test fun blackAndWhiteYuv(){assertEquals(16,MotionMath.y(0,0,0).toInt() and 255);assertEquals(235,MotionMath.y(255,255,255).toInt() and 255);assertEquals(128,MotionMath.u(255,255,255).toInt() and 255);assertEquals(128,MotionMath.v(0,0,0).toInt() and 255)}
 @Test fun expectedOutputSize(){assertEquals(20L+256L*256L*49L*3L,MotionMath.expectedBytes(256,256,49))}
 @Test fun noNullError(){assertEquals("Exception",MotionMath.error(Exception()))}
 @Test fun digestsArePinned(){assertTrue(ModelPart.entries.all{it.sha.matches(Regex("[0-9a-f]{64}"))})}
 @Test fun progressAndEtaMath(){
  assertEquals(15,MotionProgressMath.samplingOverall(0,9))
  assertEquals(58,MotionProgressMath.samplingOverall(5,9))
  assertEquals(94,MotionProgressMath.samplingOverall(9,9))
  assertEquals(99,MotionProgressMath.mp4Overall(50))
  assertEquals(30_000L,MotionProgressMath.smoothMs(30_000L,30_000L))
  assertNull(MotionProgressMath.remainingFromSampling(1,9,1_000L,11_000L,0))
  assertEquals(100_000L,MotionProgressMath.remainingFromSampling(2,9,1_000L,21_000L,0))
  assertEquals("1m 5s",MotionProgressMath.formatDuration(65))
 }
}
