package com.tvfilebridge.app.files

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long?,
)

data class StorageInfo(
    val total: String,
    val used: String,
    val available: String,
    val usedPercent: String,
)
