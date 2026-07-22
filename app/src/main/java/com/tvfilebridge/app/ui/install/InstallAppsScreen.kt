package com.tvfilebridge.app.ui.install

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.cursor.TvCompanionInstaller
import com.tvfilebridge.app.cursor.WatchdogInstaller
import com.tvfilebridge.app.ui.nav.AppHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InstallAppsScreen(container: AppContainer, contentPadding: PaddingValues, onMenuClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        AppHeader(title = "Install apps for TV", onMenuClick = onMenuClick)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Pushes each app onto the connected TV over the same ADB connection. Safe to run again later - it just reinstalls in place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                InstallCard(
                    title = "TV Bridge Cursor",
                    description = "On-screen cursor and app list - needed for Cursor mode and the Apps tab.",
                    icon = Icons.Filled.Mouse,
                    install = { container.tvCompanionInstaller.install() },
                )
            }
            item {
                InstallCard(
                    title = "Accessibility Watchdog",
                    description = "Re-enables chosen accessibility services (like TV Bridge Cursor's) if they get disabled after a reboot or update.",
                    icon = Icons.Filled.Shield,
                    install = { container.watchdogInstaller.install() },
                )
            }
        }
    }
}

@Composable
private fun InstallCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    install: suspend () -> Result<Unit>,
) {
    var isInstalling by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalling) {
                isInstalling = true
                message = null
                scope.launch {
                    val result = install()
                    message = if (result.isSuccess) "Installed" else "Install failed"
                    delay(1500)
                    message = null
                    isInstalling = false
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(10.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when {
                    isInstalling && message == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        Text("Installing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    message != null -> Text(message!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    else -> Text("Tap to install", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
