package com.tvfilebridge.app.transfers

enum class TransferDirection { PULL, PUSH }

enum class TransferStatus { IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED }

data class Transfer(
    val id: String,
    val direction: TransferDirection,
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val startedAt: Long,
    val status: TransferStatus,
    val progressBytes: Long = 0L,
    val errorMessage: String? = null,
)
