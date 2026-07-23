package com.tvfilebridge.app.mirror

import dadb.AdbStream
import dadb.Dadb
import okio.BufferedSource

private const val DEVICE_NAME_FIELD_LENGTH = 64

data class ScrcpyHandshake(
    val deviceName: String,
    val codecId: Int,
    val width: Int,
    val height: Int,
)

/**
 * Opens the video and control sockets against a running scrcpy-server and
 * parses the initial handshake - confirmed wire format for v3.3.4 (see
 * project research): on the FIRST accepted socket only, a single dummy byte,
 * then a 64-byte null-padded device name, then (video socket) a 12-byte
 * codec-meta header (codec fourcc, width, height, all big-endian int32).
 * Server default option values used here (send_dummy_byte/send_device_meta/
 * send_codec_meta all true) - see ScrcpyServerLauncher's launch command.
 */
object ScrcpySocket {

    /** Opens video then control (matching scrcpy-server's own accept order) and reads the handshake off video. */
    fun open(dadb: Dadb, scid: String): Pair<AdbStream, AdbStream> {
        val socketName = "localabstract:scrcpy_$scid"
        val video = dadb.open(socketName)
        val control = dadb.open(socketName)
        return video to control
    }

    fun readHandshake(videoSource: BufferedSource): ScrcpyHandshake {
        videoSource.readByte() // dummy byte, discarded

        val nameBytes = videoSource.readByteArray(DEVICE_NAME_FIELD_LENGTH.toLong())
        val nullIndex = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        val deviceName = String(nameBytes, 0, nullIndex, Charsets.UTF_8)

        val codecId = videoSource.readInt() // okio BufferedSource.readInt() is big-endian
        val width = videoSource.readInt()
        val height = videoSource.readInt()

        return ScrcpyHandshake(deviceName, codecId, width, height)
    }
}
