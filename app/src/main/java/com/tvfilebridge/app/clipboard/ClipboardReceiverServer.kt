package com.tvfilebridge.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ClipboardReceiverServer"
const val CLIPBOARD_RECEIVER_PORT = 58822
private const val STREAM_CHUNK_SIZE = 64 * 1024

@Serializable
private data class IncomingPushHeader(
    val type: String, // "text", "image", or "file"
    val deviceName: String = "",
    val text: String? = null,
    val imageByteLength: Int = 0,
    val fileName: String? = null,
    val fileByteLength: Long = 0L,
)

/**
 * Phone-side counterpart to the PC's ClipboardServer: listens for pushes
 * FROM a PC (the reverse direction of ClipboardBridge, which only sends TO a
 * PC) and writes what arrives directly onto the phone's system clipboard via
 * ClipboardManager.setPrimaryClip() - writing the clipboard has no
 * background-app restriction the way reading it does, so this can run
 * continuously without needing the receiving app to be in the foreground.
 *
 * Uses the exact same [4-byte little-endian length][JSON header][payload
 * bytes] framing as the PC's server, just running the opposite direction, so
 * the PC app can reuse its ClipboardBridge-equivalent push logic unchanged.
 */
class ClipboardReceiverServer(
    private val context: Context,
    private val receivedFilesFolderStore: ReceivedFilesFolderStore,
    private val clipboardSendLog: ClipboardSendLog,
    private val receivedFileTransferManager: ReceivedFileTransferManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { acceptLoop() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop() {
        val server = try {
            ServerSocket(CLIPBOARD_RECEIVER_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind port $CLIPBOARD_RECEIVER_PORT: ${e.message}")
            running = false
            return
        }
        serverSocket = server
        Log.i(TAG, "listening on port $CLIPBOARD_RECEIVER_PORT")

        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed: ${e.message}")
                break
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use {
            try {
                socket.soTimeout = 15000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val lengthBytes = ByteArray(4)
                if (!readExact(input, lengthBytes)) return@withContext
                val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                if (headerLength <= 0 || headerLength > 1_000_000) return@withContext

                val headerBytes = ByteArray(headerLength)
                if (!readExact(input, headerBytes)) return@withContext
                val header = json.decodeFromString<IncomingPushHeader>(String(headerBytes, Charsets.UTF_8))

                when (header.type) {
                    "text" -> {
                        val text = header.text ?: return@withContext
                        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Clipboard", text))
                        Log.i(TAG, "set clipboard text from ${header.deviceName}")
                        clipboardSendLog.record(
                            ClipboardSendEntry(
                                direction = ClipboardEntryDirection.RECEIVED,
                                kind = ClipboardContentKind.TEXT,
                                textPreview = text,
                                targetDeviceName = header.deviceName,
                                status = ClipboardSendStatus.SUCCESS,
                            ),
                        )
                    }
                    "image" -> {
                        if (header.imageByteLength <= 0) return@withContext
                        val imageBytes = ByteArray(header.imageByteLength)
                        if (!readExact(input, imageBytes)) return@withContext
                        val uri = saveImageToCacheAndGetUri(imageBytes) ?: return@withContext
                        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newUri(context.contentResolver, "Image", uri)
                        clipboardManager.setPrimaryClip(clip)
                        Log.i(TAG, "set clipboard image from ${header.deviceName}")
                        clipboardSendLog.record(
                            ClipboardSendEntry(
                                direction = ClipboardEntryDirection.RECEIVED,
                                kind = ClipboardContentKind.IMAGE,
                                imageUri = uri,
                                targetDeviceName = header.deviceName,
                                status = ClipboardSendStatus.SUCCESS,
                            ),
                        )
                    }
                    "file" -> {
                        val fileName = header.fileName
                        if (fileName.isNullOrBlank() || header.fileByteLength <= 0) return@withContext
                        val uri = receiveFileStreamed(input, fileName, header.fileByteLength, header.deviceName) ?: return@withContext
                        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newUri(context.contentResolver, fileName, uri)
                        clipboardManager.setPrimaryClip(clip)
                        Log.i(TAG, "saved incoming file '$fileName' from ${header.deviceName}")
                        clipboardSendLog.record(
                            ClipboardSendEntry(
                                direction = ClipboardEntryDirection.RECEIVED,
                                kind = ClipboardContentKind.FILE,
                                fileName = fileName,
                                fileUri = uri,
                                targetDeviceName = header.deviceName,
                                status = ClipboardSendStatus.SUCCESS,
                            ),
                        )
                    }
                }

                val responseBytes = "ok".toByteArray(Charsets.UTF_8)
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(responseBytes.size).array())
                output.write(responseBytes)
                output.flush()
            } catch (e: Exception) {
                Log.e(TAG, "client error: ${e.message}")
            }
        }
    }

    /**
     * Streams the file payload straight into its destination in fixed-size
     * chunks rather than buffering the whole thing first - files aren't
     * capped in size (could be a multi-GB video), so this needs constant
     * peak memory regardless of file size, same reasoning as the PC side's
     * ReceiveFileStreamedAsync.
     */
    private suspend fun receiveFileStreamed(input: java.io.InputStream, fileName: String, totalBytes: Long, sourceDeviceName: String): Uri? {
        val folderUriString = receivedFilesFolderStore.folderUri.first()

        val outputUri = if (folderUriString != null) {
            createDocumentInTree(Uri.parse(folderUriString), fileName)
        } else {
            createMediaStoreDownloadsEntry(fileName)
        } ?: return null

        val output = try {
            context.contentResolver.openOutputStream(outputUri)
        } catch (e: Exception) {
            Log.e(TAG, "couldn't open output stream for $fileName: ${e.message}")
            null
        }
        if (output == null) {
            runCatching { context.contentResolver.delete(outputUri, null, null) }
            return null
        }

        val transferId = receivedFileTransferManager.startReceive(fileName, outputUri, sourceDeviceName, totalBytes)

        return try {
            output.use { out ->
                val buffer = ByteArray(STREAM_CHUNK_SIZE)
                var remaining = totalBytes
                var received = 0L
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) throw java.io.IOException("Connection closed before all file bytes arrived")
                    out.write(buffer, 0, read)
                    remaining -= read
                    received += read
                    receivedFileTransferManager.reportProgress(transferId, received)
                }
            }
            receivedFileTransferManager.complete(transferId, ReceivedFileTransferStatus.SUCCEEDED)
            outputUri
        } catch (e: Exception) {
            Log.e(TAG, "file receive failed: ${e.message}")
            receivedFileTransferManager.complete(transferId, ReceivedFileTransferStatus.FAILED, e.message)
            runCatching { context.contentResolver.delete(outputUri, null, null) }
            null
        }
    }

    private fun createDocumentInTree(treeUri: Uri, fileName: String): Uri? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.createDocument(context.contentResolver, parentUri, guessMimeType(fileName), fileName)
        } catch (e: Exception) {
            Log.e(TAG, "createDocumentInTree failed, falling back to Downloads: ${e.message}")
            createMediaStoreDownloadsEntry(fileName)
        }
    }

    private fun createMediaStoreDownloadsEntry(fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        // IS_PENDING is cleared right away rather than after the write
        // completes - unlike TransferManager's TV downloads (which write to a
        // local cache file first, then copy in one shot), this streams
        // directly into the MediaStore entry as bytes arrive over the
        // network, so there's no single "copy finished" moment to hang the
        // clear on without restructuring the streaming loop.
        val clearPending = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, clearPending, null, null)
        return uri
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun saveImageToCacheAndGetUri(imageBytes: ByteArray): Uri? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val cacheDir = File(context.cacheDir, "clipboard_received").apply { mkdirs() }
            val file = File(cacheDir, "${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            androidx.core.content.FileProvider.getUriForFile(context, "com.tvfilebridge.app.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "failed to save incoming image: ${e.message}")
            null
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
}
