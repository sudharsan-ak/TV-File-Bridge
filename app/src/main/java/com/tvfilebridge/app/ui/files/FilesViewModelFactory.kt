package com.tvfilebridge.app.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.tvfilebridge.app.AppContainer

class FilesViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return FilesViewModel(container.fileRepository, container.transferManager, container.connectionManager) as T
    }
}
