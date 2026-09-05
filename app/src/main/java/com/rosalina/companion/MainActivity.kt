package com.rosalina.companion

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chat = TextView(this).apply {
            text = "Rosalina\n\nHey love ❤️ Rosalina Companion is alive.\n"
            textSize = 18f
            setPadding(32, 32, 32, 32)
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
            addView(ScrollView(this@MainActivity).apply { addView(chat) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls)
        }
        send.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isNotEmpty()) {
                chat.append("\nYou: $message\nRosalina: AI connection comes next ❤️\n")
                input.text.clear()
            }
        }
        setContentView(root)
    }
}
