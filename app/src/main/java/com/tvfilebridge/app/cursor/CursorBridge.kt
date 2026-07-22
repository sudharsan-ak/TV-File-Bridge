package com.tvfilebridge.app.cursor

import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.remote.RemoteControlRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

private const val TAG = "CursorBridge"
private const val LOCAL_FORWARD_PORT = 17912
private const val REMOTE_COMMAND_PORT = 7912

data class RemoteApp(val packageName: String, val label: String, val iconBase64: String?)

/**
 * Bridges the phone app to the TV companion app's command server: owns the
 * `adb forward` tunnel (via AdbConnectionManager.tcpForward) and sends the
 * plain-text command protocol over it.
 *
 * Keeps ONE persistent socket open rather than reconnecting per command -
 * a touchpad drag fires MOVE many times a second, and opening a fresh
 * `dadb.open()`-backed connection for each one flooded TcpForwarder's
 * connection-handling thread pool badly enough that a stream-closed error
 * from one connection surfaced as an uncaught exception on a background
 * thread and crashed the whole app. All sends are serialized through a
 * mutex since the socket isn't safe for concurrent writers.
 */
class CursorBridge(
    private val connectionManager: AdbConnectionManager,
    private val remoteControlRepository: RemoteControlRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()

    private var forwardHandle: AutoCloseable? = null
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    init {
        scope.launch {
            connectionManager.state.collect { state ->
                if (state !is ConnectionState.Connected) {
                    teardownAll()
                }
            }
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true

        closeSocket()
        if (forwardHandle == null) {
            forwardHandle = connectionManager.tcpForward(LOCAL_FORWARD_PORT, REMOTE_COMMAND_PORT)
        }
        if (forwardHandle == null) {
            Log.e(TAG, "failed to establish tcpForward")
            return false
        }

        return try {
            val newSocket = Socket("127.0.0.1", LOCAL_FORWARD_PORT)
            newSocket.soTimeout = 3000
            socket = newSocket
            writer = PrintWriter(newSocket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}")
            // The forward itself may be stale (e.g. after a reconnect) - drop
            // it too so the next attempt rebuilds both, not just the socket.
            teardownForward()
            false
        }
    }

    private fun closeSocket() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null
    }

    private fun teardownForward() {
        forwardHandle?.let { runCatching { it.close() } }
        forwardHandle = null
    }

    private fun teardownAll() {
        closeSocket()
        teardownForward()
    }

    private suspend fun sendCommand(command: String, expectReply: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            sendLock.withLock {
                if (!ensureConnected()) return@withLock null
                try {
                    writer!!.println(command)
                    if (writer!!.checkError()) throw java.io.IOException("write failed")

                    if (expectReply) {
                        val lines = StringBuilder()
                        while (true) {
                            val line = reader!!.readLine() ?: break
                            if (line == "END") break
                            lines.appendLine(line)
                        }
                        lines.toString()
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendCommand($command) failed: ${e.message}")
                    closeSocket()
                    null
                }
            }
        }

    suspend fun move(dx: Float, dy: Float) {
        sendCommand("MOVE $dx $dy")
    }

    /**
     * The companion app's AccessibilityService only reports where the cursor
     * currently is (its CLICK reply) - the actual tap is issued from here via
     * `adb shell input tap`, the same mechanism the D-pad's keyevents already
     * use reliably. dispatchGesture() on the TV side gets cancelled instantly
     * for synthesized single-point taps on this TV's OS build.
     *
     * Deliberately tap-only, not also DPAD_CENTER: a live-TV player's paused
     * overlay turned out to toggle play/pause on the tap alone just fine, but
     * sending DPAD_CENTER right after immediately toggled it back, so a
     * "click" looked like it silently did nothing. Apps that need D-pad-style
     * confirm semantics instead of a coordinate tap should be driven from the
     * D-pad tab, which still sends DPAD_CENTER only.
     */
    suspend fun click() {
        val reply = sendCommand("CLICK", expectReply = true) ?: return
        val coords = reply.trim().split(" ")
        val x = coords.getOrNull(0)?.toIntOrNull() ?: return
        val y = coords.getOrNull(1)?.toIntOrNull() ?: return
        remoteControlRepository.tap(x, y)
    }

    suspend fun show() {
        sendCommand("SHOW")
    }

    suspend fun hide() {
        sendCommand("HIDE")
    }

    suspend fun listApps(): List<RemoteApp> {
        val raw = sendCommand("LIST_APPS", expectReply = true) ?: return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    RemoteApp(packageName = parts[0], label = parts[1], iconBase64 = parts[2].ifBlank { null })
                } else {
                    null
                }
            }
            .toList()
    }

    suspend fun launchApp(packageName: String) {
        sendCommand("LAUNCH $packageName")
    }
}
