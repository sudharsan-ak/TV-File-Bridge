package com.tvfilebridge.app.connection

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.connectionModeDataStore by preferencesDataStore(name = "connection_mode")

/**
 * User-controlled Online/Offline mode - persisted so it survives app
 * restarts (otherwise killing and reopening the app after going offline
 * would silently go back online, defeating the whole point: releasing the
 * TV's single ADB client slot for another tool, e.g. scrcpy, until the user
 * explicitly says to reconnect). Offline means AdbConnectionManager suppresses
 * every auto-connect/auto-reconnect path (cold start, foreground resume,
 * heartbeat, and the on-demand reconnect inside withDadb) - not just an
 * initial disconnect.
 */
class ConnectionModeStore(private val context: Context) {

    private val offlineKey = booleanPreferencesKey("is_offline")

    val isOffline: Flow<Boolean> = context.connectionModeDataStore.data.map { it[offlineKey] ?: false }

    suspend fun setOffline(offline: Boolean) {
        context.connectionModeDataStore.edit { prefs -> prefs[offlineKey] = offline }
    }
}
