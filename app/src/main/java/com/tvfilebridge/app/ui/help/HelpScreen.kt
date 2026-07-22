package com.tvfilebridge.app.ui.help

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.ui.nav.AppHeader

private const val RELEASES_URL = "https://github.com/sudharsan-ak/TV-File-Bridge/releases/latest"

private enum class HelpPlatform(val label: String) {
    PHONE("Phone"),
    TV("TV"),
    PC("Windows PC"),
}

private data class HelpEntry(
    val title: String,
    val platform: HelpPlatform,
    val description: String,
    val howToGet: String,
)

private val HELP_ENTRIES = listOf(
    HelpEntry(
        title = "TV File Bridge",
        platform = HelpPlatform.PHONE,
        description = "The main app - everything else runs from here. Browses TV storage, controls the TV, syncs files/clipboard with a PC.",
        howToGet = "You already have this one installed.",
    ),
    HelpEntry(
        title = "Clipboard Bridge",
        platform = HelpPlatform.PHONE,
        description = "A small app that adds a one-tap \"Copy to PC\" shortcut for apps that don't otherwise support sharing text (e.g. via a Share button or text-selection menu).",
        howToGet = "Optional - download the APK above and sideload it if you need this.",
    ),
    HelpEntry(
        title = "TV Bridge Cursor",
        platform = HelpPlatform.TV,
        description = "Draws a real on-screen cursor and lists installed TV apps - needed for Cursor mode and the Apps tab.",
        howToGet = "Installs automatically the first time you use Cursor mode or the Apps tab, or install it yourself from \"Install apps for TV\" in the drawer, once connected to a TV.",
    ),
    HelpEntry(
        title = "Accessibility Watchdog",
        platform = HelpPlatform.TV,
        description = "Standalone TV app that re-enables chosen accessibility services (like TV Bridge Cursor's) if they get disabled after a reboot or update.",
        howToGet = "Install from \"Install apps for TV\" in the drawer once connected to a TV, or download the APK above.",
    ),
    HelpEntry(
        title = "PC Companion",
        platform = HelpPlatform.PC,
        description = "Tray app that handles clipboard and file sync between your phone and PC.",
        howToGet = "Download the .exe above and run it on your PC.",
    ),
)

@Composable
fun HelpScreen(contentPadding: PaddingValues, onMenuClick: () -> Unit) {
    val context = LocalContext.current
    var platform by remember { mutableStateOf(HelpPlatform.PHONE) }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        AppHeader(title = "Help", onMenuClick = onMenuClick)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HelpPlatform.entries.forEach { entry ->
                PlatformTab(entry.label, platform == entry) { platform = entry }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DownloadCard(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) })
            }
            item {
                Text(
                    "This suite has a few small companion apps, each optional except the main one. Here's what runs on ${platform.label.lowercase()}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            items(HELP_ENTRIES.filter { it.platform == platform }) { entry ->
                HelpCard(entry)
            }
        }
    }
}

@Composable
private fun PlatformTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Download APKs / .exe",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Opens the GitHub Releases page with all APKs and the PC .exe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun HelpCard(entry: HelpEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                entry.howToGet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
