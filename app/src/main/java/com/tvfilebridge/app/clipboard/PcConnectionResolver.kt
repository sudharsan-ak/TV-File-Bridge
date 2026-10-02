package com.tvfilebridge.app.clipboard

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
private data class WhoamiHeader(val type: String = "whoami", val deviceName: String)

private const val WHOAMI_TIMEOUT_MS = 800
// encodeDefaults = true is required here - kotlinx.serialization's default
// Json{} silently OMITS any field equal to its declared default value, and
// `type` always equals its default ("whoami") since nothing ever overrides
// it. Without this, the PC never actually received a "type" field at all,
// PushHeader.Type defaulted to "" on the C# side, and the request fell
// through to the normal (already-paired) push path instead of the whoami
// handler - which is exactly why this returned "ok" instead of the PC's
// name, with no error anywhere to signal the mismatch.
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Timeout for the data-transfer connection itself, as opposed to the short WHOAMI_TIMEOUT_MS used only for rediscovery probes - generous since a real push/pull/browse connection is expected to actually transfer data afterward, not just check reachability. */
private const val CONNECT_TIMEOUT_MS = 5000

/**
 * Socket(host, port)'s single-arg constructor connects immediately using
 * the platform's own default TCP connect timeout (tens of seconds) rather
 * than anything this app controls. Against a genuinely unreachable stale
 * IP - exactly the case PcConnectionResolver exists to recover from - a
 * caller that still used the single-arg constructor for its real
 * data-transfer connection (after resolve() already gave up and returned
 * the device unchanged) could still hang far longer than any UI timeout,
 * or on some networks fail fast with a misleading-looking stack trace
 * that made it look like resolution itself was broken when the actual bug
 * was this blocking constructor further down the call chain. Every PC
 * client (ClipboardBridge, PcFileRepository, PcFileTransferManager,
 * PcScreenshotRequester) uses this instead of `Socket(host, port)` directly.
 */
fun connectSocket(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    return socket
}

/**
 * Shared by every phone-side PC client (ClipboardBridge, PcFileRepository,
 * PcFileTransferManager, PcScreenshotRequester) - a saved PcDevice's IP can
 * go stale when DHCP reassigns the PC's address, and without this the only
 * recovery was Forget + re-scan + re-add by hand. resolve() tries the saved
 * host first (the fast path that works almost always), and only on failure
 * scans the LAN (PcDiscovery, same technique as TV discovery) and asks each
 * hit "whoami" - ClipboardServer answers that before pairing/approval, since
 * it only reveals this PC's configured name and a background rediscovery
 * probe shouldn't trigger the pairing popup. A name match updates
 * PcDeviceStore with the corrected host before returning, so every future
 * call (not just this one) uses the fixed IP - not just whichever call
 * happened to trigger the rediscovery.
 */
object PcConnectionResolver {

    suspend fun resolve(device: PcDevice, discovery: com.tvfilebridge.app.discovery.PcDiscovery, store: PcDeviceStore): PcDevice =
        withContext(Dispatchers.IO) {
            if (whoami(device.host, device.port) != null) return@withContext device

            // Match against the PC's own stable identity (captured once at
            // add time, immune to the user later renaming this device's
            // display name on the phone) - falls back to [name] only for
            // devices saved before identityName existed.
            val targetIdentity = device.identityName ?: device.name
            val candidates = discovery.scan()
            for (candidate in candidates) {
                if (whoami(candidate.host, device.port) == targetIdentity) {
                    store.updateHost(device.id, candidate.host)
                    return@withContext device.copy(host = candidate.host)
                }
            }
            device
        }

    /**
     * Public so the Add PC flow can capture a device's identityName at save
     * time - the one thing that needs to happen exactly once, before a
     * user-editable display name has a chance to diverge from the PC's own
     * actual configured name.
     *
     * Socket(host, port)'s single-arg constructor connects immediately using
     * the platform's own default TCP connect timeout - tens of seconds, way
     * past WHOAMI_TIMEOUT_MS - and soTimeout only governs read timeouts
     * *after* a connection is already established, not the connect step
     * itself. Against a genuinely unreachable stale IP (the exact case this
     * whole rediscovery exists for), that meant whoami() could silently hang
     * for a long time before ever failing, so rediscovery effectively never
     * ran in any timeframe a user would wait around for. Socket() + an
     * explicit connect(..., timeoutMs) actually bounds the connect attempt
     * itself.
     */
    fun whoami(host: String, port: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), WHOAMI_TIMEOUT_MS)
            socket.soTimeout = WHOAMI_TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            val headerJson = json.encodeToString(WhoamiHeader(deviceName = Build.MODEL ?: "Android phone"))
            val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array()
            output.write(lengthBytes)
            output.write(headerBytes)
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val responseLengthBytes = ByteArray(4)
            input.readFully(responseLengthBytes)
            val length = ByteBuffer.wrap(responseLengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (length <= 0 || length > 1_000_000) return@runCatching null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()
}
