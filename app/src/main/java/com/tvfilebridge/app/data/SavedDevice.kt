package com.tvfilebridge.app.data

import kotlinx.serialization.Serializable

@Serializable
data class SavedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5555,
    val lastConnectedAt: Long? = null,
    /** Captured from the TV's active network interface on connect - lets Wake-on-LAN target it while it's fully in standby and unreachable over ADB. */
    val macAddress: String? = null,
)
