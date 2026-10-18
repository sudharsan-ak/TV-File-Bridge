package com.tvfilebridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.tvfilebridge.app.ui.nav.AppScaffold
import com.tvfilebridge.app.ui.theme.TvFileBridgeTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val container = (application as TvFileBridgeApp).container
        setContent {
            TvFileBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(container)
                }
            }
        }
    }

    /**
     * Declared in the manifest but never actually requested at runtime
     * until this - POST_NOTIFICATIONS is a runtime permission as of
     * Android 13 (Tiramisu), so without this call it silently sits denied
     * forever and every notification this app tries to show (the TV
     * connection tile's ongoing one, and the "tap to install" prompt for
     * an APK pushed from PC Companion) just never appears, with no error
     * anywhere to explain why. Asked once on first launch, the standard
     * Android pattern, rather than waiting for a specific feature to need
     * it first.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
