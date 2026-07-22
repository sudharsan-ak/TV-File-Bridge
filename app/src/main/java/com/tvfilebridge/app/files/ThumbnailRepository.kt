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
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")

/**
 * Pulls and caches thumbnail-source images keyed by path+size+mtime, so a file
 * is never re-pulled once cached (spec §8). Concurrency is capped separately
 * from the single-command AdbConnectionManager lock - withDadb already
 * serializes actual transport use, this semaphore just avoids piling up
 * dozens of queued pulls at once when a long list scrolls into view.
 *
 * Videos need an extra step: the whole file is pulled to a scratch location
 * (no partial-range pull available over this transport), a frame is extracted
 * via MediaMetadataRetriever and saved as the cached thumbnail, then the
 * scratch video copy is deleted immediately - only the small extracted JPEG
 * counts against the cache's 200MB budget, not the source video.
 */
class ThumbnailRepository(context: Context, private val connectionManager: AdbConnectionManager) {

    private val cacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val scratchDir = File(context.cacheDir, "thumbnail_scratch").apply { mkdirs() }
    private val concurrency = Semaphore(2)

    suspend fun getThumbnail(entry: RemoteFile): File? {
        val cacheFile = cacheFileFor(entry)
        if (cacheFile.exists()) return cacheFile

        return concurrency.withPermit {
            if (cacheFile.exists()) return@withPermit cacheFile

            val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
            if (isVideo) pullVideoFrame(entry, cacheFile) else pullImage(entry, cacheFile)

            evictIfOverBudget()
            if (cacheFile.exists()) cacheFile else null
        }
    }

    private suspend fun pullImage(entry: RemoteFile, cacheFile: File) {
        val result = connectionManager.withDadb { dadb -> dadb.pull(cacheFile, entry.path) }
        result.exceptionOrNull()?.let {
            Log.e(TAG, "thumbnail pull failed for ${entry.path}: ${it.message}")
            cacheFile.delete()
        }
    }

    private suspend fun pullVideoFrame(entry: RemoteFile, cacheFile: File) {
        val scratchFile = File(scratchDir, "${cacheFile.name}.tmp")
        try {
            val result = connectionManager.withDadb { dadb -> dadb.pull(scratchFile, entry.path) }
            if (result.isFailure) {
                Log.e(TAG, "video thumbnail pull failed for ${entry.path}: ${result.exceptionOrNull()?.message}")
                return
            }
            extractVideoFrame(scratchFile, cacheFile)
        } catch (e: Exception) {
            Log.e(TAG, "video thumbnail extraction failed for ${entry.path}: ${e.message}", e)
            cacheFile.delete()
        } finally {
            scratchFile.delete()
        }
    }

    private fun cacheFileFor(entry: RemoteFile): File {
        val key = "${entry.path}:${entry.sizeBytes}:${entry.modifiedAt ?: 0}"
        val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        val ext = if (isVideo) "jpg" else entry.name.substringAfterLast('.', "jpg")
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
