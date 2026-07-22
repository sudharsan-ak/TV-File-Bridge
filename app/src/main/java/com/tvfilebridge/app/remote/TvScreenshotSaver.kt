package com.tvfilebridge.app.remote

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.tvfilebridge.app.clipboard.ReceivedFilesFolderStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TvScreenshotSaver"

/**
 * Captures the TV's current screen and saves it into the same folder the
 * user already picked for PC->phone file transfers (ReceivedFilesFolderStore)
 * - one place to look for anything that lands on the phone from the TV
 * side, rather than a separate screenshot-only location. Falls back to
 * MediaStore Downloads if no folder is configured, same fallback the PC
 * file-receive path already uses.
 */
class TvScreenshotSaver(
    private val context: Context,
    private val remoteControlRepository: RemoteControlRepository,
    private val receivedFilesFolderStore: ReceivedFilesFolderStore,
) {

    suspend fun captureAndSave(): Result<Uri> {
        val tempFile = File(context.cacheDir, "tv_screenshot_${System.currentTimeMillis()}.png")
        val captureResult = remoteControlRepository.screenshot(tempFile)
        if (captureResult.isFailure) {
            // The DRM-blocked-black-image case still writes real bytes to
            // tempFile before failing its post-hoc pixel check - clean that
            // up here too, not just on the success path below.
            tempFile.delete()
            return Result.failure(captureResult.exceptionOrNull()!!)
        }

        return try {
            val fileName = "TV Screenshot ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())}.png"
            val folderUriString = receivedFilesFolderStore.folderUri.first()
            val outputUri = (if (folderUriString != null) {
                createDocumentInTree(Uri.parse(folderUriString), fileName)
            } else {
                createMediaStoreDownloadsEntry(fileName)
            }) ?: return Result.failure(IllegalStateException("Couldn't create the destination file"))

            context.contentResolver.openOutputStream(outputUri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: return Result.failure(IllegalStateException("Couldn't open the destination file for writing"))

            Result.success(outputUri)
        } catch (e: Exception) {
            Log.e(TAG, "captureAndSave failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    private fun createDocumentInTree(treeUri: Uri, fileName: String): Uri? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.createDocument(context.contentResolver, parentUri, "image/png", fileName)
        } catch (e: Exception) {
            Log.e(TAG, "createDocumentInTree failed, falling back to Downloads: ${e.message}")
            createMediaStoreDownloadsEntry(fileName)
        }
    }

    private fun createMediaStoreDownloadsEntry(fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val clearPending = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, clearPending, null, null)
        return uri
    }
}
