package com.tvfilebridge.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.sync.SyncDirection
import com.tvfilebridge.app.sync.SyncManager
import com.tvfilebridge.app.sync.SyncPair
import com.tvfilebridge.app.sync.SyncPairStore
import com.tvfilebridge.app.sync.SyncRunState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncFoldersUiState(
    val pairs: List<SyncPair> = emptyList(),
    val runState: SyncRunState = SyncRunState(),
)

class SyncFoldersViewModel(
    private val pairStore: SyncPairStore,
    private val syncManager: SyncManager,
) : ViewModel() {

    val uiState: StateFlow<SyncFoldersUiState> = combine(
        pairStore.pairs,
        syncManager.runState,
    ) { pairs, runState -> SyncFoldersUiState(pairs, runState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncFoldersUiState())

    fun addPair(label: String, phoneTreeUri: String, phoneFolderName: String, tvPath: String, direction: SyncDirection) {
        viewModelScope.launch {
            pairStore.addPair(label, phoneTreeUri, phoneFolderName, tvPath, direction)
        }
    }

    fun removePair(id: String) {
        viewModelScope.launch { pairStore.removePair(id) }
    }

    fun updatePair(id: String, label: String, phoneTreeUri: String, phoneFolderName: String, tvPath: String, direction: SyncDirection) {
        viewModelScope.launch {
            pairStore.updatePair(id, label, phoneTreeUri, phoneFolderName, tvPath, direction)
        }
    }

    fun setDirection(id: String, direction: SyncDirection) {
        viewModelScope.launch { pairStore.updateDirection(id, direction) }
    }

    fun syncNow() {
        syncManager.syncAll(uiState.value.pairs)
    }
}

class SyncFoldersViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return SyncFoldersViewModel(container.syncPairStore, container.syncManager) as T
    }
}
