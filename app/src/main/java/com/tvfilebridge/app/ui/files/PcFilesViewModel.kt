package com.tvfilebridge.app.ui.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvfilebridge.app.clipboard.PcDevice
import com.tvfilebridge.app.clipboard.PcDeviceStore
import com.tvfilebridge.app.clipboard.PcFile
import com.tvfilebridge.app.clipboard.PcFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Empty path = the drive-list level ("This PC"); anything else is a literal absolute Windows path. */
const val PC_ROOT_PATH = ""

data class PcFilesUiState(
    val device: PcDevice? = null,
    val currentPath: String = PC_ROOT_PATH,
    val entries: List<PcFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val noPrimaryPc: Boolean = false,
)

/**
 * PC counterpart to FilesViewModel (TV browsing) - deliberately smaller: no
 * ADB-style persistent connection/ConnectionState to react to (the PC is just
 * reachable or it isn't, checked fresh per call), no search/type-filter/
 * storage-info/shortcuts for this first pass. Reuses PcDeviceStore's existing
 * "primary PC" concept (same one Copy-to-PC already uses) rather than
 * introducing a new selection mechanism.
 */
class PcFilesViewModel(
    private val context: Context,
    private val repository: PcFileRepository,
    private val pcDeviceStore: PcDeviceStore,
) : ViewModel() {

    private val cacheDir = File(context.cacheDir, "pc_downloads").apply { mkdirs() }

    private val _uiState = MutableStateFlow(PcFilesUiState())
    val uiState: StateFlow<PcFilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val device = pcDeviceStore.devices.first().find { it.isPrimary }
            if (device == null) {
                _uiState.value = _uiState.value.copy(noPrimaryPc = true)
            } else {
                _uiState.value = _uiState.value.copy(device = device)
                refresh()
            }
        }
    }

    fun navigateTo(path: String) {
        _uiState.value = _uiState.value.copy(currentPath = path)
        refresh()
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
        if (current == PC_ROOT_PATH) return false
        // Windows path parent: strip the trailing segment; if what's left is
        // just a drive root (e.g. "C:\"), go up to the drive-list level.
        val trimmed = current.trimEnd('\\')
        val lastSep = trimmed.lastIndexOf('\\')
        val parent = if (lastSep <= 2) PC_ROOT_PATH else trimmed.substring(0, lastSep)
        _uiState.value = _uiState.value.copy(currentPath = parent)
        refresh()
        return true
    }

    fun refresh() {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.list(device, _uiState.value.currentPath)
                .onSuccess { entries -> _uiState.value = _uiState.value.copy(entries = entries, isLoading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Couldn't load this folder", isLoading = false) }
        }
    }

    fun download(entry: PcFile, onDone: (Result<Unit>) -> Unit = {}) {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            val localFile = File(cacheDir, entry.name)
            val result = repository.pullToCache(device, entry.path, localFile)
            onDone(result)
        }
    }

    /** Downloads to app cache (unless already cached at the right size) and hands back the local file, for Open/Copy actions. */
    fun openFile(entry: PcFile, onReady: (File?) -> Unit) {
        viewModelScope.launch {
            onReady(pullToCacheFile(entry))
        }
    }

    private suspend fun pullToCacheFile(entry: PcFile): File? {
        val device = _uiState.value.device ?: return null
        val cacheFile = File(cacheDir, entry.name)
        if (cacheFile.exists() && cacheFile.length() == entry.sizeBytes) return cacheFile
        val result = repository.pullToCache(device, entry.path, cacheFile)
        return if (result.isSuccess) cacheFile else null
    }

    suspend fun pullAllToCache(entries: List<PcFile>): List<File> =
        entries.filterNot { it.isDirectory }.mapNotNull { pullToCacheFile(it) }

    fun uploadFile(uri: Uri, onDone: (Result<Unit>) -> Unit = {}) {
        val device = _uiState.value.device ?: return
        val targetDir = _uiState.value.currentPath
        if (targetDir == PC_ROOT_PATH) return // can't upload directly onto the drive-list level
        viewModelScope.launch {
            val fileName = fileNameFor(uri)
            val localFile = File(cacheDir, "upload_$fileName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            }
            val result = repository.push(device, localFile, targetDir)
            localFile.delete()
            if (result.isSuccess) refresh()
            onDone(result)
        }
    }

    fun rename(entry: PcFile, newName: String, onDone: (Result<Unit>) -> Unit) {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            val result = repository.rename(device, entry.path, newName)
            if (result.isSuccess) refresh()
            onDone(result)
        }
    }

    fun delete(entry: PcFile, onDone: (Result<Unit>) -> Unit) {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            val result = repository.delete(device, entry.path)
            if (result.isSuccess) refresh()
            onDone(result)
        }
    }

    fun downloadAll(entries: List<PcFile>) {
        entries.filterNot { it.isDirectory }.forEach { download(it) }
    }

    fun deleteAll(entries: List<PcFile>, onDone: (failedCount: Int) -> Unit) {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            var failures = 0
            entries.forEach { entry ->
                val result = repository.delete(device, entry.path)
                if (result.isFailure) failures++
            }
            refresh()
            onDone(failures)
        }
    }

    private fun fileNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment ?: "file"
    }
}
