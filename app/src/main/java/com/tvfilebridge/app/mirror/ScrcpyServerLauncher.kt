package com.tvfilebridge.app.mirror

import android.content.Context
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import dadb.AdbShellStream
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ScrcpyServerLauncher"
private const val SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
private const val SCRCPY_VERSION = "3.3.4" // must match the bundled scrcpy-server.jar exactly - the server rejects a mismatched client version string

/**
 * Pushes and launches scrcpy-server on the TV, same server jar PC Companion
 * bundles - Server.main() checks the client-supplied version string against
 * its own compiled version and aborts on mismatch, so this must always match
 * the bundled jar's actual version.
 */
object ScrcpyServerLauncher {

    suspend fun ensurePushed(context: Context, connectionManager: AdbConnectionManager): Result<Unit> = withContext(Dispatchers.IO) {
        connectionManager.withDadb { dadb ->
            // AAPT compresses this asset by default (unrecognized extension),
            // so it can't be read via AssetManager.openFd (needs an
            // uncompressed/mmap-able entry) - copy it out via the regular
            // stream API first, which works either way, then compare that
            // copy's size against what's already on the TV to avoid
            // re-pushing a ~90KB jar on every mirror session.
            val tempFile = File.createTempFile("scrcpy-server", ".jar", context.cacheDir)
            try {
                context.assets.open("scrcpy-server").use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                val sizeCheck = dadb.shell("stat -c %s $SERVER_REMOTE_PATH 2>/dev/null || echo 0")
                val existingSize = sizeCheck.output.trim().toLongOrNull() ?: 0L
                if (existingSize == tempFile.length()) return@withDadb Unit

                dadb.push(tempFile, SERVER_REMOTE_PATH)
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Launches the server as a long-lived background shell process - the
     * returned AdbShellStream is intentionally never read to exit (the
     * process runs until stopServer() kills it or the TV reboots), so it's
     * opened directly via the raw Dadb instance rather than through
     * withDadb's one-shot command serialization.
     */
    fun launch(dadb: Dadb, scid: String, maxSize: Int = 1280): AdbShellStream {
        // tunnel_forward=true is required: without it the server CONNECTS OUT
        // to the abstract socket (the normal "adb reverse" mode, expecting a
        // client already listening on the other end of a reverse tunnel -
        // which dadb has no API to set up). tunnel_forward=true instead makes
        // the server itself LocalServerSocket.accept() on that socket name,
        // which is exactly what a plain dadb.open("localabstract:...") needs
        // on the other end - confirmed via DesktopConnection.java's open().
        val command = "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SCRCPY_VERSION scid=$scid tunnel_forward=true audio=false control=true " +
            "send_dummy_byte=true send_device_meta=true send_frame_meta=true send_codec_meta=true " +
            "video_codec=h264 max_size=$maxSize"
        Log.i(TAG, "launch: $command")
        return dadb.openShell(command)
    }

    /** Kills any running scrcpy-server process on the TV, independent of any currently-open mirror session. */
    suspend fun stopServer(connectionManager: AdbConnectionManager): Result<Unit> = withContext(Dispatchers.IO) {
        connectionManager.withDadb { dadb ->
            dadb.shell("pkill -f com.genymobile.scrcpy.Server")
            Unit
        }
    }
}
