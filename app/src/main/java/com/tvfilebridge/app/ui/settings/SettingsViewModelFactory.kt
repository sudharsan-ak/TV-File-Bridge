package com.tvfilebridge.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.tvfilebridge.app.AppContainer

class SettingsViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(container.connectionManager, container.deviceStore, container.tvDiscovery, container.remoteControlRepository, container.tvCompanionInstaller) as T
    }
}
