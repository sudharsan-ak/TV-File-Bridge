package com.tvfilebridge.app.clipboard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.receivedFilesFolderDataStore by preferencesDataStore(name = "received_files_folder")

/**
 * Where files pushed from the PC (Explorer Ctrl+C -> auto-send) get saved on
 * this phone - a single SAF tree URI, same picker pattern as the TV sync
 * folder (#5.2.1). No default fallback stored here; ClipboardReceiverServer
 * falls back to MediaStore Downloads when this is unset, same spirit as the
 * PC side's own "Downloads\TV File Bridge" fallback.
 */
class ReceivedFilesFolderStore(private val context: Context) {

    private val folderUriKey = stringPreferencesKey("folder_uri")
    private val folderNameKey = stringPreferencesKey("folder_name")

    val folderUri: Flow<String?> = context.receivedFilesFolderDataStore.data.map { it[folderUriKey] }
    val folderName: Flow<String?> = context.receivedFilesFolderDataStore.data.map { it[folderNameKey] }

    suspend fun setFolder(uri: String, name: String) {
        context.receivedFilesFolderDataStore.edit { prefs ->
            prefs[folderUriKey] = uri
            prefs[folderNameKey] = name
        }
    }

    suspend fun clear() {
        context.receivedFilesFolderDataStore.edit { prefs ->
            prefs.remove(folderUriKey)
            prefs.remove(folderNameKey)
        }
    }
}
