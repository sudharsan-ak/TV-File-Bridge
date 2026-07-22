package com.tvfilebridge.app.files

import android.content.Context
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

private const val TAG = "ThumbnailRepository"
private const val MAX_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB bounded LRU

/**
 * Pulls and caches thumbnail-source images keyed by path+size+mtime, so a file
 * is never re-pulled once cached (spec §8). Concurrency is capped separately
 * from the single-command AdbConnectionManager lock - withDadb already
 * serializes actual transport use, this semaphore just avoids piling up
 * dozens of queued pulls at once when a long list scrolls into view.
 */
class ThumbnailRepository(context: Context, private val connectionManager: AdbConnectionManager) {

    private val cacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val concurrency = Semaphore(2)

    suspend fun getThumbnail(entry: RemoteFile): File? {
        val cacheFile = cacheFileFor(entry)
        if (cacheFile.exists()) return cacheFile

        return concurrency.withPermit {
            if (cacheFile.exists()) return@withPermit cacheFile

            val result = connectionManager.withDadb { dadb -> dadb.pull(cacheFile, entry.path) }
            result.exceptionOrNull()?.let {
                Log.e(TAG, "thumbnail pull failed for ${entry.path}: ${it.message}")
                cacheFile.delete()
            }
            evictIfOverBudget()
            if (cacheFile.exists()) cacheFile else null
        }
    }

    private fun cacheFileFor(entry: RemoteFile): File {
        val key = "${entry.path}:${entry.sizeBytes}:${entry.modifiedAt ?: 0}"
        val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = entry.name.substringAfterLast('.', "jpg")
        return File(cacheDir, "$hash.$ext")
    }

    private fun evictIfOverBudget() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_BYTES) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (totalSize <= MAX_CACHE_BYTES) return
            totalSize -= file.length()
            file.delete()
        }
    }
}
