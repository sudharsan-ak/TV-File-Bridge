package com.tvfilebridge.app.ui.clipboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast

/** Hands off to whatever app is registered for this file's type - Android's normal "Open with" behavior. */
fun openFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the received-files folder in whatever file manager the user picks
 * (Android's normal disambiguation if more than one is installed) - there's
 * no cross-app way to highlight the exact file the way Explorer's /select
 * does on Windows, so this lands you in the right folder, not on the exact
 * file. [folderTreeUri] is the SAF tree URI from ReceivedFilesFolderStore, if
 * one was chosen; when null (still on the Downloads fallback), this opens
 * the Downloads collection generally instead, since there's no folder tree
 * URI to open in that case.
 */
fun showFileLocation(context: Context, folderTreeUri: Uri?) {
    val intent = if (folderTreeUri != null) {
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderTreeUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/downloads")
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No file manager app found", Toast.LENGTH_SHORT).show()
    }
}
