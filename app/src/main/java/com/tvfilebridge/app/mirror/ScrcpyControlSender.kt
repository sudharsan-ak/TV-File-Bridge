package com.tvfilebridge.app.mirror

import dadb.AdbStream
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TYPE_INJECT_KEYCODE = 0
private const val TYPE_INJECT_TOUCH_EVENT = 2
private const val TYPE_RESET_VIDEO = 17

private const val ACTION_DOWN = 0
private const val ACTION_UP = 1
private const val ACTION_MOVE = 2

private const val POINTER_ID_MOUSE = -1L // SC_POINTER_ID_GENERIC_FINGER isn't needed for a single-touch remote; matches scrcpy's own mouse/single-pointer convention
private const val BUTTON_PRIMARY = 1

// Android KeyEvent keycodes (android.view.KeyEvent) - not imported directly
// since this class has no Android framework dependency otherwise.
const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val KEYCODE_DPAD_UP = 19
const val KEYCODE_DPAD_DOWN = 20
const val KEYCODE_DPAD_LEFT = 21
const val KEYCODE_DPAD_RIGHT = 22

/**
 * Writes INJECT_TOUCH_EVENT control messages (32 bytes, all big-endian) to
 * scrcpy-server's control socket - confirmed byte layout for v3.3.4:
 * [type:1][action:1][pointerId:8][x:4][y:4][videoWidth:2][videoHeight:2]
 * [pressure:2][actionButton:4][buttons:4]. width/height must be the video
 * frame's own dimensions (from the codec-meta handshake), not the TV's real
 * screen resolution - the server rescales using those.
 */
class ScrcpyControlSender(
    private val controlStream: AdbStream,
    private val videoWidth: Int,
    private val videoHeight: Int,
) {

    /**
     * A short delay between DOWN and UP - without one, some receivers'
     * gesture detectors treat a same-millisecond down/up pair as noise and
     * drop it, which showed up as taps feeling unreliable/insensitive. Kept
     * short (30ms, down from an initial 60ms) since it's a fixed cost paid
     * on every single tap.
     */
    suspend fun sendTap(x: Int, y: Int) {
        writeTouch(ACTION_DOWN, x, y, pressure = 1f)
        delay(30L)
        writeTouch(ACTION_UP, x, y, pressure = 0f)
    }

    /**
     * Live drag events, one call per gesture callback (dragStart/onDrag/
     * dragEnd) rather than batching a whole gesture into one send at the
     * end - streaming MOVE events as the finger moves is what makes a drag
     * track in real time instead of catching up all at once on release.
     * No artificial delay between MOVE events either: Compose's onDrag
     * already fires at the display's own frame cadence, so an extra sleep
     * on top of that just adds lag for no benefit.
     */
    fun dragStart(x: Int, y: Int) {
        writeTouch(ACTION_DOWN, x, y, pressure = 1f)
    }

    fun dragMove(x: Int, y: Int) {
        writeTouch(ACTION_MOVE, x, y, pressure = 1f)
    }

    fun dragEnd(x: Int, y: Int) {
        writeTouch(ACTION_UP, x, y, pressure = 0f)
    }

    /**
     * Sends a real Android key event (e.g. KEYCODE_BACK/KEYCODE_HOME) via
     * INJECT_KEYCODE - 14 bytes: [type:1][action:1][keycode:4][repeat:4]
     * [metastate:4], all big-endian, confirmed layout for v3.3.4.
     */
    suspend fun sendKeyPress(keycode: Int) {
        writeKeyEvent(ACTION_DOWN, keycode)
        delay(60L)
        writeKeyEvent(ACTION_UP, keycode)
    }

    /**
     * Asks the server to force a fresh keyframe + config packet right now -
     * needed whenever a new MediaCodec decoder instance attaches mid-stream
     * (e.g. reattaching a Surface after navigating away and back), since a
     * brand-new decoder has no codec-config state and the SPS/PPS config
     * packet is otherwise only ever sent once, at the very start of the
     * whole session. 1-byte message, no payload.
     */
    fun sendResetVideo() {
        controlStream.sink.writeByte(TYPE_RESET_VIDEO)
        controlStream.sink.flush()
    }

    private fun writeKeyEvent(action: Int, keycode: Int) {
        val buffer = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_INJECT_KEYCODE.toByte())
        buffer.put(action.toByte())
        buffer.putInt(keycode)
        buffer.putInt(0) // repeat
        buffer.putInt(0) // metastate

        controlStream.sink.write(buffer.array())
        controlStream.sink.flush()
    }

    private fun writeTouch(action: Int, x: Int, y: Int, pressure: Float) {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buffer.put(action.toByte())
        buffer.putLong(POINTER_ID_MOUSE)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(floatToU16Fp(pressure))
        buffer.putInt(if (action == ACTION_UP) 0 else BUTTON_PRIMARY)
        buffer.putInt(if (action == ACTION_UP) 0 else BUTTON_PRIMARY)

        controlStream.sink.write(buffer.array())
        controlStream.sink.flush()
    }

    private fun floatToU16Fp(value: Float): Short {
        val clamped = value.coerceIn(0f, 1f)
        return (clamped * 0xFFFF).toInt().toShort()
    }
}
