package com.tvfilebridge.clipboardbridge

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
    val type: String,
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
 * Same wire protocol as the main app's ClipboardBridge/PC companion's
 * ClipboardServer - [4-byte little-endian length][JSON header][payload].
 * Duplicated here rather than shared since this is a standalone app with its
 * own APK; it only ever needs the text-push half of the protocol.
 */
suspend fun pushText(device: PcDeviceRef, text: String): PushResult = withContext(Dispatchers.IO) {
    runCatching {
        Socket(device.host, device.port).use { socket ->
            socket.soTimeout = PUSH_TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            val json = Json { ignoreUnknownKeys = true }
            val header = PushHeader(type = "text", deviceName = Build.MODEL ?: "Android phone", text = text)
            val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array()
            output.write(lengthBytes)
            output.write(headerBytes)
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val responseLengthBytes = ByteArray(4)
            input.readFully(responseLengthBytes)
            val responseLength = ByteBuffer.wrap(responseLengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (responseLength <= 0 || responseLength > 1_000_000) return@withContext PushResult.Failed("Invalid response")
            val responseBytes = ByteArray(responseLength)
            input.readFully(responseBytes)
            val status = String(responseBytes, Charsets.UTF_8)
            if (status == "ok") PushResult.Success else PushResult.Failed(status)
        }
    }.getOrElse {
        PushResult.Failed(it.message ?: "Connection failed")
    }
}
