package com.tvfilebridge.app.mirror

import okio.BufferedSource

private const val FLAG_CONFIG = 1L shl 63
private const val FLAG_KEY_FRAME = 1L shl 62
private const val PTS_MASK = 0x3FFFFFFFFFFFFFFFL

data class VideoPacket(
    val payload: ByteArray,
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
)

/**
 * Reads the repeating per-packet frame-meta framing off the video socket:
 * [8-byte PTS+flags big-endian long][4-byte payload size big-endian int]
 * [payload bytes]. Bit 63 of the PTS field marks a codec-config packet
 * (SPS/PPS), bit 62 marks a key frame (only meaningful when bit 63 is unset).
 * Confirmed wire format for scrcpy v3.3.4 with send_frame_meta=true (the
 * server launch default here).
 */
object VideoPacketReader {

    fun readNext(source: BufferedSource): VideoPacket? {
        if (source.exhausted()) return null

        val ptsAndFlags = source.readLong() // okio readLong() is big-endian
        val size = source.readInt()
        val payload = source.readByteArray(size.toLong())

        val isConfig = (ptsAndFlags and FLAG_CONFIG) != 0L
        val isKeyFrame = !isConfig && (ptsAndFlags and FLAG_KEY_FRAME) != 0L
        val pts = if (isConfig) 0L else ptsAndFlags and PTS_MASK

        return VideoPacket(payload, pts, isConfig, isKeyFrame)
    }
}
