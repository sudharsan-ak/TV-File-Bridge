package com.tvfilebridge.app.clipboard

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "PcFileTransferManager"
private const val CHUNK_SIZE = 64 * 1024

enum class PcFileTransferStatus { IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED }

data class PcFileTransfer(
    val id: String,
    val fileName: String,
    val targetDeviceName: String,
    val sizeBytes: Long,
    val startedAt: Long,
    val status: PcFileTransferStatus,
    val progressBytes: Long = 0L,
    val errorMessage: String? = null,
)

@Serializable
private data class FilePushHeader(
    val type: String = "file",
    val deviceName: String,
    val fileName: String,
    val fileByteLength: Long,
)

/**
 * Streamed, cancellable phone -> PC file push - separate from ClipboardBridge
 * (text/image), which sends its whole payload in one shot since those are
 * small. Files can be arbitrarily large (a multi-GB video), so this reads and
 * writes in fixed-size chunks the whole way through: peak memory stays at one
 * CHUNK_SIZE buffer regardless of the file's total size, and a cancellation
 * can take effect between any two chunks rather than only before/after the
 * entire transfer.
 */
class PcFileTransferManager(private val context: Context, private val clipboardSendLog: ClipboardSendLog) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // encodeDefaults = true - without it, kotlinx.serialization omits any
    // field that equals its declared default, which for FilePushHeader.type
    // ("file") meant the "type" key never appeared in the JSON at all. The PC
    // side's PushHeader.Type then deserialized to its own default (""),
    // so ProcessPush's `header.Type == "file"` branch silently never matched
    // for any file push - the connection completed "successfully" by the
    // framing/response protocol, but no file was ever actually saved.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _transfers = MutableStateFlow<List<PcFileTransfer>>(emptyList())
    val transfers: StateFlow<List<PcFileTransfer>> = _transfers.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun clearHistory() {
        _transfers.update { list -> list.filter { it.status == PcFileTransferStatus.IN_PROGRESS } }
    }

    fun cancel(transferId: String) {
        jobs[transferId]?.cancel()
    }

    /** Pushes a single file; call once per URI for a multi-file share (sequential, same pattern as multi-image). */
    fun pushFile(device: PcDevice, uri: Uri): String {
        val id = UUID.randomUUID().toString()
        val fileName = fileNameFor(uri)
        val sizeBytes = fileSizeFor(uri)

        val transfer = PcFileTransfer(
            id = id,
            fileName = fileName,
            targetDeviceName = device.name,
            sizeBytes = sizeBytes,
            startedAt = System.currentTimeMillis(),
            status = PcFileTransferStatus.IN_PROGRESS,
        )
        _transfers.update { it + transfer }

        val job = scope.launch {
            runPush(id, device, uri, fileName, sizeBytes)
        }
        jobs[id] = job
        return id
    }

    private suspend fun runPush(id: String, device: PcDevice, uri: Uri, fileName: String, sizeBytes: Long) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                updateTransfer(id) { it.copy(status = PcFileTransferStatus.FAILED, errorMessage = "Couldn't read file") }
                return
            }

            input.use { stream ->
                Socket(device.host, device.port).use { socket ->
                    socket.soTimeout = 120_000
                    val output = socket.getOutputStream()

                    val header = FilePushHeader(deviceName = Build.MODEL ?: "Android phone", fileName = fileName, fileByteLength = sizeBytes)
                    val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array())
                    output.write(headerBytes)
                    output.flush()

                    val buffer = ByteArray(CHUNK_SIZE)
                    var sent = 0L
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        sent += read
                        updateTransfer(id) { it.copy(progressBytes = sent) }
                    }
                    output.flush()

                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        // Cancelled mid-stream: close the socket so the PC side's
                        // read fails fast instead of hanging on a connection that
                        // will never send the remaining bytes.
                        updateTransfer(id) { it.copy(status = PcFileTransferStatus.CANCELLED) }
                        return
                    }

                    val input2 = socket.getInputStream()
                    val lengthBytes = ByteArray(4)
                    if (!readExact(input2, lengthBytes)) {
                        updateTransfer(id) { it.copy(status = PcFileTransferStatus.FAILED, errorMessage = "No response from PC", progressBytes = sent) }
                        return
                    }
                    val responseLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    val responseBytes = ByteArray(responseLength)
                    readExact(input2, responseBytes)
                    val status = String(responseBytes, Charsets.UTF_8)

                    if (status == "ok") {
                        updateTransfer(id) { it.copy(status = PcFileTransferStatus.SUCCEEDED, progressBytes = sizeBytes) }
                        clipboardSendLog.record(
                            ClipboardSendEntry(
                                direction = ClipboardEntryDirection.SENT,
                                kind = ClipboardContentKind.FILE,
                                fileName = fileName,
                                targetDeviceName = device.name,
                                status = ClipboardSendStatus.SUCCESS,
                            ),
                        )
                    } else {
                        updateTransfer(id) { it.copy(status = PcFileTransferStatus.FAILED, errorMessage = status, progressBytes = sent) }
                        clipboardSendLog.record(
                            ClipboardSendEntry(
                                direction = ClipboardEntryDirection.SENT,
                                kind = ClipboardContentKind.FILE,
                                fileName = fileName,
                                targetDeviceName = device.name,
                                status = ClipboardSendStatus.FAILED,
                                failureReason = status,
                            ),
                        )
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            updateTransfer(id) { it.copy(status = PcFileTransferStatus.CANCELLED) }
        } catch (e: IOException) {
            Log.e(TAG, "pushFile failed: ${e.message}", e)
            updateTransfer(id) { it.copy(status = PcFileTransferStatus.FAILED, errorMessage = e.message ?: "Connection failed") }
        } finally {
            jobs.remove(id)
        }
    }

    private fun readExact(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun fileSizeFor(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) return cursor.getLong(sizeIndex)
        }
        return 0L
    }

    private fun fileNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun updateTransfer(id: String, transform: (PcFileTransfer) -> PcFileTransfer) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
