package com.tvfilebridge.app.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.ui.files.ROOT_PATH

/** Minimal folder-only browser for picking a TV destination folder for sync. */
@Composable
fun TvFolderPickerDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPick: (path: String, label: String) -> Unit,
) {
    var currentPath by remember { mutableStateOf(ROOT_PATH) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(currentPath) {
        isLoading = true
        val result = container.fileRepository.list(currentPath)
        folders = result.getOrNull()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                currentPath.removePrefix(ROOT_PATH).ifBlank { "Internal storage" }.trim('/').ifBlank { "Internal storage" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                if (currentPath != ROOT_PATH) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentPath = currentPath.substringBeforeLast('/').ifBlank { ROOT_PATH }
                                    .let { if (it.length < ROOT_PATH.length) ROOT_PATH else it }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(".. (up one level)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Box(modifier = Modifier.height(280.dp)) {
                    when {
                        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        folders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No subfolders here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> LazyColumn {
                            items(folders) { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val label = currentPath.removePrefix(ROOT_PATH).trim('/').ifBlank { "Internal storage" }
                onPick(currentPath, label)
            }) { Text("Select this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
