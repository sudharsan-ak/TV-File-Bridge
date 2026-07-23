package com.tvfilebridge.app.mirror

import android.content.Context
import android.util.Log
import android.view.Surface
import com.tvfilebridge.app.connection.AdbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TAG = "MirrorSessionManager"

sealed class MirrorState {
    data object Idle : MirrorState()
    data object Starting : MirrorState()
    data class Active(val width: Int, val height: Int) : MirrorState()
    data class Failed(val message: String) : MirrorState()
}

/**
 * Orchestrates one screen-mirroring session end to end: push+launch
 * scrcpy-server (via AdbConnectionManager.withDadb, one-shot commands), open
 * the video/control sockets directly against the raw Dadb instance
 * (currentDadb()) since those stay open for the session's whole lifetime
 * outside withDadb's single-command lock (same exception tcpForward already
 * relies on), read the handshake to learn the video's width/height, then
 * WAIT for attachSurface() before creating the decoder - a TextureView's
 * Surface isn't available until after it's already composed/shown, so
 * start() and attachSurface() are deliberately two separate calls rather
 * than one that takes a Surface up front. The server process itself is left
 * running after the session ends (socket close only); stopServer() is a
 * separate, independent action.
 */
class MirrorSessionManager(
    private val context: Context,
    private val connectionManager: AdbConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Control-message sends (tap/drag/key) must reach the socket in the
    // exact order they were generated - Dispatchers.IO is a thread pool, so
    // independently-launched jobs on it can race and write out of order
    // (visible as jittery/reordered drags). limitedParallelism(1) gives a
    // single logical lane while staying non-blocking, without needing to
    // spin up and manage a whole separate thread by hand.
    private val controlDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    // Shared with AppScaffold so it can hide the bottom nav bar while the
    // mirror screen is in fullscreen landscape - TvMirrorScreen has no
    // direct reach to the Scaffold that owns the bottom nav, one level up.
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(value: Boolean) {
        _isFullscreen.value = value
    }

    private var controlSender: ScrcpyControlSender? = null
    private var decoder: ScrcpyDecoder? = null
    private var readJob: Job? = null
    private var videoStream: dadb.AdbStream? = null
    private var controlStream: dadb.AdbStream? = null
    private var pendingHandshake: ScrcpyHandshake? = null
    private var pendingSurface: Surface? = null

    // Cached so a brand-new MediaCodec decoder (created on reattach after
    // navigating away and back) can be fed a valid starting point
    // immediately, deterministically - the config packet (SPS/PPS) and the
    // most recent keyframe are otherwise only ever sent once each, whenever
    // the encoder happens to produce them, which could be many seconds away
    // from the moment of reattach. Cheaper and instant compared to relying
    // on RESET_VIDEO's timing, which is under the server's own control (a
    // live test showed it taking ~20s to take effect on an otherwise-idle
    // screen, since it only forces re-encoding on the encoder's own next
    // capture cycle, not synchronously).
    private var cachedConfigPacket: VideoPacket? = null
    private var cachedKeyFramePacket: VideoPacket? = null

    fun start() {
        _state.value = MirrorState.Starting
        scope.launch {
            try {
                val dadb = connectionManager.currentDadb()
                    ?: throw IllegalStateException("Not connected to a TV")

                ScrcpyServerLauncher.ensurePushed(context, connectionManager).getOrThrow()

                // scid is parsed via Integer.parseInt(value, 16) server-side
                // (confirmed in Options.java) - a HEX string, matching the
                // hex formatting the server also uses for the actual local
                // socket name (getSocketName: "scrcpy_%08x"). Kept within
                // Int range so the hex value never exceeds Integer.MAX_VALUE
                // (a value like 0xAABBCC11 would overflow and throw).
                val scid = Random.nextInt(0x10000000, 0x7FFFFFFF)
                val scidHex = "%08x".format(scid)
                ScrcpyServerLauncher.launch(dadb, scidHex)

                // The server needs a moment to bind its local socket after the
                // shell process starts before it'll accept a connection.
                kotlinx.coroutines.delay(800)

                val (video, control) = withContext(Dispatchers.IO) { ScrcpySocket.open(dadb, scidHex) }
                videoStream = video
                controlStream = control

                val handshake = withContext(Dispatchers.IO) { ScrcpySocket.readHandshake(video.source) }
                Log.i(TAG, "start: connected to ${handshake.deviceName}, ${handshake.width}x${handshake.height}")
                pendingHandshake = handshake
                controlSender = ScrcpyControlSender(control, handshake.width, handshake.height)

                _state.value = MirrorState.Active(handshake.width, handshake.height)
                pendingSurface?.let { attachSurfaceLocked(it, handshake, video) }
            } catch (e: Exception) {
                Log.e(TAG, "start: failed: ${e.message}", e)
                _state.value = MirrorState.Failed(e.message ?: "Couldn't start mirroring")
                cleanupSockets()
            }
        }
    }

    /**
     * Called once the hosting TextureView's Surface becomes available - may
     * arrive before or after the handshake completes, and may be called
     * again later (a fresh Surface each time the mirror screen is revisited
     * after navigating away and back), reusing the same still-open sockets.
     */
    fun attachSurface(surface: Surface) {
        pendingSurface = surface
        val handshake = pendingHandshake ?: return
        val video = videoStream ?: return
        attachSurfaceLocked(surface, handshake, video)
    }

    private var hasAttachedOnce = false

    private fun attachSurfaceLocked(surface: Surface, handshake: ScrcpyHandshake, video: dadb.AdbStream) {
        decoder?.stop() // tear down any decoder bound to a now-stale Surface before attaching the new one
        readJob?.cancel()

        val newDecoder = ScrcpyDecoder(surface, handshake.width, handshake.height)
        newDecoder.start()
        decoder = newDecoder

        // A fresh MediaCodec instance has no codec-config state at all -
        // feed it the cached config packet + most recent keyframe (if any)
        // immediately, so something shows up right away instead of a black
        // screen. But the LIVE stream at this point is mid-sequence of
        // P-frames that reference decode state the new decoder never built
        // (it only just saw one out-of-sequence cached keyframe, not the
        // real chain of frames since) - feeding those directly corrupts the
        // picture (visible macroblock artifacting) until a real keyframe
        // happens to reset things. So after the cached feed, live P-frames
        // are discarded (not fed to the decoder) until the next genuine
        // keyframe arrives from the stream itself, which cleanly resyncs
        // decode state - the cached frame covers the gap in the meantime.
        // RESET_VIDEO is sent in parallel to make that real keyframe arrive
        // as soon as possible rather than waiting on the encoder's own
        // schedule.
        var waitingForRealKeyFrame = false
        if (hasAttachedOnce) {
            // The cached keyframe's original PTS is whatever it was when
            // first captured, long before this reattach moment - feeding it
            // as-is left the frame decoded but never actually presented
            // (confirmed via logs: renderedCount incremented, but the
            // TextureView's onSurfaceTextureUpdated didn't fire until the
            // NEXT real keyframe arrived seconds later). Re-stamping with a
            // fresh "now" timestamp makes the surface treat it as a normal
            // current frame worth displaying immediately.
            // Some hardware decoders have a one-frame pipeline delay: the
            // output for frame N only becomes available once frame N+1 has
            // been queued, not immediately after N itself. Feeding the
            // cached keyframe just once therefore decodes it but never
            // flushes it out (confirmed via logs: no onSurfaceTextureUpdated
            // until the actual NEXT keyframe arrived from the live stream,
            // several seconds later) - feeding it a second time immediately
            // after forces that first decoded frame out.
            val now = System.nanoTime() / 1000
            cachedConfigPacket?.let { newDecoder.feed(it.copy(ptsUs = 0L), outputTimeoutUs = 20_000L) }
            cachedKeyFramePacket?.let {
                newDecoder.feed(it.copy(ptsUs = now), outputTimeoutUs = 20_000L)
                newDecoder.feed(it.copy(ptsUs = now + 1), outputTimeoutUs = 20_000L)
            }
            waitingForRealKeyFrame = true
            val sender = controlSender
            if (sender != null) scope.launch(controlDispatcher) { runCatching { sender.sendResetVideo() } }
        }
        hasAttachedOnce = true

        readJob = scope.launch {
            while (isActive) {
                val packet = withContext(Dispatchers.IO) { VideoPacketReader.readNext(video.source) } ?: break
                if (packet.isConfig) cachedConfigPacket = packet
                if (packet.isKeyFrame) cachedKeyFramePacket = packet

                if (waitingForRealKeyFrame) {
                    if (!packet.isConfig && !packet.isKeyFrame) continue // discard stale P-frames until a real keyframe resyncs decode state
                    if (packet.isKeyFrame) waitingForRealKeyFrame = false
                }
                newDecoder.feed(packet)
            }
        }
    }

    /**
     * Called when the hosting screen leaves composition (navigated away) -
     * tears down only the decoder/Surface (which is about to be destroyed
     * anyway and can't be fed into once it is), while keeping the video/
     * control sockets and the read loop alive by switching it to discard
     * mode, so the underlying stream doesn't stall waiting for a decoder
     * that no longer exists. The session survives; attachSurface() picks
     * back up with a fresh Surface if the screen is revisited, and stop()
     * (an explicit user action) is the only thing that ends it for real.
     */
    fun detachSurface() {
        val video = videoStream
        readJob?.cancel()
        decoder?.stop()
        decoder = null
        pendingSurface = null

        if (video != null) {
            readJob = scope.launch {
                while (isActive) {
                    // Keep draining so the socket doesn't back up; nothing to feed without a decoder.
                    if (withContext(Dispatchers.IO) { VideoPacketReader.readNext(video.source) } == null) break
                }
            }
        }
    }

    fun tap(x: Int, y: Int) {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { sender.tapSafely(x, y) }
    }

    fun dragStart(x: Int, y: Int) {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { runCatching { sender.dragStart(x, y) } }
    }

    fun dragMove(x: Int, y: Int) {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { runCatching { sender.dragMove(x, y) } }
    }

    fun dragEnd(x: Int, y: Int) {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { runCatching { sender.dragEnd(x, y) } }
    }

    /**
     * Synthesizes a vertical swipe from screen-center, for the Up/Down
     * scroll buttons - same DOWN/MOVE.../UP shape a real drag produces, just
     * generated on a timer instead of from touch input, so it reads as a
     * natural scroll gesture on the TV side rather than an instant jump.
     * A negative [deltaFraction] scrolls content up (swipe finger down);
     * positive scrolls content down (swipe finger up) - matches how a real
     * finger drag maps to scroll direction.
     */
    fun scroll(deltaFraction: Float) {
        val sender = controlSender ?: return
        val handshake = pendingHandshake ?: return
        scope.launch(controlDispatcher) {
            runCatching {
                val centerX = handshake.width / 2
                val centerY = handshake.height / 2
                val totalDelta = (handshake.height * deltaFraction).toInt()
                val steps = 10
                sender.dragStart(centerX, centerY)
                for (i in 1..steps) {
                    val y = (centerY - totalDelta * i / steps).coerceIn(0, handshake.height - 1)
                    sender.dragMove(centerX, y)
                    kotlinx.coroutines.delay(10L)
                }
                val endY = (centerY - totalDelta).coerceIn(0, handshake.height - 1)
                sender.dragEnd(centerX, endY)
            }
        }
    }

    /** Same synthesized-swipe approach as [scroll], horizontal instead of vertical - for Left/Right buttons. */
    fun scrollHorizontal(deltaFraction: Float) {
        val sender = controlSender ?: return
        val handshake = pendingHandshake ?: return
        scope.launch(controlDispatcher) {
            runCatching {
                val centerX = handshake.width / 2
                val centerY = handshake.height / 2
                val totalDelta = (handshake.width * deltaFraction).toInt()
                val steps = 10
                sender.dragStart(centerX, centerY)
                for (i in 1..steps) {
                    val x = (centerX - totalDelta * i / steps).coerceIn(0, handshake.width - 1)
                    sender.dragMove(x, centerY)
                    kotlinx.coroutines.delay(10L)
                }
                val endX = (centerX - totalDelta).coerceIn(0, handshake.width - 1)
                sender.dragEnd(endX, centerY)
            }
        }
    }

    /** Real D-pad navigation key events (KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT) - distinct from scroll/scrollHorizontal's synthesized swipe gestures. */
    fun pressDpad(keycode: Int) {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { sender.sendKeyPress(keycode) }
    }

    fun pressBack() {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { sender.sendKeyPress(KEYCODE_BACK) }
    }

    fun pressHome() {
        val sender = controlSender ?: return
        scope.launch(controlDispatcher) { sender.sendKeyPress(KEYCODE_HOME) }
    }

    /** Closes this session's sockets/decoder only - the scrcpy-server process on the TV keeps running. */
    fun stop() {
        readJob?.cancel()
        readJob = null
        decoder?.stop()
        decoder = null
        controlSender = null
        pendingHandshake = null
        pendingSurface = null
        hasAttachedOnce = false
        cachedConfigPacket = null
        cachedKeyFramePacket = null
        cleanupSockets()
        _state.value = MirrorState.Idle
    }

    private fun cleanupSockets() {
        runCatching { videoStream?.close() }
        runCatching { controlStream?.close() }
        videoStream = null
        controlStream = null
    }

    /** Independent of any open session - kills the server process on the TV entirely. */
    suspend fun stopServer(): Result<Unit> = ScrcpyServerLauncher.stopServer(connectionManager)
}

private suspend fun ScrcpyControlSender.tapSafely(x: Int, y: Int) {
    try {
        sendTap(x, y)
    } catch (e: Exception) {
        Log.e("MirrorSessionManager", "tap failed: ${e.message}", e)
    }
}
