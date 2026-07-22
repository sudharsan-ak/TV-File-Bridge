package com.tvfilebridge.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tvfilebridge.app.TvFileBridgeApp
import com.tvfilebridge.app.ui.theme.TvFileBridgeTheme

class ShareUploadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        val container = (application as TvFileBridgeApp).container

        setContent {
            TvFileBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShareUploadScreen(
                        container = container,
                        uriCount = uris.size,
                        onConfirm = { destinationPath ->
                            uris.forEach { uri ->
                                val fileName = container.transferManager.fileNameFor(uri)
                                val sizeBytes = container.transferManager.fileSizeFor(uri)
                                container.transferManager.pushFile(uri, destinationPath, fileName, sizeBytes)
                            }
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
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
