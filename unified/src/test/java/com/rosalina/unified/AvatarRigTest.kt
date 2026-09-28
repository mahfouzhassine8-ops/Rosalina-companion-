package com.rosalina.unified

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AvatarRigTest {
    private fun render(rig: AvatarRig, time: Float, energy: Float = 0f, enabled: Boolean = true): FloatArray {
        rig.update(time, energy, false, false, 10f, 20f, 360f, 522f, enabled)
        return rig.vertices.copyOf()
    }
    @Test fun disabledMotionIsExactlyTheRegularSourceMesh() {
        val rig=AvatarRig();val v=render(rig,99f,1f,false)
        for(y in 0..rig.rows)for(x in 0..rig.columns){
            val i=(y*(rig.columns+1)+x)*2
            assertEquals(10f+x*360f/rig.columns,v[i],0.0001f)
            assertEquals(20f+y*522f/rig.rows,v[i+1],0.0001f)
        }
    }
    @Test fun animationMovesLocalFeaturesButNotOuterEdges() {
        val rig=AvatarRig();val a=render(rig,0f);val b=render(rig,1.72f)
        assertTrue(a.indices.count{abs(a[it]-b[it])>.05f}>50)
        for(y in 0..rig.rows)for(x in listOf(0,rig.columns)){
            val i=(y*(rig.columns+1)+x)*2
            assertEquals(a[i],b[i],0f);assertEquals(a[i+1],b[i+1],0f)
        }
    }
    @Test fun blinkingClosesAndReopensAndAudioOnlyMovesMouthRegion() {
        assertEquals(0f,AvatarRig.blink(0f),0f)
        assertEquals(1f,AvatarRig.blink(1.72f),.0001f)
        assertEquals(0f,AvatarRig.blink(2.5f),0f)
        val rig=AvatarRig();val silent=render(rig,0f);val voiced=render(rig,0f,1f)
        assertTrue(silent.indices.any{abs(silent[it]-voiced[it])>.1f})
        val last=silent.size-1;assertEquals(silent[last],voiced[last],0f)
    }
    @Test fun longUptimeAndInvalidEnergyDoNotProduceNonFiniteVertices() {
        val rig=AvatarRig()
        for(t in listOf(0f,864000f,Float.NaN,Float.POSITIVE_INFINITY))
            for(e in listOf(0f,1f,Float.NaN,Float.POSITIVE_INFINITY))assertTrue(render(rig,t,e).all{it.isFinite()})
    }
}
