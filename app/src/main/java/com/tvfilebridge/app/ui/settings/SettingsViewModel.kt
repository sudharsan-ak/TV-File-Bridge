package com.tvfilebridge.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.connection.FailureReason
import com.tvfilebridge.app.cursor.TV_COMPANION_ACCESSIBILITY_SERVICE
import com.tvfilebridge.app.cursor.TvCompanionInstaller
import com.tvfilebridge.app.data.DeviceStore
import com.tvfilebridge.app.data.SavedDevice
import com.tvfilebridge.app.discovery.DiscoveredDevice
import com.tvfilebridge.app.discovery.TvDiscovery
import com.tvfilebridge.app.remote.RemoteControlRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val devices: List<SavedDevice> = emptyList(),
    val activeDeviceId: String? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
)

data class DiscoveryUiState(
    val isScanning: Boolean = false,
    val results: List<DiscoveredDevice> = emptyList(),
    val hasScanned: Boolean = false,
)

class SettingsViewModel(
    private val connectionManager: AdbConnectionManager,
    private val deviceStore: DeviceStore,
    private val tvDiscovery: TvDiscovery,
    private val remoteControlRepository: RemoteControlRepository,
    private val tvCompanionInstaller: TvCompanionInstaller,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        deviceStore.devices,
        deviceStore.activeDeviceId,
        connectionManager.state,
    ) { devices, activeId, connectionState ->
        SettingsUiState(devices, activeId, connectionState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _discoveryState = MutableStateFlow(DiscoveryUiState())
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    fun scanForDevices() {
        viewModelScope.launch {
            _discoveryState.value = DiscoveryUiState(isScanning = true)
            val results = tvDiscovery.scan()
            _discoveryState.value = DiscoveryUiState(isScanning = false, results = results, hasScanned = true)
        }
    }

    fun clearDiscovery() {
        _discoveryState.value = DiscoveryUiState()
    }

    fun addDevice(name: String, host: String, port: Int) {
        viewModelScope.launch {
            deviceStore.addDevice(name, host, port)
        }
    }

    fun deleteDevice(id: String) {
        viewModelScope.launch {
            if (uiState.value.activeDeviceId == id) {
                connectionManager.disconnect()
            }
            deviceStore.deleteDevice(id)
        }
    }

    fun connectTo(device: SavedDevice) {
        viewModelScope.launch {
            val success = connectionManager.connectSuspending(device.host, device.port)
            if (success) {
                deviceStore.markConnected(device.id)
                if (device.macAddress == null) {
                    remoteControlRepository.fetchMacAddress()
                        .onSuccess { mac -> if (mac != null) deviceStore.updateMacAddress(device.id, mac) }
                        .onFailure { Log.w("SettingsViewModel", "Could not read TV MAC for Wake-on-LAN: ${it.message}") }
                }
                if (tvCompanionInstaller.isInstalled()) {
                    remoteControlRepository.ensureCursorAccessibilityEnabled(TV_COMPANION_ACCESSIBILITY_SERVICE)
                }
            }
        }
    }

    fun disconnect() {
        connectionManager.disconnect()
        viewModelScope.launch { deviceStore.setActiveDevice(null) }
    }
}

fun hintForFailure(reason: FailureReason): String = when (reason) {
    FailureReason.CONNECTION_REFUSED ->
        "Connection refused. On the TV: Settings → System → About → Status shows the IP; " +
            "if ADB won't connect, toggle Developer options → Network debugging (or USB debugging) off/on."
    FailureReason.TIMEOUT ->
        "Connection timed out. Check the TV is on and on the same Wi-Fi network, and that the IP is correct."
    FailureReason.AUTH_REJECTED ->
        "The TV rejected this device's key. Check the TV screen for an \"Allow USB debugging?\" prompt " +
            "and tap Always allow, or re-check Developer options → USB debugging."
    FailureReason.UNKNOWN_HOST ->
        "Couldn't resolve that address. Double-check the IP."
    FailureReason.ALREADY_IN_USE ->
        "Only one ADB client can connect at a time. If your laptop is connected to this TV, disconnect it there first."
    FailureReason.UNKNOWN ->
        "Couldn't connect. Check the TV is on, on the same Wi-Fi, and the IP/port are correct."
}
