package com.rosalina.motion

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScreenTest {
    private fun all(v:View):List<View> = listOf(v) + if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList()
    @Test fun photoPromptAndRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        ActivityScenario.launch(MotionActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val views=all(a.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any{it.text.toString().contains("Choose a photo")})
                assertTrue(views.filterIsInstance<TextView>().any{it.text.toString()=="6s"})
                assertTrue(views.filterIsInstance<TextView>().any{it.text.toString()=="10s"})
                views.filterIsInstance<EditText>().first().setText("A red ball bounces gently, static camera.")
            }
            val f=File(context.cacheDir,"ui-reference.png")
            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.rgb(35,62,88));drawCircle(128f,120f,58f,Paint().apply{color=Color.rgb(225,80,55)}) }
            f.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            scenario.onActivity { MotionSession.selectPhoto(Uri.fromFile(f)) }
            runBlocking { withTimeout(15000) { MotionSession.state.first{!it.busy && it.photo.isNotBlank()} } }
            scenario.recreate()
            scenario.onActivity { a ->
                assertEquals("A red ball bounces gently, static camera.",all(a.window.decorView).filterIsInstance<EditText>().first().text.toString())
                assertTrue(File(MotionSession.state.value.photo).isFile)
            }
            instrumentation.waitForIdleSync();Thread.sleep(600)
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            // Public emulator-only QA output survives Gradle uninstalling the test package.
            val values=ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"motion-photo-screen.png")
                put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/RosalinaQA")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
            context.contentResolver.openOutputStream(uri)!!.use{assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))}
            context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            screenshot.recycle();f.delete()
        }
    }
}
