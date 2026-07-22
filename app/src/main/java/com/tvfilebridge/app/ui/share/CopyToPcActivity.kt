package com.tvfilebridge.app.ui.share

import android.content.Intent
import android.net.Uri
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
 * Share-sheet target for "Copy to PC": sends the shared image(s) (or text)
 * directly to a saved PC companion over its own TCP protocol, which then
 * writes it onto the Windows clipboard - a completely separate destination/
 * protocol from ShareUploadActivity's "Send to TV" (ADB push to TV storage).
 *
 * Multiple images are pushed one after another rather than as a single
 * batch - Windows' clipboard only ever holds one item "live" at a time
 * regardless (a second copy always replaces the first), but each push still
 * lands in Windows' own Clipboard History panel (Win+V), so sending them in
 * sequence is what actually gets all of them somewhere pasteable, matching
 * how copying multiple things one at a time already behaves on Windows.
 */
class CopyToPcActivity : ComponentActivity() {
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

        val sharedText = extractSharedText(intent)
        val sharedImageUris = extractSharedImageUris(intent)
        val sharedFileUris = extractSharedFileUris(intent)
        if (sharedText == null && sharedImageUris.isEmpty() && sharedFileUris.isEmpty()) {
            finish()
            return
        }

        val container = (application as TvFileBridgeApp).container

        lifecycleScope.launch {
            val primaryDevice = container.pcDeviceStore.devices.first().find { it.isPrimary }
            if (primaryDevice != null) {
                // Skip the picker entirely when exactly one device is marked
                // primary - the common case is always the same phone<->PC
                // pair, so making that a two-tap round trip every time (pick
                // device, then confirm) was pure friction for no benefit.
                pushAndFinish(container, primaryDevice, sharedText, sharedImageUris, sharedFileUris)
                return@launch
            }

            setContent {
                TvFileBridgeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CopyToPcScreen(
                            container = container,
                            isImage = sharedImageUris.isNotEmpty(),
                            imageCount = sharedImageUris.size,
                            onConfirm = { device -> pushAndFinish(container, device, sharedText, sharedImageUris, sharedFileUris) },
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun pushAndFinish(container: AppContainer, device: PcDevice, text: String?, imageUris: List<Uri>, fileUris: List<Uri>) {
        // Files are streamed and can take a long time (a multi-GB video) -
        // fire them into PcFileTransferManager and finish immediately rather
        // than awaiting completion here; progress/result show up in the
        // Transfers > PC tab instead of a toast at the end.
        if (fileUris.isNotEmpty()) {
            for (uri in fileUris) {
                container.pcFileTransferManager.pushFile(device, uri)
            }
            val message = if (fileUris.size == 1) "Sending to ${device.name}…" else "Sending ${fileUris.size} files to ${device.name}…"
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            if (imageUris.isNotEmpty()) {
                var successCount = 0
                var lastFailureReason: String? = null
                for (uri in imageUris) {
                    val result = container.clipboardBridge.pushImage(device, uri)
                    if (result is PushResult.Success) successCount++ else lastFailureReason = (result as? PushResult.Failed)?.reason
                    container.clipboardSendLog.record(
                        ClipboardSendEntry(
                            kind = ClipboardContentKind.IMAGE,
                            imageUri = uri,
                            targetDeviceName = device.name,
                            status = if (result is PushResult.Success) ClipboardSendStatus.SUCCESS else ClipboardSendStatus.FAILED,
                            failureReason = (result as? PushResult.Failed)?.reason,
                        ),
                    )
                }
                val message = when {
                    successCount == imageUris.size -> if (imageUris.size == 1) "Copied to ${device.name}" else "Copied ${imageUris.size} images to ${device.name}"
                    successCount == 0 -> "Couldn't reach ${device.name}: ${lastFailureReason ?: "unknown error"}"
                    else -> "Copied $successCount of ${imageUris.size} images to ${device.name}"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } else {
                val result = container.clipboardBridge.pushText(device, text.orEmpty())
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
            }
            finish()
        }
    }

    private fun extractSharedText(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

    private fun extractSharedImageUris(intent: Intent): List<Uri> = when {
        intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true -> {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            listOfNotNull(uri)
        }
        intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true -> {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris ?: emptyList()
        }
        else -> emptyList()
    }

    /** Any non-image, non-text share (PDFs, docs, videos, zips) - anything with a mimeType that isn't handled above. */
    private fun extractSharedFileUris(intent: Intent): List<Uri> {
        val type = intent.type ?: return emptyList()
        if (type.startsWith("image/") || type == "text/plain") return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris ?: emptyList()
            }
            else -> emptyList()
        }
    }
}
