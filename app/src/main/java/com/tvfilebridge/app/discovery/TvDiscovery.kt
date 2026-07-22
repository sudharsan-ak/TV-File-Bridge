package com.tvfilebridge.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class DiscoveredDevice(val host: String)

private const val ADB_PORT = 5555
private const val PER_HOST_TIMEOUT_MS = 250

/**
 * Scans the phone's current Wi-Fi /24 subnet for hosts with port 5555 open.
 * A plain TCP-connect probe, not a full ADB handshake - good enough signal that
 * something ADB-like is listening; the real auth/connect happens when the user
 * taps Connect. Runs all 254 hosts concurrently so the whole scan finishes in a
 * couple of seconds rather than minutes.
 */
class TvDiscovery(private val context: Context) {

    suspend fun scan(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val subnetPrefix = localSubnetPrefix() ?: return@withContext emptyList()

        (1..254).map { lastOctet ->
            async {
                val host = "$subnetPrefix.$lastOctet"
                if (isPortOpen(host, ADB_PORT)) host else null
            }
        }.awaitAll().filterNotNull().map { DiscoveredDevice(it) }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PER_HOST_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun localSubnetPrefix(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ipInt == 0) return null
        val bytes = intArrayOf(
            ipInt and 0xFF,
            ipInt shr 8 and 0xFF,
            ipInt shr 16 and 0xFF,
            ipInt shr 24 and 0xFF,
        )
        return "${bytes[0]}.${bytes[1]}.${bytes[2]}"
    }
}
