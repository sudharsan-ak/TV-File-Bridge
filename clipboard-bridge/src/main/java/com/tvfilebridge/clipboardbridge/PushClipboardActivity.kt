package com.tvfilebridge.clipboardbridge

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val PRIMARY_PC_URI: Uri = Uri.parse("content://com.tvfilebridge.app.primarypc/primary")

/**
 * Whole reason this app exists: Samsung's One UI text-selection toolbar
 * doesn't honor ACTION_PROCESS_TEXT for third-party apps, so there's no way
 * to add "Copy to PC" there for apps with no Share button (WhatsApp's own
 * selection menu, for one). This app's only launcher activity is meant to be
 * added to Edge Panel's Apps edge - after copying text anywhere with
 * Android's native Copy, swipe out the edge panel and tap this app's icon.
 * That launch is enough focus for ClipboardManager.getPrimaryClip() to
 * legally return data (the background-read restriction only blocks apps
 * that are NOT focused, not apps being freshly launched), so it reads
 * whatever's on the clipboard right then, pushes it to the main app's
 * primary PC, and finishes - no screen of its own, ever.
 */
class PushClipboardActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // ClipboardManager.getPrimaryClip() is gated on this activity actually
    // holding window focus, not just having been launched - reading in
    // onCreate (or even onResume, which can fire just before focus is
    // granted during an Edge Panel launch transition) raced the OS and
    // returned null even right after a real copy. onWindowFocusChanged(true)
    // is the actual, reliable "focus granted" signal.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val text = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Copy some text first, then open Clipboard Bridge", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val device = queryPrimaryDevice()
            if (device == null) {
                Toast.makeText(
                    this@PushClipboardActivity,
                    "No primary PC set - open TV File Bridge and mark one as primary",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }

            val result = pushText(device, text)
            val message = when (result) {
                is PushResult.Success -> "Copied to ${device.name}"
                is PushResult.Failed -> "Couldn't reach ${device.name}: ${result.reason}"
            }
            Toast.makeText(this@PushClipboardActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun queryPrimaryDevice(): PcDeviceRef? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        contentResolver.query(PRIMARY_PC_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            PcDeviceRef(
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                host = cursor.getString(cursor.getColumnIndexOrThrow("host")),
                port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
            )
        }
    }
}

data class PcDeviceRef(val name: String, val host: String, val port: Int)
