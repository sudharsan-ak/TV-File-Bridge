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

private const val TAG = "ScreenshotSaveHelper"

/**
 * Shared save-into-configured-folder logic for both TV and PC screenshots -
 * same destination (ReceivedFilesFolderStore's SAF folder, falling back to
 * MediaStore Downloads) regardless of source, so there's one place to look
 * for anything captured from either side rather than two separate paths
 * that happen to do the same thing slightly differently.
 */
class ScreenshotSaveHelper(
    private val context: Context,
    private val receivedFilesFolderStore: ReceivedFilesFolderStore,
) {

    suspend fun saveFromTempFile(tempFile: File, fileName: String): Result<Uri> {
        return try {
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
            Log.e(TAG, "saveFromTempFile failed: ${e.message}", e)
            Result.failure(e)
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
