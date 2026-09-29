package com.rosalina.unified

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarRenderProbe {
    @Test fun tokenizerMatchesPassedHostGoldens() {
        val root=File(System.getProperty("rosalina.qa.model") ?: error("Model fixture directory required"))
        val tokenizer=ChatterboxTokenizer(File(root,"tokenizer.json"))
        val cases=org.json.JSONArray(File(root,"tokenizer-cases.json").readText())
        for(i in 0 until cases.length()){
            val e=cases.getJSONObject(i);val a=e.getJSONArray("ids")
            assertArrayEquals(e.getString("text"),LongArray(a.length()){a.getLong(it)},tokenizer.encode(e.getString("text")))
        }
    }

    @Test fun renderAllExpressionsAndSignalArticulation() {
        val context=RuntimeEnvironment.getApplication()
        val assetPath=System.getProperty("rosalina.qa.assets")
        val added=context.assets.javaClass.getMethod("addAssetPath",String::class.java).invoke(context.assets,assetPath) as Int
        assertTrue("Assets mounted",added!=0)
        val assets=RigAssets.load(context);assertTrue(assets.layers.size>=25)
        val output=File(System.getProperty("rosalina.qa.output")).apply{mkdirs()}
        val renderer=LayeredAvatarRenderer(assets)
        for(e in RosalinaExpression.entries){
            val perf=PerformanceState(expression=e)
            val snapshot=CompanionSnapshot(phase=CompanionPhase.SPEAKING,playbackActive=true,performance=perf,expression=e,changedAt=10_000)
            val frame=RigMotion.sample(snapshot,MotionTier.NORMAL,11_000,false)
            val bitmap=Bitmap.createBitmap(594,1082,Bitmap.Config.ARGB_8888);renderer.draw(Canvas(bitmap),594,1082,frame)
            File(output,e.name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        val s=CompanionSnapshot(phase=CompanionPhase.SPEAKING,playbackActive=true,performance=PerformanceState(expression=RosalinaExpression.HAPPY),
            mouth=MouthPose(.7f,.4f,.15f),changedAt=10_000)
        val bitmap=Bitmap.createBitmap(594,1082,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap),594,1082,RigMotion.sample(s,MotionTier.NORMAL,11_400));File(output,"PCM_SPEAKING.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        for(g in listOf(Gesture.WALK,Gesture.TURN)){
            val ss=s.copy(performance=PerformanceState(gesture=g,expression=RosalinaExpression.HAPPY))
            for(i in 0..5){
                val b=Bitmap.createBitmap(594,1082,Bitmap.Config.ARGB_8888)
                renderer.draw(Canvas(b),594,1082,RigMotion.sample(ss,MotionTier.NORMAL,10_000+i*1000L))
                File(output,"${g.name}_$i.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            }
        }
    }
}
