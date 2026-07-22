package com.tvfilebridge.tvcompanion

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "CommandServer"
const val COMMAND_SERVER_PORT = 7912

/**
 * Plain line-based text protocol over a local TCP socket - simplest thing
 * that works for this, easy to debug with `nc`/telnet while developing.
 * The phone side reaches this via `dadb.tcpForward(somePhonePort, 7912)`,
 * so this only ever needs to listen on localhost.
 *
 * Commands (one per line):
 *   MOVE <dx> <dy>       - move cursor by a relative delta (shows the cursor,
 *                          resets its auto-hide timer)
 *   CLICK                - tap at the current cursor position (also shows it)
 *   SHOW                 - show the cursor without moving it (entering cursor mode)
 *   HIDE                 - hide the cursor immediately (leaving cursor mode)
 *   LIST_APPS            - reply with one "package|label" line per app, then "END"
 *   LAUNCH <package>     - launch an app by package name
 */
class CommandServer(private val handler: CommandHandler) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val server = ServerSocket(COMMAND_SERVER_PORT)
                serverSocket = server
                Log.i(TAG, "listening on port $COMMAND_SERVER_PORT")
                while (running) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "accept failed: ${e.message}")
                        break
                    }
                    Thread({ handleClient(client) }, "command-client").start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "server failed: ${e.message}", e)
            }
        }, "command-server").start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    handleLine(line, writer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "client error: ${e.message}")
            }
        }
    }

    private fun handleLine(line: String, writer: PrintWriter) {
        val parts = line.trim().split(" ")
        when (parts.getOrNull(0)) {
            "MOVE" -> {
                val dx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val dy = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                handler.onMove(dx, dy)
            }
            "CLICK" -> {
                val (x, y) = handler.onClick()
                writer.println("$x $y")
                writer.println("END")
            }
            "SHOW" -> handler.onShowCursor()
            "HIDE" -> handler.onHideCursor()
            "LIST_APPS" -> {
                handler.listApps().forEach { app ->
                    writer.println("${app.packageName}|${app.label}|${app.iconBase64}")
                }
                writer.println("END")
            }
            "LAUNCH" -> {
                val packageName = parts.getOrNull(1)
                if (packageName != null) handler.launchApp(packageName)
            }
            else -> Log.e(TAG, "unknown command: $line")
        }
    }
}

interface CommandHandler {
    fun onMove(dx: Float, dy: Float)
    /** Returns the cursor's current (x, y) on the TV's screen for the phone to tap via ADB. */
    fun onClick(): Pair<Int, Int>
    fun onShowCursor()
    fun onHideCursor()
    fun listApps(): List<AppEntry>
    fun launchApp(packageName: String)
}

data class AppEntry(val packageName: String, val label: String, val iconBase64: String)
