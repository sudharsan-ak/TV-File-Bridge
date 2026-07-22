package com.tvfilebridge.app.clipboard

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class ReceivedFileTransferStatus { IN_PROGRESS, SUCCEEDED, FAILED }

data class ReceivedFileTransfer(
    val id: String,
    val fileName: String,
    val fileUri: Uri,
    val sourceDeviceName: String,
    val sizeBytes: Long,
    val startedAt: Long,
    val status: ReceivedFileTransferStatus,
    val progressBytes: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * Live progress tracking for files arriving from a PC - the receive-side
 * counterpart to PcFileTransferManager (which tracks this phone's own sends).
 * Kept separate from PcFileTransferManager rather than merged into one
 * bidirectional list, so the Transfers > PC screen's Sent/Received sub-tabs
 * can each have their own independent Clear all without touching the other's
 * data - the same reasoning as the PC companion's PcTransferManager split.
 */
class ReceivedFileTransferManager {
    private val _transfers = MutableStateFlow<List<ReceivedFileTransfer>>(emptyList())
    val transfers: StateFlow<List<ReceivedFileTransfer>> = _transfers.asStateFlow()

    fun startReceive(fileName: String, fileUri: Uri, sourceDeviceName: String, sizeBytes: Long): String {
        val id = UUID.randomUUID().toString()
        val transfer = ReceivedFileTransfer(
            id = id,
            fileName = fileName,
            fileUri = fileUri,
            sourceDeviceName = sourceDeviceName,
            sizeBytes = sizeBytes,
            startedAt = System.currentTimeMillis(),
            status = ReceivedFileTransferStatus.IN_PROGRESS,
        )
        _transfers.update { listOf(transfer) + it }
        return id
    }

    fun reportProgress(id: String, bytesReceived: Long) {
        updateTransfer(id) { it.copy(progressBytes = bytesReceived) }
    }

    fun complete(id: String, status: ReceivedFileTransferStatus, errorMessage: String? = null) {
        updateTransfer(id) {
            it.copy(
                status = status,
                errorMessage = errorMessage,
                progressBytes = if (status == ReceivedFileTransferStatus.SUCCEEDED) it.sizeBytes else it.progressBytes,
            )
        }
    }

    fun clearHistory() {
        _transfers.update { list -> list.filter { it.status == ReceivedFileTransferStatus.IN_PROGRESS } }
    }

    private fun updateTransfer(id: String, transform: (ReceivedFileTransfer) -> ReceivedFileTransfer) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
