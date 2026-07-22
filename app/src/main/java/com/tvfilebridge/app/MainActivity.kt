package com.tvfilebridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tvfilebridge.app.ui.nav.AppScaffold
import com.tvfilebridge.app.ui.theme.TvFileBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TvFileBridgeApp).container
        setContent {
            TvFileBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(container)
                }
            }
        }
    }
}
