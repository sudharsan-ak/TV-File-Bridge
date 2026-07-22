package com.tvfilebridge.app.ui.share

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.TvFileBridgeApp
import com.tvfilebridge.app.clipboard.ClipboardContentKind
import com.tvfilebridge.app.clipboard.ClipboardSendEntry
import com.tvfilebridge.app.clipboard.ClipboardSendStatus
import com.tvfilebridge.app.clipboard.PcDevice
import com.tvfilebridge.app.clipboard.PushResult
import com.tvfilebridge.app.ui.theme.TvFileBridgeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles ACTION_PROCESS_TEXT - the entry Android adds to the text-selection
 * floating toolbar (Copy/Share/Web search/etc.) for any app using the
 * standard selection widget, letting "Copy to PC" show up right there
 * without going through the full Share sheet. Doesn't cover apps with their
 * own custom selection UI that skips this system mechanism entirely (e.g.
 * WhatsApp's own long-press action sheet) - there's no way to add an entry
 * to another app's custom menu, only to Android's shared one.
 *
 * Distinct from CopyToPcActivity (the Share-sheet target) because
 * ACTION_PROCESS_TEXT's intent shape is different (EXTRA_PROCESS_TEXT
 * instead of EXTRA_TEXT) and this one is text-only by definition.
 */
class ProcessTextCopyToPcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 14+ (API 34) plays a scale/fade transition with a black
        // scrim by default when launching any activity, independent of the
        // windowAnimationStyle theme attribute - this activity is meant to be
        // invisible when it can finish instantly (primary device set), so
        // both open and close transitions are disabled here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (text.isNullOrBlank()) {
            finish()
            return
        }

        val container = (application as TvFileBridgeApp).container

        lifecycleScope.launch {
            val primaryDevice = container.pcDeviceStore.devices.first().find { it.isPrimary }
            if (primaryDevice != null) {
                pushAndFinish(container, primaryDevice, text)
                return@launch
            }

            setContent {
                TvFileBridgeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CopyToPcScreen(
                            container = container,
                            isImage = false,
                            onConfirm = { device -> pushAndFinish(container, device, text) },
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun pushAndFinish(container: AppContainer, device: PcDevice, text: String) {
        lifecycleScope.launch {
            val result = container.clipboardBridge.pushText(device, text)
            val message = when (result) {
                is PushResult.Success -> "Copied to ${device.name}"
                is PushResult.Failed -> "Couldn't reach ${device.name}: ${result.reason}"
            }
            container.clipboardSendLog.record(
                ClipboardSendEntry(
                    kind = ClipboardContentKind.TEXT,
                    textPreview = text,
                    targetDeviceName = device.name,
                    status = if (result is PushResult.Success) ClipboardSendStatus.SUCCESS else ClipboardSendStatus.FAILED,
                    failureReason = (result as? PushResult.Failed)?.reason,
                ),
            )
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
