package com.tvfilebridge.app.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.syncDataStore by preferencesDataStore(name = "sync_pairs")

class SyncPairStore(private val context: Context) {

    private val pairsKey = stringPreferencesKey("sync_pairs_json")
    private val json = Json { ignoreUnknownKeys = true }

    val pairs: Flow<List<SyncPair>> = context.syncDataStore.data.map { prefs ->
        val raw = prefs[pairsKey] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<SyncPair>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addPair(
        label: String,
        phoneTreeUri: String,
        phoneFolderName: String,
        tvPath: String,
        direction: SyncDirection,
    ): SyncPair {
        val newPair = SyncPair(
            id = UUID.randomUUID().toString(),
            label = label,
            phoneTreeUri = phoneTreeUri,
            phoneFolderName = phoneFolderName,
            tvPath = tvPath,
            direction = direction,
        )
        save(pairs.first() + newPair)
        return newPair
    }

    suspend fun removePair(id: String) {
        save(pairs.first().filterNot { it.id == id })
    }

    suspend fun updatePair(
        id: String,
        label: String,
        phoneTreeUri: String,
        phoneFolderName: String,
        tvPath: String,
        direction: SyncDirection,
    ) {
        save(
            pairs.first().map {
                if (it.id == id) {
                    it.copy(
                        label = label,
                        phoneTreeUri = phoneTreeUri,
                        phoneFolderName = phoneFolderName,
                        tvPath = tvPath,
                        direction = direction,
                    )
                } else it
            }
        )
    }

    suspend fun updateDirection(id: String, direction: SyncDirection) {
        save(pairs.first().map { if (it.id == id) it.copy(direction = direction) else it })
    }

    suspend fun markSynced(id: String) {
        save(pairs.first().map { if (it.id == id) it.copy(lastSyncedAt = System.currentTimeMillis()) else it })
    }

    private suspend fun save(list: List<SyncPair>) {
        context.syncDataStore.edit { prefs -> prefs[pairsKey] = json.encodeToString(list) }
    }
}
