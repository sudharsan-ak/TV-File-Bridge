package com.tvfilebridge.app.clipboard

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ClipboardSendStatus { SUCCESS, FAILED }
enum class ClipboardEntryDirection { SENT, RECEIVED }
enum class ClipboardContentKind { TEXT, IMAGE, FILE }

data class ClipboardSendEntry(
    val id: String = UUID.randomUUID().toString(),
    val direction: ClipboardEntryDirection = ClipboardEntryDirection.SENT,
    val kind: ClipboardContentKind,
    val textPreview: String? = null,
    val imageUri: Uri? = null,
    val fileName: String? = null,
    val fileUri: Uri? = null,
    val targetDeviceName: String,
    val status: ClipboardSendStatus,
    val failureReason: String? = null,
    val sentAt: Long = System.currentTimeMillis(),
)

/**
 * App-lifetime local log of this phone's clipboard activity in BOTH
 * directions - pushes it made to a PC (SENT) and pushes it received from a
 * PC (RECEIVED) - so the Clipboard History tab reads as one combined record,
 * matching the PC companion's own History tab, rather than only ever showing
 * what this phone sent.
 */
class ClipboardSendLog {
    private val _entries = MutableStateFlow<List<ClipboardSendEntry>>(emptyList())
    val entries: StateFlow<List<ClipboardSendEntry>> = _entries.asStateFlow()

    fun record(entry: ClipboardSendEntry) {
        _entries.value = listOf(entry) + _entries.value
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
