package com.tvfilebridge.tvcompanion

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        layout.addView(TextView(this).apply {
            text = "TV Bridge Cursor\n\nThis app lets the TV File Bridge phone app show a " +
                "cursor and control this TV. Two things need enabling once:"
            textSize = 18f
            setPadding(0, 0, 0, 48)
        })

        layout.addView(Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "2. Allow \"Display over other apps\""
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
        })

        setContentView(layout)
    }
}
