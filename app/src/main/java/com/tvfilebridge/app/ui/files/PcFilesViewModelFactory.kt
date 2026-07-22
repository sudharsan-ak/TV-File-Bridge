package com.tvfilebridge.app.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.tvfilebridge.app.AppContainer

class PcFilesViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return PcFilesViewModel(container.appContext, container.pcFileRepository, container.pcDeviceStore) as T
    }
}
