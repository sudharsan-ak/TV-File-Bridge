package com.tvfilebridge.app.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.TvFileBridgeApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Read-only lookup of the current primary PC device, for the separate
 * clipboard-bridge app to query - a second package can't read this app's
 * DataStore directly (private per-app storage), and duplicating the device
 * list in the bridge app would mean configuring the target PC twice. This
 * keeps PcDeviceStore as the single source of truth; the bridge app only
 * ever reads through here.
 */
class PrimaryPcDeviceProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.tvfilebridge.app.primarypc"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/primary")

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_HOST = "host"
        const val COLUMN_PORT = "port"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val container = (context?.applicationContext as TvFileBridgeApp).container
        val primary = runBlocking { container.pcDeviceStore.devices.first().find { it.isPrimary } }

        val cursor = MatrixCursor(arrayOf(COLUMN_ID, COLUMN_NAME, COLUMN_HOST, COLUMN_PORT))
        if (primary != null) {
            cursor.addRow(arrayOf(primary.id, primary.name, primary.host, primary.port))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
