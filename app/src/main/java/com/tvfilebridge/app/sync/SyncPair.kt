package com.tvfilebridge.app.sync

import kotlinx.serialization.Serializable

enum class SyncDirection { TV_TO_PHONE, PHONE_TO_TV, TWO_WAY }

@Serializable
data class SyncPair(
    val id: String,
    val label: String,
    val phoneTreeUri: String,
    val phoneFolderName: String,
    val tvPath: String,
    val direction: SyncDirection,
    val lastSyncedAt: Long? = null,
)
