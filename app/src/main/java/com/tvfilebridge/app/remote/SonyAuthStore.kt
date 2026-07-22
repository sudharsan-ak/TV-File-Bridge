package com.tvfilebridge.app.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sonyAuthDataStore by preferencesDataStore(name = "sony_ircc_auth")

/** Persists the auth cookie Sony's IRCC-IP registration handshake returns, keyed by TV host, so pairing only needs to happen once per TV. */
class SonyAuthStore(private val context: Context) {

    private fun cookieKey(host: String) = stringPreferencesKey("cookie_$host")

    fun cookie(host: String): Flow<String?> = context.sonyAuthDataStore.data.map { it[cookieKey(host)] }

    suspend fun setCookie(host: String, cookie: String) {
        context.sonyAuthDataStore.edit { prefs -> prefs[cookieKey(host)] = cookie }
    }

    suspend fun clearCookie(host: String) {
        context.sonyAuthDataStore.edit { prefs -> prefs.remove(cookieKey(host)) }
    }
}
