package com.tvfilebridge.app.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.sync.SyncDirection
import com.tvfilebridge.app.sync.SyncPair
import com.tvfilebridge.app.sync.SyncRunStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFoldersScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SyncFoldersViewModel = viewModel(factory = SyncFoldersViewModelFactory(container))
    val uiState by viewModel.uiState.collectAsState()
    var showAddFlow by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SyncPair?>(null) }
    var deleteTarget by remember { mutableStateOf<SyncPair?>(null) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddFlow = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add sync pair")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val running = uiState.runState.status == SyncRunStatus.RUNNING
                Button(
                    onClick = { viewModel.syncNow() },
                    enabled = !running && uiState.pairs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Syncing ${uiState.runState.currentPairLabel ?: ""}…")
                    } else {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sync all")
                    }
                }
            }

            if (uiState.pairs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("No sync pairs yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pair a phone folder with a TV folder to keep them in sync.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.pairs, key = { it.id }) { pair ->
                        SyncPairCard(
                            pair = pair,
                            onDirectionChange = { viewModel.setDirection(pair.id, it) },
                            onEdit = { editTarget = pair },
                            onDelete = { deleteTarget = pair },
                        )
                    }
                }
            }
        }
    }

    if (showAddFlow) {
        AddSyncPairFlow(
            container = container,
            existingPair = null,
            onDismiss = { showAddFlow = false },
            onConfirm = { label, phoneUri, phoneFolderName, tvPath, direction ->
                viewModel.addPair(label, phoneUri, phoneFolderName, tvPath, direction)
                showAddFlow = false
            },
        )
    }

    editTarget?.let { pair ->
        AddSyncPairFlow(
            container = container,
            existingPair = pair,
            onDismiss = { editTarget = null },
            onConfirm = { label, phoneUri, phoneFolderName, tvPath, direction ->
                viewModel.updatePair(pair.id, label, phoneUri, phoneFolderName, tvPath, direction)
                editTarget = null
            },
        )
    }

    deleteTarget?.let { pair ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove \"${pair.label}\" pairing?") },
            text = { Text("This only removes the pairing — no files on your phone or TV are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removePair(pair.id)
                    deleteTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SyncPairCard(
    pair: SyncPair,
    onDirectionChange: (SyncDirection) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pair.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "📱 ${pair.phoneFolderName}  ⇄  📺 ${pair.tvPath.substringAfterLast('/')}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            DirectionSelector(current = pair.direction, onSelect = onDirectionChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionSelector(current: SyncDirection, onSelect: (SyncDirection) -> Unit) {
    val options = listOf(
        SyncDirection.TV_TO_PHONE to "TV → Phone",
        SyncDirection.PHONE_TO_TV to "Phone → TV",
        SyncDirection.TWO_WAY to "Two-way",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (direction, label) ->
            SegmentedButton(
                selected = current == direction,
                onClick = { onSelect(direction) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AddSyncPairFlow(
    container: AppContainer,
    existingPair: SyncPair?,
    onDismiss: () -> Unit,
    onConfirm: (label: String, phoneTreeUri: String, phoneFolderName: String, tvPath: String, direction: SyncDirection) -> Unit,
) {
    var label by remember { mutableStateOf(existingPair?.label ?: "") }
    var phoneTreeUri by remember { mutableStateOf(existingPair?.phoneTreeUri) }
    var phoneFolderName by remember { mutableStateOf(existingPair?.phoneFolderName) }
    var tvPath by remember { mutableStateOf(existingPair?.tvPath) }
    var tvLabel by remember { mutableStateOf(existingPair?.tvPath?.substringAfterLast('/')) }
    var direction by remember { mutableStateOf(existingPair?.direction ?: SyncDirection.TV_TO_PHONE) }
    var showTvPicker by remember { mutableStateOf(false) }

    val phoneFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            container.contentResolverPersistPermission(uri)
            phoneTreeUri = uri.toString()
            phoneFolderName = uri.lastPathSegment?.substringAfterLast(':') ?: "Phone folder"
        }
    }

    if (showTvPicker) {
        TvFolderPickerDialog(
            container = container,
            onDismiss = { showTvPicker = false },
            onPick = { path, pickedLabel ->
                tvPath = path
                tvLabel = pickedLabel
                showTvPicker = false
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingPair == null) "Add sync pair" else "Edit sync pair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    placeholder = { Text("Screenshots") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    text = phoneFolderName ?: "Choose phone folder",
                    onClick = { phoneFolderLauncher.launch(null) },
                )
                OutlinedButton(
                    text = tvLabel ?: "Choose TV folder",
                    onClick = { showTvPicker = true },
                )
                if (tvPath != null && phoneTreeUri != null) {
                    Text("Direction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DirectionSelector(current = direction, onSelect = { direction = it })
                }
            }
        },
        confirmButton = {
            val ready = label.isNotBlank() && phoneTreeUri != null && tvPath != null
            TextButton(
                enabled = ready,
                onClick = {
                    onConfirm(label.trim(), phoneTreeUri!!, phoneFolderName ?: "Phone folder", tvPath!!, direction)
                },
            ) { Text(if (existingPair == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OutlinedButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
