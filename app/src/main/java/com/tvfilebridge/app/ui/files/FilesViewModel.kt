package com.tvfilebridge.app.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvfilebridge.app.connection.AdbConnectionManager
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.files.FileRepository
import com.tvfilebridge.app.files.RemoteFile
import com.tvfilebridge.app.files.StorageInfo
import com.tvfilebridge.app.transfers.TransferManager
import com.tvfilebridge.app.transfers.TransferStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

const val ROOT_PATH = "/sdcard"

enum class SortMode { NAME, DATE }

enum class TypeFilter { ALL, IMAGES, VIDEOS, DOCUMENTS, OTHER }

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
private val DOCUMENT_EXT = setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx")

data class Shortcut(val label: String, val path: String)

val SHORTCUTS = listOf(
    Shortcut("Pictures", "$ROOT_PATH/Pictures"),
    Shortcut("Screenshots", "$ROOT_PATH/Pictures/Screenshots"),
    Shortcut("DCIM", "$ROOT_PATH/DCIM"),
    Shortcut("Download", "$ROOT_PATH/Download"),
    Shortcut("Movies", "$ROOT_PATH/Movies"),
)

data class FilesUiState(
    val currentPath: String = ROOT_PATH,
    val entries: List<RemoteFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortMode: SortMode = SortMode.NAME,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val typeFilter: TypeFilter = TypeFilter.ALL,
)

class FilesViewModel(
    private val fileRepository: FileRepository,
    private val transferManager: TransferManager,
    connectionManager: AdbConnectionManager,
) : ViewModel() {

    private val _currentPath = MutableStateFlow(ROOT_PATH)
    private val _entries = MutableStateFlow<List<RemoteFile>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _sortMode = MutableStateFlow(SortMode.NAME)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearching = MutableStateFlow(false)
    private val _typeFilter = MutableStateFlow(TypeFilter.ALL)
    private val _searchResults = MutableStateFlow<List<RemoteFile>?>(null)
    private var searchJob: Job? = null

    private data class BrowseState(val path: String, val entries: List<RemoteFile>, val loading: Boolean, val error: String?)
    private data class SearchState(val query: String, val searching: Boolean, val results: List<RemoteFile>?)

    private val browseState = combine(_currentPath, _entries, _isLoading, _error) { path, entries, loading, error ->
        BrowseState(path, entries, loading, error)
    }
    private val searchState = combine(_searchQuery, _isSearching, _searchResults) { query, searching, results ->
        SearchState(query, searching, results)
    }

    val uiState: StateFlow<FilesUiState> = combine(
        browseState,
        searchState,
        _sortMode,
        _typeFilter,
        connectionManager.state,
    ) { browse, search, sortMode, typeFilter, connectionState ->
        // Either a text search or a non-ALL type filter switches to a
        // recursive result set (folders hidden, whole subtree searched);
        // otherwise show the plain current-folder listing.
        val isFiltering = search.query.isNotBlank() || typeFilter != TypeFilter.ALL
        val activeEntries = if (isFiltering) search.results ?: emptyList() else browse.entries
        FilesUiState(
            currentPath = browse.path,
            entries = sortEntries(activeEntries, sortMode),
            isLoading = if (isFiltering) search.searching else browse.loading,
            error = browse.error,
            sortMode = sortMode,
            connectionState = connectionState,
            searchQuery = search.query,
            isSearching = search.searching,
            typeFilter = typeFilter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilesUiState())

    init {
        viewModelScope.launch {
            connectionManager.state.collect { state ->
                if (state is ConnectionState.Connected) {
                    refresh()
                }
            }
        }
    }

    fun navigateTo(path: String) {
        clearSearch()
        _typeFilter.value = TypeFilter.ALL
        _currentPath.value = path
        refresh()
    }

    fun navigateUp(): Boolean {
        val current = _currentPath.value
        if (current == ROOT_PATH) return false
        clearSearch()
        _typeFilter.value = TypeFilter.ALL
        val parent = current.substringBeforeLast("/").ifBlank { "/" }
        _currentPath.value = if (parent.length < ROOT_PATH.length) ROOT_PATH else parent
        refresh()
        return true
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun setTypeFilter(filter: TypeFilter) {
        _typeFilter.value = filter
        searchJob?.cancel()
        if (filter == TypeFilter.ALL) {
            if (_searchQuery.value.isBlank()) {
                _searchResults.value = null
                _isSearching.value = false
            }
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            val result = when (filter) {
                TypeFilter.IMAGES -> fileRepository.searchByExtensions(_currentPath.value, IMAGE_EXT)
                TypeFilter.VIDEOS -> fileRepository.searchByExtensions(_currentPath.value, VIDEO_EXT)
                TypeFilter.DOCUMENTS -> fileRepository.searchByExtensions(_currentPath.value, DOCUMENT_EXT)
                TypeFilter.OTHER -> fileRepository.searchByExtensions(
                    _currentPath.value,
                    IMAGE_EXT + VIDEO_EXT + DOCUMENT_EXT,
                    matchNone = true,
                )
                TypeFilter.ALL -> return@launch
            }
            result
                .onSuccess { _searchResults.value = it }
                .onFailure { _error.value = it.message ?: "Filter failed" }
            _isSearching.value = false
        }
    }

    /** Debounced so a fast typist doesn't trigger a `find` per keystroke. */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = null
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _isSearching.value = true
            fileRepository.search(_currentPath.value, query)
                .onSuccess { _searchResults.value = it }
                .onFailure { _error.value = it.message ?: "Search failed" }
            _isSearching.value = false
        }
    }

    suspend fun loadStorageInfo(): StorageInfo? = fileRepository.storageInfo(ROOT_PATH).getOrNull()

    suspend fun loadFolderSize(path: String): String? = fileRepository.folderSize(path).getOrNull()

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = null
        _isSearching.value = false
    }

    fun download(entry: RemoteFile) {
        transferManager.pullFile(entry.path, entry.name, entry.sizeBytes)
    }

    /**
     * Downloads to the app cache if not already cached at the right size, then
     * hands back the local file for an Open-with intent. Never re-pulls a file
     * already cached with a matching size (spec §8: never pull a file twice).
     */
    fun openFile(entry: RemoteFile, onReady: (java.io.File?) -> Unit) {
        viewModelScope.launch {
            onReady(pullToCacheFile(entry))
        }
    }

    /** Same cache-or-pull logic as [openFile], exposed directly for callers
     *  (like bulk Copy to PC) that need the local file without an Open intent. */
    private suspend fun pullToCacheFile(entry: RemoteFile): java.io.File? {
        val cacheFile = transferManager.cacheFileFor(entry.name)
        if (cacheFile.exists() && cacheFile.length() == entry.sizeBytes) return cacheFile
        val result = fileRepository.pullToCache(entry.path, cacheFile)
        return if (result.isSuccess) cacheFile else null
    }

    /**
     * Pulls each entry to cache (sequentially, reusing the same cache-or-pull
     * path as Open/download) and hands back local files for the caller to
     * push to a PC device - kept out of this ViewModel since building a
     * FileProvider content:// Uri needs a Context, which the Composable
     * already has via LocalContext.
     */
    suspend fun pullAllToCache(entries: List<RemoteFile>): List<java.io.File> =
        entries.filterNot { it.isDirectory }.mapNotNull { pullToCacheFile(it) }

    /**
     * Uploads to the currently viewed folder. If a file with the same name
     * already exists there, calls [onConflict] instead of starting the
     * upload, letting the caller show Overwrite/Keep both/Cancel (same
     * pattern as transfer redo, spec §5.0).
     */
    fun uploadFile(uri: Uri, onConflict: (fileName: String, proceed: (finalName: String) -> Unit) -> Unit) {
        viewModelScope.launch {
            val fileName = transferManager.fileNameFor(uri)
            val sizeBytes = transferManager.fileSizeFor(uri)
            val targetDir = _currentPath.value
            val remotePath = if (targetDir.endsWith("/")) "$targetDir$fileName" else "$targetDir/$fileName"

            val existsResult = fileRepository.exists(remotePath)
            if (existsResult.getOrDefault(false)) {
                onConflict(fileName) { finalName ->
                    val id = transferManager.pushFile(uri, targetDir, finalName, sizeBytes)
                    refreshOnUploadComplete(id, targetDir)
                }
            } else {
                val id = transferManager.pushFile(uri, targetDir, fileName, sizeBytes)
                refreshOnUploadComplete(id, targetDir)
            }
        }
    }

    /** Refreshes the listing once the given upload finishes, but only if the
     *  user is still looking at the folder it was uploaded into. */
    private fun refreshOnUploadComplete(transferId: String, targetDir: String) {
        viewModelScope.launch {
            transferManager.transfers
                .map { list -> list.find { it.id == transferId } }
                .first { it == null || it.status != TransferStatus.IN_PROGRESS }
            if (_currentPath.value == targetDir) refresh()
        }
    }

    fun rename(entry: RemoteFile, newName: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = fileRepository.rename(entry.path, newName)
            if (result.isSuccess) refresh()
            onDone(result.map {})
        }
    }

    fun delete(entry: RemoteFile, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = fileRepository.delete(entry.path)
            if (result.isSuccess) refresh()
            onDone(result)
        }
    }

    fun downloadAll(entries: List<RemoteFile>) {
        entries.filterNot { it.isDirectory }.forEach { download(it) }
    }

    fun deleteAll(entries: List<RemoteFile>, onDone: (failedCount: Int) -> Unit) {
        viewModelScope.launch {
            var failures = 0
            entries.forEach { entry ->
                val result = fileRepository.delete(entry.path)
                if (result.isFailure) failures++
            }
            refresh()
            onDone(failures)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            fileRepository.list(_currentPath.value)
                .onSuccess { _entries.value = it }
                .onFailure { _error.value = it.message ?: "Couldn't load this folder" }
            _isLoading.value = false
        }
    }

    private fun sortEntries(entries: List<RemoteFile>, mode: SortMode): List<RemoteFile> = when (mode) {
        SortMode.NAME -> entries.sortedWith(
            compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
        SortMode.DATE -> entries.sortedWith(
            compareByDescending<RemoteFile> { it.isDirectory }.thenByDescending { it.modifiedAt ?: 0L }
        )
    }

}
