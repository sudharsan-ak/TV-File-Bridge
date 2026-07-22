package com.tvfilebridge.app.transfers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Source
import okio.Timeout
import okio.buffer
import okio.source
import java.io.File
import java.util.UUID

private const val TAG = "TransferManager"

class TransferManager(
    private val context: Context,
    private val connectionManager: AdbConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(context.cacheDir, "downloads").apply { mkdirs() }

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    fun clearHistory() {
        _transfers.update { list -> list.filter { it.status == TransferStatus.IN_PROGRESS } }
    }

    fun pullFile(remotePath: String, fileName: String, sizeBytes: Long): String {
        val id = UUID.randomUUID().toString()
        val transfer = Transfer(
            id = id,
            direction = TransferDirection.PULL,
            fileName = fileName,
            remotePath = remotePath,
            sizeBytes = sizeBytes,
            startedAt = System.currentTimeMillis(),
            status = TransferStatus.IN_PROGRESS,
        )
        _transfers.update { it + transfer }

        scope.launch {
            runPull(id, remotePath, fileName, sizeBytes)
        }
        return id
    }

    fun retry(transferId: String) {
        val transfer = _transfers.value.find { it.id == transferId } ?: return
        when (transfer.direction) {
            TransferDirection.PULL -> pullFile(transfer.remotePath, transfer.fileName, transfer.sizeBytes)
            TransferDirection.PUSH -> {
                // Uploads retry against the original content URI, stashed
                // when the push was first started.
                val uri = pendingRetryUris[transferId] ?: return
                val newId = UUID.randomUUID().toString()
                val retryTransfer = transfer.copy(
                    id = newId,
                    startedAt = System.currentTimeMillis(),
                    status = TransferStatus.IN_PROGRESS,
                    progressBytes = 0L,
                    errorMessage = null,
                )
                _transfers.update { it + retryTransfer }
                pendingRetryUris[newId] = uri
                scope.launch { runPush(newId, uri, transfer.remotePath, transfer.sizeBytes) }
            }
        }
    }

    private val pendingRetryUris = mutableMapOf<String, Uri>()

    /** [remoteDir] is the destination folder currently being viewed; the file lands there. */
    fun pushFile(localUri: Uri, remoteDir: String, fileName: String, sizeBytes: Long): String {
        val id = UUID.randomUUID().toString()
        val remotePath = if (remoteDir.endsWith("/")) "$remoteDir$fileName" else "$remoteDir/$fileName"
        val transfer = Transfer(
            id = id,
            direction = TransferDirection.PUSH,
            fileName = fileName,
            remotePath = remotePath,
            sizeBytes = sizeBytes,
            startedAt = System.currentTimeMillis(),
            status = TransferStatus.IN_PROGRESS,
        )
        _transfers.update { it + transfer }
        pendingRetryUris[id] = localUri

        scope.launch {
            runPush(id, localUri, remotePath, sizeBytes)
        }
        return id
    }

    /** Local cache path a completed pull was saved to — used by "Open". */
    fun cacheFileFor(fileName: String): File = File(cacheDir, fileName)

    /**
     * Pulls into an arbitrary SAF destination document (used by folder sync,
     * where the destination is a user-picked phone folder, not the fixed
     * Downloads collection a plain download uses).
     */
    fun pullFileToUri(remotePath: String, fileName: String, sizeBytes: Long, destinationUri: Uri): String {
        val id = UUID.randomUUID().toString()
        val transfer = Transfer(
            id = id,
            direction = TransferDirection.PULL,
            fileName = fileName,
            remotePath = remotePath,
            sizeBytes = sizeBytes,
            startedAt = System.currentTimeMillis(),
            status = TransferStatus.IN_PROGRESS,
        )
        _transfers.update { it + transfer }

        scope.launch {
            runPullToUri(id, remotePath, sizeBytes, destinationUri)
        }
        return id
    }

    private suspend fun runPullToUri(id: String, remotePath: String, sizeBytes: Long, destinationUri: Uri) {
        val cacheFile = File(cacheDir, "sync_${UUID.randomUUID()}")
        var progressJob: kotlinx.coroutines.Job? = null

        progressJob = scope.launch {
            while (isActive) {
                updateTransfer(id) { it.copy(progressBytes = cacheFile.length()) }
                delay(200)
            }
        }

        val result = connectionManager.withDadb { dadb -> dadb.pull(cacheFile, remotePath) }
        progressJob.cancel()

        result.onFailure { e ->
            cacheFile.delete()
            Log.e(TAG, "pullFileToUri($remotePath) failed: ${e.message}", e)
            updateTransfer(id) { it.copy(status = TransferStatus.FAILED, errorMessage = e.message ?: "Download failed") }
            return
        }

        val copyOk = try {
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                cacheFile.inputStream().use { input -> input.copyTo(out) }
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "pullFileToUri($remotePath) copy failed: ${e.message}", e)
            false
        } finally {
            cacheFile.delete()
        }

        if (!copyOk) {
            updateTransfer(id) { it.copy(status = TransferStatus.FAILED, errorMessage = "Couldn't save to phone folder", progressBytes = sizeBytes) }
            return
        }

        updateTransfer(id) { it.copy(status = TransferStatus.SUCCEEDED, progressBytes = sizeBytes) }
    }

    private suspend fun runPull(id: String, remotePath: String, fileName: String, sizeBytes: Long) {
        val cacheFile = cacheFileFor(fileName)
        cacheFile.delete()
        var progressJob: kotlinx.coroutines.Job? = null

        progressJob = scope.launch {
            while (isActive) {
                updateTransfer(id) { it.copy(progressBytes = cacheFile.length()) }
                delay(200)
            }
        }

        val result = connectionManager.withDadb { dadb -> dadb.pull(cacheFile, remotePath) }
        progressJob.cancel()

        result.onFailure { e ->
            Log.e(TAG, "pull($remotePath) failed: ${e.message}", e)
            updateTransfer(id) { it.copy(status = TransferStatus.FAILED, errorMessage = e.message ?: "Download failed") }
            return
        }

        val savedUri = saveToMediaStoreDownloads(cacheFile, fileName)
        if (savedUri == null) {
            updateTransfer(id) {
                it.copy(status = TransferStatus.FAILED, errorMessage = "Couldn't save to Downloads", progressBytes = sizeBytes)
            }
            return
        }

        updateTransfer(id) {
            it.copy(status = TransferStatus.SUCCEEDED, progressBytes = sizeBytes)
        }
    }

    private suspend fun runPush(id: String, localUri: Uri, remotePath: String, sizeBytes: Long) {
        val counter = CountingSource(context, localUri)
        var progressJob: kotlinx.coroutines.Job? = null

        progressJob = scope.launch {
            while (isActive) {
                updateTransfer(id) { it.copy(progressBytes = counter.bytesRead) }
                delay(200)
            }
        }

        val result = connectionManager.withDadb { dadb ->
            counter.openIfNeeded()
            dadb.push(counter, remotePath, "664".toInt(8), System.currentTimeMillis() / 1000)
        }
        progressJob.cancel()
        counter.close()

        result.onFailure { e ->
            Log.e(TAG, "push($remotePath) failed: ${e.message}", e)
            updateTransfer(id) { it.copy(status = TransferStatus.FAILED, errorMessage = e.message ?: "Upload failed") }
            return
        }

        updateTransfer(id) { it.copy(status = TransferStatus.SUCCEEDED, progressBytes = sizeBytes) }
    }

    /** Wraps a content:// URI's stream as an okio Source, tracking bytes read for progress. */
    private class CountingSource(private val context: Context, private val uri: Uri) : Source {
        var bytesRead: Long = 0
            private set

        private var delegate: Source? = null

        fun openIfNeeded() {
            if (delegate != null) return
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Couldn't open $uri")
            delegate = stream.source()
        }

        override fun read(sink: okio.Buffer, byteCount: Long): Long {
            openIfNeeded()
            val read = delegate!!.read(sink, byteCount)
            if (read > 0) bytesRead += read
            return read
        }

        override fun timeout(): Timeout = delegate?.timeout() ?: Timeout.NONE
        override fun close() { delegate?.close() }
    }

    fun fileSizeFor(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) return cursor.getLong(sizeIndex)
        }
        return 0L
    }

    fun fileNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment ?: "upload"
    }

    private fun saveToMediaStoreDownloads(sourceFile: File, fileName: String): android.net.Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(itemUri)?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
        } ?: return null

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return itemUri
    }

    private fun updateTransfer(id: String, transform: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
