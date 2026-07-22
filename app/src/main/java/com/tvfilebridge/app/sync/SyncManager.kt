package com.tvfilebridge.app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tvfilebridge.app.files.FileRepository
import com.tvfilebridge.app.transfers.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SyncManager"

/** One file present on either side, with enough info to diff. */
private data class SideFile(val name: String, val sizeBytes: Long, val modifiedAt: Long?)

enum class SyncRunStatus { IDLE, RUNNING, DONE }

data class SyncRunState(
    val status: SyncRunStatus = SyncRunStatus.IDLE,
    val currentPairLabel: String? = null,
    val pairsCompleted: Int = 0,
    val pairsTotal: Int = 0,
)

class SyncManager(
    private val context: Context,
    private val fileRepository: FileRepository,
    private val transferManager: TransferManager,
    private val pairStore: SyncPairStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runState = MutableStateFlow(SyncRunState())
    val runState: StateFlow<SyncRunState> = _runState.asStateFlow()

    fun syncAll(pairs: List<SyncPair>) {
        if (_runState.value.status == SyncRunStatus.RUNNING) return
        scope.launch {
            _runState.value = SyncRunState(status = SyncRunStatus.RUNNING, pairsTotal = pairs.size)
            pairs.forEachIndexed { index, pair ->
                _runState.update { it.copy(currentPairLabel = pair.label, pairsCompleted = index) }
                runCatching { syncPair(pair) }
                    .onFailure { Log.e(TAG, "sync failed for ${pair.label}: ${it.message}", it) }
                pairStore.markSynced(pair.id)
            }
            _runState.value = SyncRunState(status = SyncRunStatus.DONE, pairsCompleted = pairs.size, pairsTotal = pairs.size)
        }
    }

    private suspend fun syncPair(pair: SyncPair) {
        val tvFiles = fileRepository.list(pair.tvPath).getOrElse {
            Log.e(TAG, "couldn't list TV folder ${pair.tvPath}: ${it.message}")
            return
        }.filterNot { it.isDirectory }.associateBy { it.name }

        val phoneTree = DocumentFile.fromTreeUri(context, Uri.parse(pair.phoneTreeUri))
        val phoneFiles = phoneTree?.listFiles()
            ?.filter { it.isFile }
            ?.associateBy { it.name ?: "" }
            ?: emptyMap()

        if (pair.direction == SyncDirection.TV_TO_PHONE || pair.direction == SyncDirection.TWO_WAY) {
            tvFiles.values.forEach { tvFile ->
                val phoneFile = phoneFiles[tvFile.name]
                val needsPull = phoneFile == null || phoneFile.length() != tvFile.sizeBytes
                if (needsPull) {
                    val destination = phoneTree?.createFile(guessMimeType(tvFile.name), tvFile.name)
                        ?: return@forEach
                    transferManager.pullFileToUri(tvFile.path, tvFile.name, tvFile.sizeBytes, destination.uri)
                }
            }
        }

        if (pair.direction == SyncDirection.PHONE_TO_TV || pair.direction == SyncDirection.TWO_WAY) {
            phoneFiles.values.forEach { phoneFile ->
                val name = phoneFile.name ?: return@forEach
                val tvFile = tvFiles[name]
                val needsPush = tvFile == null || tvFile.sizeBytes != phoneFile.length()
                if (needsPush) {
                    transferManager.pushFile(phoneFile.uri, pair.tvPath, name, phoneFile.length())
                }
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
