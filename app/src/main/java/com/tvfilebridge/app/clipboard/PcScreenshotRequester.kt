package com.tvfilebridge.app.clipboard

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.tvfilebridge.app.remote.ScreenshotSaveHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PcScreenshotRequester"

@Serializable
private data class ScreenshotRequestHeader(
    val type: String = "screenshot_request",
    val deviceName: String,
)

/**
 * Requests a screenshot of the primary PC's screen - reverses the usual
 * phone-pushes-to-PC flow (text/image/file): this connects to the same
 * configured port and sends a new header type with no payload, then reads
 * back a status string followed by (if "ok") a 4-byte image length and the
 * raw PNG bytes, mirroring the exact framing PcFileTransferManager already
 * uses for its own response. No new port/listener needed on the PC side.
 */
class PcScreenshotRequester(
    private val context: Context,
    receivedFilesFolderStore: ReceivedFilesFolderStore,
) {
    private val saveHelper = ScreenshotSaveHelper(context, receivedFilesFolderStore)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun requestAndSave(device: PcDevice): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "pc_screenshot_${System.currentTimeMillis()}.png")
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 15_000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()

                val header = ScreenshotRequestHeader(deviceName = Build.MODEL ?: "Android phone")
                val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array())
                output.write(headerBytes)
                output.flush()

                val statusLengthBytes = ByteArray(4)
                if (!readExact(input, statusLengthBytes)) {
                    return@withContext Result.failure(IllegalStateException("No response from PC"))
                }
                val statusLength = ByteBuffer.wrap(statusLengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                val statusBytes = ByteArray(statusLength)
                if (!readExact(input, statusBytes)) {
                    return@withContext Result.failure(IllegalStateException("No response from PC"))
                }
                val status = String(statusBytes, Charsets.UTF_8)
                if (status != "ok") {
                    return@withContext Result.failure(IllegalStateException(status))
                }

                val imageLengthBytes = ByteArray(4)
                if (!readExact(input, imageLengthBytes)) {
                    return@withContext Result.failure(IllegalStateException("Connection closed before image arrived"))
                }
                val imageLength = ByteBuffer.wrap(imageLengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                tempFile.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = imageLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size, remaining)
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) throw java.io.IOException("Connection closed before all image bytes arrived")
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            val fileName = "PC Screenshot ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())}.png"
            val result = saveHelper.saveFromTempFile(tempFile, fileName)
            tempFile.delete()
            result
        } catch (e: Exception) {
            Log.e(TAG, "requestAndSave failed: ${e.message}", e)
            Result.failure(e)
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
