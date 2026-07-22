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
import com.tvfilebridge.app.clipboard.PcDevice
import com.tvfilebridge.app.ui.theme.TvFileBridgeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Share-sheet target "Send to PC" for images: a second, separate row next to
 * "Copy to PC" (CopyToPcActivity) in Android's own Share sheet - registered
 * for the same image mimeType, so Android lists both as distinct entries the
 * same way "Send to TV"/"Copy to PC" already do for other content. This one
 * always goes to the file-send path (PcFileTransferManager, streamed, lands
 * as a real file in the configured receive folder), never the clipboard -
 * "Copy to PC" stays clipboard-only for images, unchanged. Two native rows
 * instead of an in-app "which do you want" prompt, since there's no way to
 * inject a submenu into the Share sheet itself.
 */
class SendImageToPcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        val imageUris = extractImageUris(intent)
        if (imageUris.isEmpty()) {
            finish()
            return
        }

        val container = (application as TvFileBridgeApp).container

        lifecycleScope.launch {
            val primaryDevice = container.pcDeviceStore.devices.first().find { it.isPrimary }
            if (primaryDevice != null) {
                pushAndFinish(container, primaryDevice, imageUris)
                return@launch
            }

            setContent {
                TvFileBridgeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CopyToPcScreen(
                            container = container,
                            isImage = true,
                            imageCount = imageUris.size,
                            onConfirm = { device -> pushAndFinish(container, device, imageUris) },
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun pushAndFinish(container: AppContainer, device: PcDevice, imageUris: List<Uri>) {
        for (uri in imageUris) {
            container.pcFileTransferManager.pushFile(device, uri)
        }
        val message = if (imageUris.size == 1) "Sending to ${device.name}…" else "Sending ${imageUris.size} images to ${device.name}…"
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun extractImageUris(intent: Intent): List<Uri> = when {
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
}
