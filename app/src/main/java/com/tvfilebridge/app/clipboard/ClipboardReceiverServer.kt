package com.tvfilebridge.app.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
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
private const val INSTALL_NOTIFICATION_CHANNEL_ID = "apk_install_ready"
private const val INSTALL_NOTIFICATION_ID = 2001
private const val STREAM_CHUNK_SIZE = 64 * 1024

@Serializable
private data class IncomingPushHeader(
    val type: String, // "text", "image", "file", "install-apk", or "folder-start"/"folder-file"/"folder-end"
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

                // Every existing message type (text/image/file) is a single
                // header+payload then done - reading just one iteration below
                // reproduces that exactly. "folder-start" is the only type
                // that keeps this loop going, for as many "folder-file"
                // headers as the PC side sends before "folder-end" closes it
                // out - all on the one connection, so the destination folder
                // only needs finding/creating once per batch instead of once
                // per file.
                var folderState: FolderReceiveState? = null
                while (true) {
                    val lengthBytes = ByteArray(4)
                    if (!readExact(input, lengthBytes)) break
                    val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (headerLength <= 0 || headerLength > 1_000_000) break

                    val headerBytes = ByteArray(headerLength)
                    if (!readExact(input, headerBytes)) break
                    val header = json.decodeFromString<IncomingPushHeader>(String(headerBytes, Charsets.UTF_8))

                    val shouldContinue = handleMessage(header, input, folderState) { folderState = it }
                    if (!shouldContinue) break
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

    /** Holds the destination folder while a folder-start/.../folder-end batch is in progress. */
    private class FolderReceiveState(val root: DocumentFile, val folderName: String, val deviceName: String, var fileCount: Int = 0)

    /** Returns true if the connection should keep reading more headers (mid-folder-batch), false once done. */
    private suspend fun handleMessage(
        header: IncomingPushHeader,
        input: java.io.InputStream,
        folderState: FolderReceiveState?,
        setFolderState: (FolderReceiveState?) -> Unit,
    ): Boolean {
        when (header.type) {
            "text" -> {
                val text = header.text ?: return false
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
                return false
            }
            "image" -> {
                if (header.imageByteLength <= 0) return false
                val imageBytes = ByteArray(header.imageByteLength)
                if (!readExact(input, imageBytes)) return false
                val uri = saveImageToCacheAndGetUri(imageBytes) ?: return false
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
                return false
            }
            "file" -> {
                val fileName = header.fileName
                if (fileName.isNullOrBlank() || header.fileByteLength <= 0) return false
                val uri = receiveFileStreamed(input, fileName, header.fileByteLength, header.deviceName) ?: return false
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
                return false
            }
            "install-apk" -> {
                val fileName = header.fileName
                if (fileName.isNullOrBlank() || header.fileByteLength <= 0) return false
                val uri = receiveFileStreamed(input, fileName, header.fileByteLength, header.deviceName) ?: return false
                Log.i(TAG, "received APK '$fileName' from ${header.deviceName}, launching installer")
                launchPackageInstaller(uri, fileName)
                return false
            }
            "folder-start" -> {
                val folderName = header.fileName
                if (folderName.isNullOrBlank()) return false
                val root = openReceiveRoot()
                if (root == null) {
                    Log.e(TAG, "folder-start: no writable destination folder available")
                    return false
                }
                // Reuse an existing same-named folder rather than always
                // making a new one (Explorer's own copy-again behavior) -
                // findFile() first so re-syncing the same folder doesn't pile
                // up "Trip Photos", "Trip Photos (1)", etc. on repeat sends.
                val folderDoc = root.findFile(folderName)?.takeIf { it.isDirectory }
                    ?: root.createDirectory(folderName)
                if (folderDoc == null) {
                    Log.e(TAG, "folder-start: couldn't create folder '$folderName'")
                    return false
                }
                setFolderState(FolderReceiveState(folderDoc, folderName, header.deviceName))
                Log.i(TAG, "folder-start '$folderName' from ${header.deviceName}")
                return true
            }
            "folder-file" -> {
                val relativePath = header.fileName
                if (relativePath.isNullOrBlank() || header.fileByteLength <= 0 || folderState == null) return false
                val uri = receiveFileIntoFolder(input, folderState.root, relativePath, header.fileByteLength)
                if (uri != null) folderState.fileCount++
                return true
            }
            "folder-end" -> {
                if (folderState != null) {
                    Log.i(TAG, "folder-end '${folderState.folderName}': ${folderState.fileCount} file(s) from ${folderState.deviceName}")
                    clipboardSendLog.record(
                        ClipboardSendEntry(
                            direction = ClipboardEntryDirection.RECEIVED,
                            kind = ClipboardContentKind.FILE,
                            fileName = folderState.folderName,
                            fileUri = folderState.root.uri,
                            targetDeviceName = folderState.deviceName,
                            status = ClipboardSendStatus.SUCCESS,
                        ),
                    )
                }
                return false
            }
            else -> return false
        }
    }

    /** Same destination the plain "file" case uses (configured Save location, or Downloads) - as a DocumentFile so folders can be found/created under it. */
    private suspend fun openReceiveRoot(): DocumentFile? {
        val folderUriString = receivedFilesFolderStore.folderUri.first() ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
    }

    /**
     * Creates (or reuses) every folder segment in [relativePath] under [root]
     * - the file's own directory, e.g. "Day 1/photo.jpg" under a "Trip
     * Photos" root creates/reuses "Day 1" first - then streams the file into
     * the final segment. Mirrors receiveFileStreamed's streaming approach,
     * just targeting a caller-supplied tree instead of always the root.
     */
    private fun receiveFileIntoFolder(input: java.io.InputStream, root: DocumentFile, relativePath: String, totalBytes: Long): Uri? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        var dir = root
        for (folderName in segments.dropLast(1)) {
            dir = dir.findFile(folderName)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(folderName)
                ?: run {
                    Log.e(TAG, "receiveFileIntoFolder: couldn't create subfolder '$folderName'")
                    return null
                }
        }
        val fileName = segments.last()
        // Same overwrite-by-replace approach as a repeat top-level folder
        // sync: delete any existing file with this name first rather than
        // leaving stale duplicates or silently failing createFile.
        dir.findFile(fileName)?.delete()
        val fileDoc = dir.createFile(guessMimeType(fileName), fileName) ?: return null

        val output = try {
            context.contentResolver.openOutputStream(fileDoc.uri)
        } catch (e: Exception) {
            Log.e(TAG, "couldn't open output stream for $fileName: ${e.message}")
            null
        }
        if (output == null) {
            runCatching { fileDoc.delete() }
            return null
        }

        return try {
            output.use { out ->
                val buffer = ByteArray(STREAM_CHUNK_SIZE)
                var remaining = totalBytes
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) throw java.io.IOException("Connection closed before all file bytes arrived")
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
            fileDoc.uri
        } catch (e: Exception) {
            Log.e(TAG, "folder file receive failed ($relativePath): ${e.message}")
            runCatching { fileDoc.delete() }
            null
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

    /**
     * Can't call startActivity() directly here to pop the install screen
     * open the moment the APK arrives - Android has blocked a background
     * app from launching an Activity like that since Android 10, regardless
     * of FLAG_ACTIVITY_NEW_TASK or any manifest permission; it fails
     * silently (no exception) unless the calling app currently owns the
     * foreground window, which this background socket listener never does.
     * Confirmed against this exact failure: a push logged "launching
     * installer" with no error, yet nothing ever appeared on screen.
     *
     * The notification-tap pattern sidesteps this cleanly: tapping a
     * notification counts as the user-initiated action Android requires
     * before allowing a new Activity to come forward, so the PendingIntent
     * below reliably opens the same install confirmation screen
     * startActivity() couldn't show directly - one extra tap, but it works
     * regardless of what the user is doing elsewhere on the phone at the
     * moment the push arrives. This is also the general pattern Android
     * itself recommends for this situation (see "Restrictions on starting
     * activities from the background" in the platform docs).
     */
    private fun launchPackageInstaller(apkUri: Uri, fileName: String) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            INSTALL_NOTIFICATION_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        ensureInstallNotificationChannel()
        val notification = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Update ready to install")
            .setContentText("$fileName was sent from your PC - tap to install")
            // Same built-in system icon as ConnectionForegroundService -
            // inflating our own vector drawable through the resource-ID
            // Drawable/Icon paths throws a platform bug on this device.
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(INSTALL_NOTIFICATION_ID, notification)
    }

    private fun ensureInstallNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "APK updates ready to install",
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
