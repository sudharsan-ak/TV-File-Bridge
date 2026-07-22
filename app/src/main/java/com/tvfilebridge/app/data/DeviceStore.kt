package com.tvfilebridge.app.data

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

private val Context.dataStore by preferencesDataStore(name = "tv_devices")

class DeviceStore(private val context: Context) {

    private val devicesKey = stringPreferencesKey("saved_devices_json")
    private val activeIdKey = stringPreferencesKey("active_device_id")
    private val json = Json { ignoreUnknownKeys = true }

    val devices: Flow<List<SavedDevice>> = context.dataStore.data.map { prefs ->
        val raw = prefs[devicesKey] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<SavedDevice>>(raw) }.getOrDefault(emptyList())
    }

    val activeDeviceId: Flow<String?> = context.dataStore.data.map { it[activeIdKey] }

    suspend fun addDevice(name: String, host: String, port: Int): SavedDevice {
        val newDevice = SavedDevice(id = UUID.randomUUID().toString(), name = name, host = host, port = port)
        val current = devices.first()
        saveDevices(current + newDevice)
        return newDevice
    }

    suspend fun updateDevice(device: SavedDevice) {
        val current = devices.first()
        saveDevices(current.map { if (it.id == device.id) device else it })
    }

    suspend fun deleteDevice(id: String) {
        val current = devices.first()
        saveDevices(current.filterNot { it.id == id })
        if (activeDeviceId.first() == id) {
            setActiveDevice(null)
        }
    }

    suspend fun markConnected(id: String) {
        val current = devices.first()
        saveDevices(current.map { if (it.id == id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it })
        setActiveDevice(id)
    }

    /** Called once a MAC is read from the connected TV so Wake-on-LAN has a target even while the TV is fully asleep and unreachable over ADB. */
    suspend fun updateMacAddress(id: String, macAddress: String) {
        val current = devices.first()
        saveDevices(current.map { if (it.id == id) it.copy(macAddress = macAddress) else it })
    }

    suspend fun setActiveDevice(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(activeIdKey) else prefs[activeIdKey] = id
        }
    }

    private suspend fun saveDevices(list: List<SavedDevice>) {
        context.dataStore.edit { prefs ->
            prefs[devicesKey] = json.encodeToString(list)
        }
    }
}
