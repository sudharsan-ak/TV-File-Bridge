package com.tvfilebridge.app.clipboard

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
private data class PushHeader(
    val type: String, // "text" or "image"
    val deviceName: String,
    val text: String? = null,
    val imageByteLength: Int = 0,
)

private const val PUSH_TIMEOUT_MS = 120_000

sealed class PushResult {
    data object Success : PushResult()
    data class Failed(val reason: String) : PushResult()
}

/**
 * Phone-side client for the PC companion's clipboard-push protocol: a plain
 * TCP connection carrying [4-byte little-endian length][JSON header][payload
 * bytes], matching PcCompanion's ClipboardServer exactly. One-shot per push -
 * unlike CursorBridge's persistent socket, this only needs a short-lived
 * connection per share action, not a continuous high-frequency stream.
 */
class ClipboardBridge(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pushText(device: PcDevice, text: String): PushResult = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                // Long enough to cover a first-time pairing approval, which
                // waits on the user actually noticing and clicking the PC's
                // Allow/Deny popup - a short timeout here previously reset
                // the connection (and so never actually recorded the pairing
                // on the PC side) if that took longer than a few seconds.
                socket.soTimeout = PUSH_TIMEOUT_MS
                val output = DataOutputStream(socket.getOutputStream())
                val header = PushHeader(type = "text", deviceName = Build.MODEL ?: "Android phone", text = text)
                writeHeader(output, header)
                readResponse(DataInputStream(socket.getInputStream()))
            }
        }.getOrElse {
            android.util.Log.e("ClipboardBridge", "pushText failed", it)
            PushResult.Failed(it.message ?: "Connection failed")
        }
    }

    suspend fun pushImage(device: PcDevice, uri: Uri): PushResult = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching PushResult.Failed("Could not read image")

            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = PUSH_TIMEOUT_MS
                val output = DataOutputStream(socket.getOutputStream())
                val header = PushHeader(
                    type = "image",
                    deviceName = Build.MODEL ?: "Android phone",
                    imageByteLength = bytes.size,
                )
                writeHeader(output, header)
                output.write(bytes)
                output.flush()
                readResponse(DataInputStream(socket.getInputStream()))
            }
        }.getOrElse {
            android.util.Log.e("ClipboardBridge", "pushImage failed", it)
            PushResult.Failed(it.message ?: "Connection failed")
        }
    }

    private fun writeHeader(output: DataOutputStream, header: PushHeader) {
        val headerJson = json.encodeToString(header)
        android.util.Log.d("ClipboardBridge", "sending header: $headerJson")
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array()
        output.write(lengthBytes)
        output.write(headerBytes)
        output.flush()
    }

    private fun readResponse(input: DataInputStream): PushResult {
        val lengthBytes = ByteArray(4)
        input.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (length <= 0 || length > 1_000_000) return PushResult.Failed("Invalid response")
        val responseBytes = ByteArray(length)
        input.readFully(responseBytes)
        val status = String(responseBytes, Charsets.UTF_8)
        return if (status == "ok") PushResult.Success else PushResult.Failed(status)
    }
}
