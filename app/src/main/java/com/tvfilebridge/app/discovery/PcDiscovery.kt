package com.tvfilebridge.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class DiscoveredPc(val host: String)

private const val PC_CLIPBOARD_PORT = 58821
private const val PER_HOST_TIMEOUT_MS = 250

/**
 * Same algorithm as TvDiscovery, scanning for PC Companion's clipboard
 * server port (58821, see pc-companion/ClipboardServer.cs) instead of ADB's
 * 5555 - a plain TCP-connect probe across the phone's current /24 subnet,
 * all 254 hosts concurrently so the whole scan finishes in a couple of
 * seconds. Good enough signal that a PC Companion instance is listening
 * there; nothing about the actual clipboard protocol is exercised during
 * the scan itself.
 */
class PcDiscovery(private val context: Context) {

    suspend fun scan(): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val subnetPrefix = localSubnetPrefix() ?: return@withContext emptyList()

        (1..254).map { lastOctet ->
            async {
                val host = "$subnetPrefix.$lastOctet"
                if (isPortOpen(host, PC_CLIPBOARD_PORT)) host else null
            }
        }.awaitAll().filterNotNull().map { DiscoveredPc(it) }
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
