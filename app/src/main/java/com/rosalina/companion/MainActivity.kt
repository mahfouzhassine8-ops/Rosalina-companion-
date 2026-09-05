package com.rosalina.companion

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val avatar = TextView(this).apply {
            text = "✦\n👩🏻\n♡"
            textSize = 54f
            gravity = Gravity.CENTER
            setPadding(16, 30, 16, 20)
            contentDescription = "Rosalina live avatar prototype"
        }

        // Gentle idle motion. This gives us a live-avatar layer now; later we can
        // replace the placeholder with a rigged 2D/3D model without changing chat.
        ObjectAnimator.ofFloat(avatar, View.TRANSLATION_Y, 0f, -12f, 0f).apply {
            duration = 2600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val status = TextView(this).apply {
            text = "ROSALINA • ONLINE"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 16)
        }

        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Sweet", "Menace", "Spicy / Flirty")
            )
        }

        val chat = TextView(this).apply {
            text = "Hey love ❤️ Rosalina Companion is alive.\n\nLive avatar prototype: ACTIVE\n"
            textSize = 17f
            setPadding(32, 24, 32, 24)
        }
        val input = EditText(this).apply { hint = "Message Rosalina…" }
        val send = Button(this).apply { text = "Send" }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 12, 22))
            addView(avatar)
            addView(status)
            addView(mode)
            addView(ScrollView(this@MainActivity).apply { addView(chat) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls)
        }
        send.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isNotEmpty()) {
                val selectedMode = mode.selectedItem.toString()
                chat.append("\nYou: $message\nRosalina [$selectedMode]: AI + voice connection comes next ❤️\n")
                avatar.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction {
                    avatar.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }.start()
                input.text.clear()
            }
        }
        setContentView(root)
    }
}
