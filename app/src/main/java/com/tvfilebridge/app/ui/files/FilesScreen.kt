package com.tvfilebridge.app.ui.files

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.clipboard.PcFile
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.files.RemoteFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")

private enum class ViewMode { LIST, GRID }

/**
 * Same TV/PC split shape as TransfersScreen's TabRow - the drawer keeps one
 * "Files" entry, this just picks which backend (ADB-to-TV vs the phone's own
 * TCP protocol to the PC) the body underneath talks to.
 */
@Composable
fun FilesScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            com.tvfilebridge.app.ui.nav.AppHeader(title = "Files", onMenuClick = onMenuClick)
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("TV") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("PC") })
            }
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    TvFilesTab(container = container, onMenuClick = onMenuClick)
                } else {
                    PcFilesTab(container = container)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvFilesTab(
    container: AppContainer,
    onMenuClick: () -> Unit,
) {
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModelFactory(container))
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sheetTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var detailsTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var copyToPcError by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var uploadConflict by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showStorageInfo by remember { mutableStateOf(false) }
    val isSelecting = selectedPaths.isNotEmpty()

    fun toggleSelection(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.uploadFile(uri) { fileName, proceed ->
                uploadConflict = fileName to proceed
            }
        }
    }

    BackHandler(enabled = isSelecting) {
        selectedPaths = emptySet()
    }
    BackHandler(enabled = !isSelecting && uiState.currentPath != ROOT_PATH) {
        viewModel.navigateUp()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedPaths = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                }
                Text(
                    "${selectedPaths.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val entries = uiState.entries.filter { it.path in selectedPaths }
                    viewModel.downloadAll(entries)
                    selectedPaths = emptySet()
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "Download selected")
                }
                IconButton(onClick = {
                    val entries = uiState.entries.filter { it.path in selectedPaths }
                    selectedPaths = emptySet()
                    scope.launch {
                        val device = container.pcDeviceStore.devices.first().find { it.isPrimary }
                        if (device == null) {
                            copyToPcError = "No primary PC set - add or choose one in PC Sync > Devices."
                            return@launch
                        }
                        val files = viewModel.pullAllToCache(entries)
                        files.forEach { file ->
                            val uri = FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
                            container.pcFileTransferManager.pushFile(device, uri)
                        }
                    }
                }) {
                    Icon(Icons.Filled.Computer, contentDescription = "Copy to PC")
                }
                IconButton(onClick = { batchDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar) viewModel.clearSearch()
                }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilesOverflowMenu(
                    viewMode = viewMode,
                    sortMode = uiState.sortMode,
                    onRefresh = { viewModel.refresh() },
                    onToggleViewMode = { viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST },
                    onSetSortMode = viewModel::setSortMode,
                    onShowStorageInfo = { showStorageInfo = true },
                )
            }
        }

        androidx.compose.runtime.LaunchedEffect(uiState.currentPath) {
            selectedPaths = emptySet()
        }

        if (uiState.connectionState !is ConnectionState.Connected) {
            NotConnectedBody(
                modifier = Modifier.weight(1f),
                message = "Connect to a TV from Settings to browse its storage.",
            )
            return@Column
        }

        Breadcrumb(path = uiState.currentPath, onNavigate = viewModel::navigateTo)

        if (showSearchBar) {
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                onClose = {
                    showSearchBar = false
                    viewModel.clearSearch()
                },
            )
        } else {
            ShortcutRow(currentPath = uiState.currentPath, onSelect = viewModel::navigateTo)
        }

        TypeFilterRow(current = uiState.typeFilter, onSelect = viewModel::setTypeFilter)

        Box(modifier = Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.entries.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    uiState.error != null ->
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                uiState.error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    uiState.entries.isEmpty() ->
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "This folder is empty.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    viewMode == ViewMode.LIST -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                container = container,
                                isSelecting = isSelecting,
                                isSelected = entry.path in selectedPaths,
                                onClick = {
                                    when {
                                        isSelecting -> toggleSelection(entry.path)
                                        entry.isDirectory -> viewModel.navigateTo(entry.path)
                                        else -> sheetTarget = entry
                                    }
                                },
                                onLongClick = { toggleSelection(entry.path) },
                            )
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(uiState.entries, key = { it.path }) { entry ->
                            FileGridCell(
                                entry = entry,
                                container = container,
                                isSelecting = isSelecting,
                                isSelected = entry.path in selectedPaths,
                                onClick = {
                                    when {
                                        isSelecting -> toggleSelection(entry.path)
                                        entry.isDirectory -> viewModel.navigateTo(entry.path)
                                        else -> sheetTarget = entry
                                    }
                                },
                                onLongClick = { toggleSelection(entry.path) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.connectionState is ConnectionState.Connected) {
        FloatingActionButton(
            onClick = { uploadLauncher.launch("*/*") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Upload to this folder")
        }
    }
    }

    sheetTarget?.let { entry ->
        FileActionSheet(
            entry = entry,
            onDismiss = { sheetTarget = null },
            onDownload = {
                viewModel.download(entry)
                sheetTarget = null
            },
            onOpen = {
                sheetTarget = null
                scope.launch {
                    viewModel.openFile(entry) { file ->
                        if (file == null) return@openFile
                        val uri = FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(entry.name.substringAfterLast('.', ""))
                            ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            },
            onCopy = {
                sheetTarget = null
                scope.launch {
                    viewModel.openFile(entry) { file ->
                        if (file == null) {
                            copyToPcError = "Couldn't copy: download failed"
                            return@openFile
                        }
                        val uri = FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
                        val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboardManager.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, entry.name, uri))
                    }
                }
            },
            onRename = {
                sheetTarget = null
                renameTarget = entry
            },
            onDetails = {
                sheetTarget = null
                detailsTarget = entry
            },
            onDelete = {
                sheetTarget = null
                deleteTarget = entry
            },
        )
    }

    renameTarget?.let { entry ->
        RenameFileDialog(
            currentName = entry.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(entry, newName) { result ->
                    if (result.isFailure) {
                        renameError = result.exceptionOrNull()?.message ?: "Rename failed"
                    }
                }
                renameTarget = null
            },
        )
    }

    renameError?.let { message ->
        AlertDialog(
            onDismissRequest = { renameError = null },
            title = { Text("Couldn't rename") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { renameError = null }) { Text("OK") }
            },
        )
    }

    copyToPcError?.let { message ->
        AlertDialog(
            onDismissRequest = { copyToPcError = null },
            title = { Text("Couldn't copy to PC") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { copyToPcError = null }) { Text("OK") }
            },
        )
    }

    detailsTarget?.let { entry ->
        FileDetailsDialog(entry = entry, onDismiss = { detailsTarget = null })
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${entry.name}\"?") },
            text = { Text("This permanently deletes it from the TV. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry) { result ->
                        result.onFailure { deleteError = it.message ?: "Couldn't delete this file" }
                    }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
            title = { Text("Delete failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { deleteError = null }) { Text("OK") }
            },
        )
    }

    if (batchDeleteConfirm) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text(if (count == 1) "Delete 1 item?" else "Delete $count items?") },
            text = { Text("This permanently deletes them from the TV. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val entries = uiState.entries.filter { it.path in selectedPaths }
                    viewModel.deleteAll(entries) { failedCount ->
                        if (failedCount > 0) deleteError = "$failedCount item(s) couldn't be deleted"
                    }
                    selectedPaths = emptySet()
                    batchDeleteConfirm = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    uploadConflict?.let { (fileName, proceed) ->
        AlertDialog(
            onDismissRequest = { uploadConflict = null },
            title = { Text("\"$fileName\" already exists") },
            text = { Text("A file with this name is already in this folder on the TV.") },
            confirmButton = {
                TextButton(onClick = {
                    proceed(fileName)
                    uploadConflict = null
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val dot = fileName.lastIndexOf('.')
                        val uniqueName = if (dot > 0) {
                            "${fileName.substring(0, dot)} (${System.currentTimeMillis() % 10000})${fileName.substring(dot)}"
                        } else {
                            "$fileName (${System.currentTimeMillis() % 10000})"
                        }
                        proceed(uniqueName)
                        uploadConflict = null
                    }) { Text("Keep both") }
                    TextButton(onClick = { uploadConflict = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (showStorageInfo) {
        StorageInfoDialog(viewModel = viewModel, onDismiss = { showStorageInfo = false })
    }
}

@Composable
private fun StorageInfoDialog(viewModel: FilesViewModel, onDismiss: () -> Unit) {
    var storageInfo by remember { mutableStateOf<com.tvfilebridge.app.files.StorageInfo?>(null) }
    var folderSizes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        storageInfo = viewModel.loadStorageInfo()
        SHORTCUTS.forEach { shortcut ->
            val size = viewModel.loadFolderSize(shortcut.path)
            if (size != null) folderSizes = folderSizes + (shortcut.path to size)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage on this TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (storageInfo == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    val info = storageInfo!!
                    DetailRow("Used", "${expandSizeUnit(info.used)} of ${expandSizeUnit(info.total)} (${info.usedPercent})")
                    DetailRow("Free", expandSizeUnit(info.available))
                }

                Text(
                    "Folder sizes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SHORTCUTS.forEach { shortcut ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(shortcut.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val size = folderSizes[shortcut.path]
                        if (size != null) {
                            Text(expandSizeUnit(size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RenameFileDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != currentName,
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    entry: RemoteFile,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth(),
            )
            SheetAction(icon = Icons.Filled.Download, label = "Download", onClick = onDownload)
            SheetAction(icon = Icons.Filled.OpenInNew, label = "Open", onClick = onOpen)
            if (isThumbnailable(entry)) {
                SheetAction(icon = Icons.Filled.ContentCopy, label = "Copy", onClick = onCopy)
            }
            SheetAction(icon = Icons.Filled.Edit, label = "Rename", onClick = onRename)
            SheetAction(icon = Icons.Filled.Info, label = "Details", onClick = onDetails)
            SheetAction(icon = Icons.Filled.Delete, label = "Delete", onClick = onDelete, isDestructive = true)
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, isDestructive: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FileDetailsDialog(entry: RemoteFile, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("Type", if (entry.isDirectory) "Folder" else fileTypeLabel(entry.name))
                if (!entry.isDirectory) {
                    DetailRow("Size", "${formatBytes(entry.sizeBytes)} (${entry.sizeBytes} bytes)")
                }
                DetailRow("Location", entry.path.substringBeforeLast('/').ifBlank { "/" })
                DetailRow("Modified", formatFullDate(entry.modifiedAt))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun fileTypeLabel(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return if (ext.isBlank()) "File" else "$ext file"
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) {
        val relative = path.removePrefix(ROOT_PATH).trim('/')
        val crumbs = mutableListOf("Internal storage" to ROOT_PATH)
        if (relative.isNotEmpty()) {
            var accum = ROOT_PATH
            relative.split("/").forEach { part ->
                accum = "$accum/$part"
                crumbs.add(part to accum)
            }
        }
        crumbs
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(" / ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = index != segments.lastIndex) { onNavigate(target) },
            )
        }
    }
}

@Composable
private fun ShortcutRow(currentPath: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SHORTCUTS.forEach { shortcut ->
            val isActive = currentPath == shortcut.path
            Text(
                shortcut.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(shortcut.path) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("Search this folder and subfolders") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close search")
            }
        },
    )
}

private data class TypeFilterOption(val filter: TypeFilter, val label: String)

private val TYPE_FILTER_OPTIONS = listOf(
    TypeFilterOption(TypeFilter.ALL, "All"),
    TypeFilterOption(TypeFilter.IMAGES, "Images"),
    TypeFilterOption(TypeFilter.VIDEOS, "Videos"),
    TypeFilterOption(TypeFilter.DOCUMENTS, "Documents"),
    TypeFilterOption(TypeFilter.OTHER, "Other"),
)

@Composable
private fun TypeFilterRow(current: TypeFilter, onSelect: (TypeFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TYPE_FILTER_OPTIONS.forEach { option ->
            val isActive = current == option.filter
            Text(
                option.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(option.filter) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FilesOverflowMenu(
    viewMode: ViewMode,
    sortMode: SortMode,
    onRefresh: () -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onShowStorageInfo: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                text = { Text("Refresh") },
                onClick = { onRefresh(); expanded = false },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(if (viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList, contentDescription = null)
                },
                text = { Text(if (viewMode == ViewMode.LIST) "Grid view" else "List view") },
                onClick = { onToggleViewMode(); expanded = false },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null) },
                text = { Text("Sort by name" + if (sortMode == SortMode.NAME) " ✓" else "") },
                onClick = { onSetSortMode(SortMode.NAME); expanded = false },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null) },
                text = { Text("Sort by date" + if (sortMode == SortMode.DATE) " ✓" else "") },
                onClick = { onSetSortMode(SortMode.DATE); expanded = false },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                text = { Text("Storage usage") },
                onClick = { onShowStorageInfo(); expanded = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: RemoteFile,
    container: AppContainer,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelecting) {
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        if (isThumbnailable(entry)) {
            ThumbnailImage(
                entry = entry,
                container = container,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                isVideo = isVideo,
            )
        } else {
            val icon = when {
                entry.isDirectory -> Icons.Filled.Folder
                isVideo -> Icons.Filled.Movie
                else -> Icons.Filled.InsertDriveFile
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = if (entry.isDirectory) {
                formatModifiedDate(entry.modifiedAt)
            } else {
                listOf(formatBytes(entry.sizeBytes), formatModifiedDate(entry.modifiedAt)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: RemoteFile,
    container: AppContainer,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (isThumbnailable(entry)) {
                ThumbnailImage(entry = entry, container = container, modifier = Modifier.fillMaxSize(), isVideo = isVideo)
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelecting) {
                Box(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopStart) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// PC tab - browses this phone's paired PC's own filesystem over the existing
// clipboard-bridge TCP protocol (pc_list_dir/pc_pull_file/pc_push_file/
// pc_rename/pc_delete), not ADB. Deliberately smaller than the TV tab: no
// search/type-filter/storage-info/shortcuts for this first pass, and the
// drive-list level (PC_ROOT_PATH) renders drive tiles instead of file rows.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcFilesTab(container: AppContainer) {
    val viewModel: PcFilesViewModel = viewModel(factory = PcFilesViewModelFactory(container))
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sheetTarget by remember { mutableStateOf<PcFile?>(null) }
    var deleteTarget by remember { mutableStateOf<PcFile?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PcFile?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var copyError by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    val isSelecting = selectedPaths.isNotEmpty()

    fun toggleSelection(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.uploadFile(uri)
    }

    BackHandler(enabled = isSelecting) {
        selectedPaths = emptySet()
    }
    BackHandler(enabled = !isSelecting && uiState.currentPath != PC_ROOT_PATH) {
        viewModel.navigateUp()
    }

    androidx.compose.runtime.LaunchedEffect(uiState.currentPath) {
        selectedPaths = emptySet()
    }

    if (uiState.noPrimaryPc) {
        NotConnectedBody(
            modifier = Modifier.fillMaxSize(),
            title = "No primary PC set",
            message = "Add or choose a primary PC in PC Sync > Devices to browse its files.",
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isSelecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedPaths = emptySet() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        "${selectedPaths.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val entries = uiState.entries.filter { it.path in selectedPaths }
                        viewModel.downloadAll(entries)
                        selectedPaths = emptySet()
                    }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download selected")
                    }
                    IconButton(onClick = { batchDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST }) {
                        Icon(
                            if (viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList,
                            contentDescription = "Toggle view",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PcBreadcrumb(path = uiState.currentPath, onNavigate = viewModel::navigateTo)

            Box(modifier = Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        uiState.isLoading && uiState.entries.isEmpty() ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        uiState.error != null ->
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    uiState.error ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        uiState.entries.isEmpty() ->
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (uiState.currentPath == PC_ROOT_PATH) "No drives found." else "This folder is empty.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        viewMode == ViewMode.LIST -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(uiState.entries, key = { it.path }) { entry ->
                                PcFileRow(
                                    entry = entry,
                                    device = uiState.device,
                                    container = container,
                                    isRoot = uiState.currentPath == PC_ROOT_PATH,
                                    isSelecting = isSelecting,
                                    isSelected = entry.path in selectedPaths,
                                    onClick = {
                                        when {
                                            isSelecting -> toggleSelection(entry.path)
                                            entry.isDirectory -> viewModel.navigateTo(entry.path)
                                            else -> sheetTarget = entry
                                        }
                                    },
                                    onLongClick = { if (uiState.currentPath != PC_ROOT_PATH) toggleSelection(entry.path) },
                                )
                            }
                        }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 108.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(uiState.entries, key = { it.path }) { entry ->
                                PcFileGridCell(
                                    entry = entry,
                                    device = uiState.device,
                                    container = container,
                                    isRoot = uiState.currentPath == PC_ROOT_PATH,
                                    isSelecting = isSelecting,
                                    isSelected = entry.path in selectedPaths,
                                    onClick = {
                                        when {
                                            isSelecting -> toggleSelection(entry.path)
                                            entry.isDirectory -> viewModel.navigateTo(entry.path)
                                            else -> sheetTarget = entry
                                        }
                                    },
                                    onLongClick = { if (uiState.currentPath != PC_ROOT_PATH) toggleSelection(entry.path) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.currentPath != PC_ROOT_PATH) {
            FloatingActionButton(
                onClick = { uploadLauncher.launch("*/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Upload to this folder")
            }
        }
    }

    sheetTarget?.let { entry ->
        PcFileActionSheet(
            entry = entry,
            onDismiss = { sheetTarget = null },
            onDownload = {
                sheetTarget = null
                viewModel.download(entry) { result ->
                    result.onFailure { copyError = it.message ?: "Download failed" }
                }
            },
            onOpen = {
                sheetTarget = null
                scope.launch {
                    viewModel.openFile(entry) { file ->
                        if (file == null) return@openFile
                        val uri = FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(entry.name.substringAfterLast('.', ""))
                            ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            },
            onCopy = {
                sheetTarget = null
                scope.launch {
                    viewModel.openFile(entry) { file ->
                        if (file == null) {
                            copyError = "Couldn't copy: download failed"
                            return@openFile
                        }
                        val uri = FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
                        val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboardManager.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, entry.name, uri))
                    }
                }
            },
            onRename = {
                sheetTarget = null
                renameTarget = entry
            },
            onDelete = {
                sheetTarget = null
                deleteTarget = entry
            },
        )
    }

    renameTarget?.let { entry ->
        RenameFileDialog(
            currentName = entry.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(entry, newName) { result ->
                    if (result.isFailure) {
                        renameError = result.exceptionOrNull()?.message ?: "Rename failed"
                    }
                }
                renameTarget = null
            },
        )
    }

    renameError?.let { message ->
        AlertDialog(
            onDismissRequest = { renameError = null },
            title = { Text("Couldn't rename") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { renameError = null }) { Text("OK") } },
        )
    }

    copyError?.let { message ->
        AlertDialog(
            onDismissRequest = { copyError = null },
            title = { Text("Couldn't copy") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { copyError = null }) { Text("OK") } },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${entry.name}\"?") },
            text = { Text("This permanently deletes it from your PC. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry) { result ->
                        result.onFailure { deleteError = it.message ?: "Couldn't delete this file" }
                    }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
            title = { Text("Delete failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteError = null }) { Text("OK") } },
        )
    }

    if (batchDeleteConfirm) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text(if (count == 1) "Delete 1 item?" else "Delete $count items?") },
            text = { Text("This permanently deletes them from your PC. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val entries = uiState.entries.filter { it.path in selectedPaths }
                    viewModel.deleteAll(entries) { failedCount ->
                        if (failedCount > 0) deleteError = "$failedCount item(s) couldn't be deleted"
                    }
                    selectedPaths = emptySet()
                    batchDeleteConfirm = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { batchDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NotConnectedBody(modifier: Modifier = Modifier, title: String = "Not connected", message: String = "") {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PcBreadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) {
        val crumbs = mutableListOf("This PC" to PC_ROOT_PATH)
        if (path != PC_ROOT_PATH) {
            val driveRoot = path.take(3) // e.g. "C:\"
            crumbs.add(driveRoot.trimEnd('\\') to driveRoot)
            val rest = path.removePrefix(driveRoot).trim('\\')
            if (rest.isNotEmpty()) {
                var accum = driveRoot.trimEnd('\\')
                rest.split('\\').forEach { part ->
                    accum = "$accum\\$part"
                    crumbs.add(part to accum)
                }
            }
        }
        crumbs
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(" / ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = index != segments.lastIndex) { onNavigate(target) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcFileActionSheet(
    entry: PcFile,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth(),
            )
            SheetAction(icon = Icons.Filled.Download, label = "Download", onClick = onDownload)
            SheetAction(icon = Icons.Filled.OpenInNew, label = "Open", onClick = onOpen)
            if (isPcThumbnailable(entry)) {
                SheetAction(icon = Icons.Filled.ContentCopy, label = "Copy", onClick = onCopy)
            }
            SheetAction(icon = Icons.Filled.Edit, label = "Rename", onClick = onRename)
            SheetAction(icon = Icons.Filled.Delete, label = "Delete", onClick = onDelete, isDestructive = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcFileRow(
    entry: PcFile,
    device: com.tvfilebridge.app.clipboard.PcDevice?,
    container: AppContainer,
    isRoot: Boolean,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelecting) {
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        if (!isRoot && isPcThumbnailable(entry) && device != null) {
            PcThumbnailImage(
                entry = entry,
                device = device,
                container = container,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                isVideo = isVideo,
            )
        } else {
            val icon = when {
                isRoot -> Icons.Filled.Computer
                entry.isDirectory -> Icons.Filled.Folder
                isVideo -> Icons.Filled.Movie
                else -> Icons.Filled.InsertDriveFile
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = if (isRoot) {
                val total = entry.totalBytes
                if (total != null) "${formatBytes(entry.sizeBytes)} free of ${formatBytes(total)}" else ""
            } else if (entry.isDirectory) {
                formatModifiedDate(entry.modifiedAt)
            } else {
                listOf(formatBytes(entry.sizeBytes), formatModifiedDate(entry.modifiedAt)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcFileGridCell(
    entry: PcFile,
    device: com.tvfilebridge.app.clipboard.PcDevice?,
    container: AppContainer,
    isRoot: Boolean,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
            if (!isRoot && isPcThumbnailable(entry) && device != null) {
                PcThumbnailImage(entry = entry, device = device, container = container, modifier = Modifier.fillMaxSize(), isVideo = isVideo)
            } else {
                val icon = when {
                    isRoot -> Icons.Filled.Computer
                    entry.isDirectory -> Icons.Filled.Folder
                    isVideo -> Icons.Filled.Movie
                    else -> Icons.Filled.InsertDriveFile
                }
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelecting) {
                Box(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopStart) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isRoot) {
            val total = entry.totalBytes
            if (total != null) {
                Text(
                    "${formatBytes(entry.sizeBytes)} free of ${formatBytes(total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PC_THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

private fun isPcThumbnailable(entry: PcFile): Boolean {
    if (entry.isDirectory) return false
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return ext in PC_THUMBNAIL_EXTENSIONS || ext in VIDEO_EXTENSIONS
}

@Composable
private fun PcThumbnailImage(
    entry: PcFile,
    device: com.tvfilebridge.app.clipboard.PcDevice,
    container: AppContainer,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
) {
    val cacheFile by androidx.compose.runtime.produceState<java.io.File?>(initialValue = null, entry.path) {
        value = container.pcThumbnailRepository.getThumbnail(device, entry)
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            cacheFile != null -> coil.compose.AsyncImage(
                model = cacheFile,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            isVideo -> Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}
