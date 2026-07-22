package com.tvfilebridge.app.clipboard

import kotlinx.serialization.Serializable

@Serializable
data class PcDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 58821,
    val isPrimary: Boolean = false,
)
