package com.tvfilebridge.app.ui.transfers

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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.clipboard.PcFileTransfer
import com.tvfilebridge.app.clipboard.PcFileTransferStatus
import com.tvfilebridge.app.clipboard.ReceivedFileTransfer
import com.tvfilebridge.app.clipboard.ReceivedFileTransferStatus
import com.tvfilebridge.app.transfers.Transfer
import com.tvfilebridge.app.transfers.TransferDirection
import com.tvfilebridge.app.transfers.TransferStatus
import com.tvfilebridge.app.ui.files.formatBytes

@Composable
fun TransfersScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenSyncFolders: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pcSubTab by remember { mutableIntStateOf(0) } // 0 = Sent, 1 = Received
    val tvTransfers by container.transferManager.transfers.collectAsState()
    val pcSentTransfers by container.pcFileTransferManager.transfers.collectAsState()
    val pcReceivedTransfers by container.receivedFileTransferManager.transfers.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        com.tvfilebridge.app.ui.nav.AppHeader(title = "Transfers", onMenuClick = onMenuClick) {
            if (selectedTab == 0) {
                IconButton(onClick = onOpenSyncFolders) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync folders", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tvTransfers.isNotEmpty()) {
                    IconButton(onClick = { container.transferManager.clearHistory() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (pcSubTab == 0) {
                if (pcSentTransfers.isNotEmpty()) {
                    IconButton(onClick = { container.pcFileTransferManager.clearHistory() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear sent", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (pcReceivedTransfers.isNotEmpty()) {
                IconButton(onClick = { container.receivedFileTransferManager.clearHistory() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear received", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("TV") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("PC") })
        }

        if (selectedTab == 0) {
            TvTransfersTab(container)
        } else {
            PcTransfersTab(container, pcSubTab, onSubTabChange = { pcSubTab = it })
        }
    }
}

@Composable
private fun TvTransfersTab(container: AppContainer) {
    val transfers by container.transferManager.transfers.collectAsState()
    val sorted = transfers.sortedByDescending { it.startedAt }

    if (sorted.isEmpty()) {
        EmptyTransfersState(
            icon = Icons.Filled.SwapVert,
            title = "No transfers yet",
            subtitle = "Downloads and uploads will show up here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sorted, key = { it.id }) { transfer ->
            TransferCard(
                transfer = transfer,
                onRetry = { container.transferManager.retry(transfer.id) },
            )
        }
    }
}

@Composable
private fun PcTransfersTab(container: AppContainer, subTab: Int, onSubTabChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { onSubTabChange(0) }, text = { Text("Sent") })
            Tab(selected = subTab == 1, onClick = { onSubTabChange(1) }, text = { Text("Received") })
        }
        if (subTab == 0) {
            PcSentTab(container)
        } else {
            PcReceivedTab(container)
        }
    }
}

@Composable
private fun PcSentTab(container: AppContainer) {
    val transfers by container.pcFileTransferManager.transfers.collectAsState()
    val sorted = transfers.sortedByDescending { it.startedAt }

    if (sorted.isEmpty()) {
        EmptyTransfersState(
            icon = Icons.Filled.SwapVert,
            title = "No files sent yet",
            subtitle = "Files sent via Copy to PC / Send to PC will show up here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sorted, key = { it.id }) { transfer ->
            PcFileTransferCard(
                transfer = transfer,
                onCancel = { container.pcFileTransferManager.cancel(transfer.id) },
            )
        }
    }
}

@Composable
private fun PcReceivedTab(container: AppContainer) {
    val transfers by container.receivedFileTransferManager.transfers.collectAsState()
    val sorted = transfers.sortedByDescending { it.startedAt }

    if (sorted.isEmpty()) {
        EmptyTransfersState(
            icon = Icons.Filled.SwapVert,
            title = "No files received yet",
            subtitle = "Files your PC sends here will show up here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sorted, key = { it.id }) { transfer ->
            ReceivedFileTransferCard(transfer = transfer, container = container)
        }
    }
}

@Composable
private fun EmptyTransfersState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PcFileTransferCard(transfer: PcFileTransfer, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    transfer.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PcFileStatusIcon(transfer.status)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "to ${transfer.targetDeviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            when (transfer.status) {
                PcFileTransferStatus.IN_PROGRESS -> {
                    val progress = if (transfer.sizeBytes > 0) {
                        (transfer.progressBytes.toFloat() / transfer.sizeBytes.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(transfer.progressBytes)} of ${formatBytes(transfer.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    }
                }
                PcFileTransferStatus.SUCCEEDED -> Text(
                    formatBytes(transfer.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PcFileTransferStatus.FAILED -> Text(
                    transfer.errorMessage ?: "Transfer failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                PcFileTransferStatus.CANCELLED -> Text(
                    "Cancelled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReceivedFileTransferCard(transfer: ReceivedFileTransfer, container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderUriString by container.receivedFilesFolderStore.folderUri.collectAsState(initial = null)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    transfer.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ReceivedFileStatusIcon(transfer.status)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "from ${transfer.sourceDeviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            when (transfer.status) {
                ReceivedFileTransferStatus.IN_PROGRESS -> {
                    val progress = if (transfer.sizeBytes > 0) {
                        (transfer.progressBytes.toFloat() / transfer.sizeBytes.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(transfer.progressBytes)} of ${formatBytes(transfer.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                ReceivedFileTransferStatus.SUCCEEDED -> {
                    Text(
                        formatBytes(transfer.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = {
                            com.tvfilebridge.app.ui.clipboard.openFile(context, transfer.fileUri)
                        }) { Text("Open") }
                        androidx.compose.material3.TextButton(onClick = {
                            com.tvfilebridge.app.ui.clipboard.showFileLocation(context, folderUriString?.let(android.net.Uri::parse))
                        }) { Text("Show location") }
                    }
                }
                ReceivedFileTransferStatus.FAILED -> Text(
                    transfer.errorMessage ?: "Transfer failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ReceivedFileStatusIcon(status: ReceivedFileTransferStatus) {
    when (status) {
        ReceivedFileTransferStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        ReceivedFileTransferStatus.SUCCEEDED -> Icon(Icons.Filled.Check, contentDescription = "Done", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        ReceivedFileTransferStatus.FAILED -> Icon(Icons.Filled.Error, contentDescription = "Failed", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PcFileStatusIcon(status: PcFileTransferStatus) {
    when (status) {
        PcFileTransferStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        PcFileTransferStatus.SUCCEEDED -> Icon(Icons.Filled.Check, contentDescription = "Done", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        PcFileTransferStatus.FAILED -> Icon(Icons.Filled.Error, contentDescription = "Failed", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        PcFileTransferStatus.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = "Cancelled", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransferCard(transfer: Transfer, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (transfer.direction == TransferDirection.PULL) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    transfer.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusIcon(transfer.status)
            }

            Spacer(Modifier.height(6.dp))

            when (transfer.status) {
                TransferStatus.IN_PROGRESS -> {
                    val progress = if (transfer.sizeBytes > 0) {
                        (transfer.progressBytes.toFloat() / transfer.sizeBytes.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(transfer.progressBytes)} of ${formatBytes(transfer.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                TransferStatus.SUCCEEDED -> Text(
                    formatBytes(transfer.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TransferStatus.FAILED -> {
                    Text(
                        transfer.errorMessage ?: "Transfer failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
                TransferStatus.CANCELLED -> Text(
                    "Cancelled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(status: TransferStatus) {
    when (status) {
        TransferStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        TransferStatus.SUCCEEDED -> Icon(Icons.Filled.Check, contentDescription = "Done", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        TransferStatus.FAILED -> Icon(Icons.Filled.Error, contentDescription = "Failed", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        TransferStatus.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = "Cancelled", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
