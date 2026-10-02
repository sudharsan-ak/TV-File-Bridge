package com.tvfilebridge.app.clipboard

import kotlinx.serialization.Serializable

@Serializable
data class PcDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 58821,
    val isPrimary: Boolean = false,
    // The PC's own whoami-reported DeviceName, captured once when the device
    // is first added/paired and never touched afterward - separate from
    // [name] (which the user can freely rename in the Devices list) because
    // rediscovery needs something that stays true to the actual PC
    // regardless of whatever label the user has given it here. Nullable
    // only for devices added before this field existed; PcConnectionResolver
    // falls back to [name] for those, matching the old (renaming-sensitive)
    // behavior until the device is re-added or this gets backfilled.
    val identityName: String? = null,
)
