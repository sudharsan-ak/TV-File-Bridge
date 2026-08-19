package com.tvfilebridge.app.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Source
import okio.Timeout
import okio.source

private const val TAG = "SideloadApkInstaller"

/**
 * Installs a user-picked APK (any local file, via SAF) onto the connected TV.
 * Two steps, same as what `adb install` itself does under the hood: push the
 * bytes to a TV-side temp path (real byte progress via CountingSource, same
 * trick TransferManager.runPush uses - dadb.push() has no progress callback
 * either, but the Source it reads from tracks bytes consumed), then run
 * `pm install -r` over that path so the UI can show real upload progress
 * instead of a bare "Installing…" spinner. dadb.install() was tried first but
 * offers no progress hook at all (File/Source + flags overloads only).
 */
class SideloadApkInstaller(
    private val context: Context,
    private val connectionManager: AdbConnectionManager,
) {
    sealed interface Progress {
        data class Uploading(val bytesSent: Long, val totalBytes: Long) : Progress
        data object Installing : Progress
    }

    suspend fun install(apkUri: Uri, onProgress: (Progress) -> Unit = {}): Result<Unit> {
        val fileName = "sideload_${System.currentTimeMillis()}.apk"
        val remotePath = "/data/local/tmp/$fileName"
        val totalBytes = fileSizeFor(apkUri)
        val counter = CountingSource(context, apkUri)

        var progressJob: kotlinx.coroutines.Job? = null
        val scope = CoroutineScope(Dispatchers.Default)
        progressJob = scope.launch {
            while (isActive) {
                onProgress(Progress.Uploading(counter.bytesRead, totalBytes))
                delay(150)
            }
        }

        val pushResult = connectionManager.withDadb { dadb ->
            counter.openIfNeeded()
            dadb.push(counter, remotePath, "664".toInt(8), System.currentTimeMillis() / 1000)
        }
        progressJob.cancel()
        onProgress(Progress.Uploading(totalBytes, totalBytes))

        if (pushResult.isFailure) {
            val e = pushResult.exceptionOrNull()
            Log.e(TAG, "push failed: ${e?.message}", e)
            return Result.failure(e ?: IllegalStateException("Upload failed"))
        }

        onProgress(Progress.Installing)
        val installResult = connectionManager.withDadb { dadb ->
            val response = dadb.shell("pm install -r ${shellQuote(remotePath)}")
            dadb.shell("rm -f ${shellQuote(remotePath)}")
            if (response.exitCode != 0 || response.output.contains("Failure")) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output }.ifBlank { "Install failed" })
            }
        }
        installResult.exceptionOrNull()?.let { Log.e(TAG, "install failed: ${it.message}", it) }
        return installResult
    }

    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun fileSizeFor(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) return cursor.getLong(sizeIndex)
        }
        return 0L
    }

    /** Same shape as TransferManager's private CountingSource - tracks bytes
     *  actually read so push progress can be polled without a callback. */
    private class CountingSource(private val context: Context, private val uri: Uri) : Source {
        var bytesRead: Long = 0
            private set

        private var delegate: Source? = null

        fun openIfNeeded() {
            if (delegate != null) return
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Couldn't open $uri")
            delegate = stream.source()
        }

        override fun read(sink: okio.Buffer, byteCount: Long): Long {
            openIfNeeded()
            val read = delegate!!.read(sink, byteCount)
            if (read > 0) bytesRead += read
            return read
        }

        override fun timeout(): Timeout = delegate?.timeout() ?: Timeout.NONE
        override fun close() { delegate?.close() }
    }
}
