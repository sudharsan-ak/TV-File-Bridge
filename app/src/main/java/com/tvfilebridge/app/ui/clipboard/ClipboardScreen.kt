package com.tvfilebridge.app.ui.clipboard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.clipboard.ClipboardContentKind
import com.tvfilebridge.app.clipboard.ClipboardEntryDirection
import com.tvfilebridge.app.clipboard.ClipboardSendEntry
import com.tvfilebridge.app.clipboard.ClipboardSendStatus
import com.tvfilebridge.app.clipboard.PcDevice
import com.tvfilebridge.app.clipboard.PushResult
import com.tvfilebridge.app.ui.nav.AppHeader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ClipboardSection { HISTORY, DEVICES, FILE_SHARING }

@Composable
fun ClipboardScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(ClipboardSection.HISTORY) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        AppHeader(title = "PC Sync", onMenuClick = onMenuClick)

        Scaffold(
            modifier = Modifier.fillMaxWidth(),
            floatingActionButton = {
                if (section == ClipboardSection.DEVICES) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddDialog = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Add PC") },
                    )
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTab("History", section == ClipboardSection.HISTORY) { section = ClipboardSection.HISTORY }
                    SectionTab("Devices", section == ClipboardSection.DEVICES) { section = ClipboardSection.DEVICES }
                    SectionTab("File Sharing", section == ClipboardSection.FILE_SHARING) { section = ClipboardSection.FILE_SHARING }
                }

                when (section) {
                    ClipboardSection.HISTORY -> HistorySection(container)
                    ClipboardSection.DEVICES -> DevicesSection(container)
                    ClipboardSection.FILE_SHARING -> FileSharingSection(container)
                }
            }
        }
    }

    if (showAddDialog) {
        AddPcDeviceDialog(
            container = container,
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun SectionTab(label: String, selected: Boolean, onSelect: () -> Unit) {
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
private fun HistorySection(container: AppContainer) {
    val entries by container.clipboardSendLog.entries.collectAsState()

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Nothing copied yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use \"Copy to PC\" from any app's Share menu to send text or images here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(onClick = { container.clipboardSendLog.clear() }) {
                Text("Clear all")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                HistoryRow(entry, container)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ClipboardSendEntry, container: AppContainer) {
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderUriString by container.receivedFilesFolderStore.folderUri.collectAsState(initial = null)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.kind == ClipboardContentKind.IMAGE && entry.imageUri != null) {
                    coil.compose.AsyncImage(
                        model = entry.imageUri,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        when (entry.kind) {
                            ClipboardContentKind.IMAGE -> Icons.Filled.Image
                            ClipboardContentKind.FILE -> Icons.Filled.InsertDriveFile
                            ClipboardContentKind.TEXT -> Icons.Filled.TextSnippet
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (entry.kind) {
                            ClipboardContentKind.IMAGE -> "Image"
                            ClipboardContentKind.FILE -> entry.fileName ?: "File"
                            ClipboardContentKind.TEXT -> entry.textPreview?.take(80) ?: ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(2.dp))
                    val directionLabel = if (entry.direction == ClipboardEntryDirection.SENT) "to" else "from"
                    Text(
                        "$directionLabel ${entry.targetDeviceName} · ${timeFormat.format(Date(entry.sentAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.status == ClipboardSendStatus.FAILED) {
                        Text(
                            entry.failureReason ?: "Failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Icon(
                    if (entry.status == ClipboardSendStatus.SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (entry.status == ClipboardSendStatus.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            // Open/Show location only make sense for a file this phone can
            // still actually reach: a received file (saved locally, real
            // fileUri) - not a sent file, whose URI came from whatever app
            // originally shared it and isn't reliably reusable afterward.
            if (entry.kind == ClipboardContentKind.FILE && entry.direction == ClipboardEntryDirection.RECEIVED && entry.fileUri != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { openFile(context, entry.fileUri) }) { Text("Open") }
                    TextButton(onClick = { showFileLocation(context, folderUriString?.let(Uri::parse)) }) { Text("Show location") }
                }
            }
        }
    }
}

@Composable
private fun DevicesSection(container: AppContainer) {
    val devices by container.pcDeviceStore.devices.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("No PCs added yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Install the companion app on your PC, then tap \"Add PC\" with its IP and port.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    var deviceToRename by remember { mutableStateOf<PcDevice?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(devices, key = { it.id }) { device ->
            var isReconnecting by remember { mutableStateOf(false) }
            val context = LocalContext.current
            PcDeviceRow(
                device = device,
                isReconnecting = isReconnecting,
                onRename = { deviceToRename = device },
                onDelete = { scope.launch { container.pcDeviceStore.deleteDevice(device.id) } },
                onTogglePrimary = {
                    scope.launch {
                        if (device.isPrimary) {
                            container.pcDeviceStore.clearPrimary(device.id)
                        } else {
                            container.pcDeviceStore.setPrimary(device.id)
                        }
                    }
                },
                onReconnect = {
                    isReconnecting = true
                    scope.launch {
                        val result = container.clipboardBridge.ping(device)
                        isReconnecting = false
                        val message = when (result) {
                            is PushResult.Success -> "Reconnected - ${device.name} is reachable."
                            is PushResult.Failed -> "Couldn't reach ${device.name}. Make sure PC Companion is running and on the same Wi-Fi."
                        }
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    deviceToRename?.let { device ->
        RenamePcDeviceDialog(
            container = container,
            device = device,
            onDismiss = { deviceToRename = null },
        )
    }
}

@Composable
private fun PcDeviceRow(
    device: PcDevice,
    isReconnecting: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePrimary: () -> Unit,
    onReconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isPrimary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                    if (device.isPrimary) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "PRIMARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePrimary) {
                Icon(
                    if (device.isPrimary) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (device.isPrimary) "Unset as primary" else "Set as primary",
                    tint = if (device.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onReconnect, enabled = !isReconnecting) {
                if (isReconnecting) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reconnect", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RenamePcDeviceDialog(
    container: AppContainer,
    device: PcDevice,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(device.name) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename PC") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.trim().ifBlank { device.host }
                    scope.launch {
                        container.pcDeviceStore.renameDevice(device.id, finalName)
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddPcDeviceDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("58821") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add PC") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("My PC") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.4.50") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portInt = port.toIntOrNull() ?: 58821
                    val finalName = name.trim().ifBlank { host.trim() }
                    if (host.isNotBlank()) {
                        scope.launch {
                            container.pcDeviceStore.addDevice(finalName, host.trim(), portInt)
                            onDismiss()
                        }
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FileSharingSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val folderName by container.receivedFilesFolderStore.folderName.collectAsState(initial = null)

    val folderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            container.contentResolverPersistPermission(uri)
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: "Chosen folder"
            scope.launch { container.receivedFilesFolderStore.setFolder(uri.toString(), name) }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            "Files your PC sends here",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "When \"Files\" auto-send is turned on in the PC companion app's settings, copying a file in Windows Explorer (Ctrl+C) sends it here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "SAVE LOCATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    folderName ?: "Not set - using Downloads",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (folderName == null) "Choose folder" else "Change folder")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "If no folder is chosen, files are saved to your phone's Downloads folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
