package com.tvfilebridge.app.clipboard

import android.content.Context
import android.util.Log
import com.tvfilebridge.app.files.extractVideoFrame
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

private const val TAG = "PcThumbnailRepository"
private const val MAX_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB bounded LRU
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")

/**
 * PC counterpart to ThumbnailRepository (TV browsing) - pulls and caches
 * thumbnail-source images keyed by path+size, same never-pull-twice contract,
 * but goes through PcFileRepository's per-call socket instead of a persistent
 * ADB session, and needs an explicit target PcDevice per call (there's no
 * app-global "currently connected" state to read it from). Videos are pulled
 * whole to a scratch file, a frame extracted via extractVideoFrame, then the
 * scratch copy deleted - same approach as ThumbnailRepository since neither
 * transport supports a partial/range pull.
 */
class PcThumbnailRepository(context: Context, private val repository: PcFileRepository) {

    private val cacheDir = File(context.cacheDir, "pc_thumbnails").apply { mkdirs() }
    private val scratchDir = File(context.cacheDir, "pc_thumbnail_scratch").apply { mkdirs() }
    private val concurrency = Semaphore(2)

    suspend fun getThumbnail(device: PcDevice, entry: PcFile): File? {
        val cacheFile = cacheFileFor(entry)
        if (cacheFile.exists()) return cacheFile

        return concurrency.withPermit {
            if (cacheFile.exists()) return@withPermit cacheFile

            val isVideo = entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
            if (isVideo) pullVideoFrame(device, entry, cacheFile) else pullImage(device, entry, cacheFile)

            evictIfOverBudget()
            if (cacheFile.exists()) cacheFile else null
        }
    }

    private suspend fun pullImage(device: PcDevice, entry: PcFile, cacheFile: File) {
        val result = repository.pullToCache(device, entry.path, cacheFile)
        result.exceptionOrNull()?.let {
            Log.e(TAG, "thumbnail pull failed for ${entry.path}: ${it.message}")
            cacheFile.delete()
        }
    }

    private suspend fun pullVideoFrame(device: PcDevice, entry: PcFile, cacheFile: File) {
        val scratchFile = File(scratchDir, "${cacheFile.name}.tmp")
        try {
            val result = repository.pullToCache(device, entry.path, scratchFile)
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

    private fun cacheFileFor(entry: PcFile): File {
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
