package com.tvfilebridge.app.clipboard

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

private val Context.pcDeviceDataStore by preferencesDataStore(name = "pc_devices")

/**
 * Saved PC companion targets for "Copy to PC" - separate from DeviceStore's
 * TV list since this is a completely different protocol/connection (a plain
 * TCP push to the PC companion app, not ADB), not just another device type
 * on the same connection.
 */
class PcDeviceStore(private val context: Context) {

    private val devicesKey = stringPreferencesKey("saved_pc_devices_json")
    private val json = Json { ignoreUnknownKeys = true }

    val devices: Flow<List<PcDevice>> = context.pcDeviceDataStore.data.map { prefs ->
        val raw = prefs[devicesKey] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<PcDevice>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addDevice(name: String, host: String, port: Int): PcDevice {
        val newDevice = PcDevice(id = UUID.randomUUID().toString(), name = name, host = host, port = port)
        saveDevices(devices.first() + newDevice)
        return newDevice
    }

    suspend fun deleteDevice(id: String) {
        saveDevices(devices.first().filterNot { it.id == id })
    }

    suspend fun renameDevice(id: String, newName: String) {
        saveDevices(devices.first().map { if (it.id == id) it.copy(name = newName) else it })
    }

    /** Only one device can be primary at a time - setting one clears the flag on all others. */
    suspend fun setPrimary(id: String) {
        saveDevices(devices.first().map { it.copy(isPrimary = it.id == id) })
    }

    suspend fun clearPrimary(id: String) {
        saveDevices(devices.first().map { if (it.id == id) it.copy(isPrimary = false) else it })
    }

    private suspend fun saveDevices(list: List<PcDevice>) {
        context.pcDeviceDataStore.edit { prefs -> prefs[devicesKey] = json.encodeToString(list) }
    }
}
