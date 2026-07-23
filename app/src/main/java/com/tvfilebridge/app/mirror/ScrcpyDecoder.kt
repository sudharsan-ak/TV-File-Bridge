package com.tvfilebridge.app.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "ScrcpyDecoder"
private const val MIME_H264 = "video/avc"

/**
 * Feeds VideoPacketReader's parsed packets into a MediaCodec H264 decoder
 * configured to render straight to a Surface - no manual bitmap handling,
 * MediaCodec does the YUV->RGB conversion and presentation itself. The first
 * config packet (SPS/PPS) is used as csd-0 at configure() time; every
 * following packet is queued as a normal Annex-B input buffer, matching
 * exactly what MediaCodec's own H264 encoder emits (and its decoder expects)
 * on Android.
 */
class ScrcpyDecoder(private val surface: Surface, private val width: Int, private val height: Int) {

    @Volatile private var codec: MediaCodec? = null
    private val format = MediaFormat.createVideoFormat(MIME_H264, width, height)
    private var packetCount = 0
    private var renderedCount = 0

    fun start() {
        val mediaCodec = MediaCodec.createDecoderByType(MIME_H264)
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.start()
        codec = mediaCodec
        Log.i(TAG, "start: decoder started, ${width}x${height}, surface valid=${surface.isValid}")
    }

    /**
     * [outputTimeoutUs] lets a caller wait briefly for this specific frame
     * to actually finish decoding and render, instead of the usual
     * non-blocking poll - used for the cached config/keyframe fed
     * immediately on reattach, since decode takes a little real time and a
     * single instant dequeueOutputBuffer(0) right after queuing very often
     * finds nothing ready yet. Without waiting there, a decoded frame could
     * sit unclaimed until whenever the *next* feed() call happens to drain
     * it - for the very first frame after a reattach (cached keyframe fed
     * once, then a long wait for the next real keyframe from the live
     * stream), that gap was multiple seconds of the picture not appearing
     * at all. Normal streaming keeps the default 0/non-blocking poll so it
     * doesn't add per-packet latency to the steady-state frame rate.
     */
    fun feed(packet: VideoPacket, outputTimeoutUs: Long = 0L) {
        val mediaCodec = codec ?: return
        packetCount++
        if (packetCount <= 5 || packetCount % 60 == 0) {
            Log.i(TAG, "feed: #$packetCount size=${packet.payload.size} config=${packet.isConfig} key=${packet.isKeyFrame} pts=${packet.ptsUs} rendered=$renderedCount")
        }
        try {
            val index = mediaCodec.dequeueInputBuffer(10_000)
            if (index < 0) {
                Log.w(TAG, "feed: #$packetCount dequeueInputBuffer returned $index, dropping")
                return
            }

            val buffer: ByteBuffer = mediaCodec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(packet.payload)

            val flags = if (packet.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            mediaCodec.queueInputBuffer(index, 0, packet.payload.size, packet.ptsUs, flags)

            drainOutput(mediaCodec, timeoutUs = outputTimeoutUs)
        } catch (e: IllegalStateException) {
            // Benign: stop() ran concurrently on another thread (session
            // ending while a packet was mid-flight) - not a decode failure.
            Log.i(TAG, "feed: #$packetCount decoder already stopped, dropping")
        } catch (e: Exception) {
            Log.e(TAG, "feed: #$packetCount failed to decode packet: ${e.message}", e)
        }
    }

    private fun drainOutput(mediaCodec: MediaCodec, timeoutUs: Long = 0L) {
        val info = MediaCodec.BufferInfo()
        var firstCall = true
        while (true) {
            // Only the first dequeue in this batch waits up to timeoutUs;
            // once one frame's been pulled, drain anything else already
            // sitting in the queue without blocking further.
            val outIndex = mediaCodec.dequeueOutputBuffer(info, if (firstCall) timeoutUs else 0L)
            firstCall = false
            if (outIndex < 0) return
            renderedCount++
            // true = render this frame to the configured Surface immediately.
            mediaCodec.releaseOutputBuffer(outIndex, true)
        }
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stop: ${e.message}", e)
        } finally {
            codec = null
        }
    }
}
