package com.tvfilebridge.app.clipboard

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PcFileRepository"
private const val CHUNK_SIZE = 64 * 1024

@Serializable
data class PcFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long? = null,
    val totalBytes: Long? = null, // drive rows only: total capacity, sizeBytes is free space
)

@Serializable
private data class PcRequestHeader(
    val type: String,
    val deviceName: String,
    val path: String? = null,
    val newName: String? = null,
    val fileName: String? = null,
    val fileByteLength: Long = 0L,
)

/**
 * Phone-side counterpart to the PC's PcFileServer/ClipboardServer pc_* branches
 * - same wire protocol shape as PcFileTransferManager/PcScreenshotRequester
 * (length-prefixed JSON header, then a type-specific response), but a fresh
 * socket per call rather than a persistent session: there's no ADB-style
 * connection state to hold onto, the PC is just reachable or it isn't.
 * "" as a path means the drive-list level ("This PC"), any other path is a
 * literal absolute Windows path the PC uses directly - no relative-to-root
 * scheme, same full-access model the TV Files feature already has via ADB.
 */
class PcFileRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun list(device: PcDevice, path: String): Result<List<PcFile>> = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 15_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                writeHeader(output, PcRequestHeader(type = "pc_list_dir", deviceName = deviceName(), path = path))

                val status = readStatus(input)
                if (status != "ok") throw IllegalStateException(status)

                val jsonLength = readInt32(input)
                val jsonBytes = ByteArray(jsonLength)
                if (!readExact(input, jsonBytes)) throw IllegalStateException("Connection closed before listing arrived")
                json.decodeFromString<List<PcFile>>(String(jsonBytes, Charsets.UTF_8))
            }
        }.onFailure { Log.e(TAG, "list($path) failed: ${it.message}", it) }
    }

    suspend fun pullToCache(device: PcDevice, remotePath: String, localFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 120_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                writeHeader(output, PcRequestHeader(type = "pc_pull_file", deviceName = deviceName(), path = remotePath))

                val status = readStatus(input)
                if (status != "ok") throw IllegalStateException(status)

                val totalBytes = readInt64(input)
                localFile.outputStream().use { out -> copyExactly(input, out, totalBytes) }
            }
        }.onFailure { Log.e(TAG, "pullToCache($remotePath) failed: ${it.message}", it) }
    }

    suspend fun push(device: PcDevice, localFile: File, remoteDirPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 120_000
                val output = socket.getOutputStream()
                val header = PcRequestHeader(
                    type = "pc_push_file",
                    deviceName = deviceName(),
                    path = remoteDirPath,
                    fileName = localFile.name,
                    fileByteLength = localFile.length(),
                )
                writeHeader(output, header)
                localFile.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }

                val status = readStatus(socket.getInputStream())
                if (status != "ok") throw IllegalStateException(status)
            }
        }.onFailure { Log.e(TAG, "push($remoteDirPath) failed: ${it.message}", it) }
    }

    suspend fun rename(device: PcDevice, path: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 15_000
                val output = socket.getOutputStream()
                writeHeader(output, PcRequestHeader(type = "pc_rename", deviceName = deviceName(), path = path, newName = newName))
                val status = readStatus(socket.getInputStream())
                if (status != "ok") throw IllegalStateException(status)
            }
        }.onFailure { Log.e(TAG, "rename($path -> $newName) failed: ${it.message}", it) }
    }

    suspend fun delete(device: PcDevice, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket(device.host, device.port).use { socket ->
                socket.soTimeout = 15_000
                val output = socket.getOutputStream()
                writeHeader(output, PcRequestHeader(type = "pc_delete", deviceName = deviceName(), path = path))
                val status = readStatus(socket.getInputStream())
                if (status != "ok") throw IllegalStateException(status)
            }
        }.onFailure { Log.e(TAG, "delete($path) failed: ${it.message}", it) }
    }

    private fun deviceName() = Build.MODEL ?: "Android phone"

    private fun writeHeader(output: OutputStream, header: PcRequestHeader) {
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array())
        output.write(headerBytes)
        output.flush()
    }

    private fun readStatus(input: InputStream): String {
        val length = readInt32(input)
        if (length <= 0 || length > 1_000_000) throw IllegalStateException("Invalid response")
        val bytes = ByteArray(length)
        if (!readExact(input, bytes)) throw IllegalStateException("Connection closed before response arrived")
        return String(bytes, Charsets.UTF_8)
    }

    private fun readInt32(input: InputStream): Int {
        val bytes = ByteArray(4)
        if (!readExact(input, bytes)) throw IllegalStateException("Connection closed")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readInt64(input: InputStream): Long {
        val bytes = ByteArray(8)
        if (!readExact(input, bytes)) throw IllegalStateException("Connection closed")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun copyExactly(input: InputStream, out: OutputStream, totalBytes: Long) {
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = totalBytes
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) throw java.io.IOException("Connection closed before all bytes arrived")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }
}
