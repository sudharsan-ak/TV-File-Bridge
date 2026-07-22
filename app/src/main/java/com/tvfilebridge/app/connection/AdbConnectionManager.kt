package com.tvfilebridge.app.connection

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "AdbConnectionManager"
private const val HEARTBEAT_INTERVAL_MS = 20_000L

/**
 * Owns the single live ADB connection to whichever TV is active. Not tab-scoped -
 * lives above the nav shell so any tab (Files, Transfers, future Remote) can read
 * connection state or issue commands through the same session.
 *
 * dadb's `Dadb` multiplexes shell/pull/push over one TCP socket; running two
 * commands on it concurrently from different coroutines corrupts the stream
 * ("Broken pipe" on every command afterward). All command execution goes
 * through [withDadb], which holds a mutex for the duration of one command and
 * treats any socket-level failure as connection death - clearing the cached
 * instance and reconnecting on the next call, rather than reusing a dead pipe.
 */
class AdbConnectionManager(context: Context, private val connectionModeStore: ConnectionModeStore) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandLock = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Cached in-memory mirror of ConnectionModeStore's persisted flag - the
    // hot paths below (heartbeat tick, withDadb on every command, lifecycle
    // resume) need a synchronous read, not a suspend/Flow collection each
    // time. Kept in sync via the onEach below, started at construction.
    @Volatile private var isOffline: Boolean = false

    private var dadb: Dadb? = null
    private var currentHost: String? = null
    private var currentPort: Int? = null
    private var heartbeatJob: Job? = null

    private val keyPair: AdbKeyPair by lazy { loadOrGenerateKeyPair() }

    init {
        connectionModeStore.isOffline.onEach { isOffline = it }.launchIn(scope)

        // The heartbeat only pings every HEARTBEAT_INTERVAL_MS, so a drop
        // right after a successful ping could otherwise go unnoticed for
        // nearly that whole interval before the next scheduled check even
        // looks - most of what read as a "slow reconnect" when returning to
        // the app. Checking immediately on foreground resume, rather than
        // waiting for the next tick, closes that gap.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (isOffline) return
                val host = currentHost ?: return
                val port = currentPort ?: return
                scope.launch {
                    val pingResult = withDadb { it.shell("echo ping") }
                    if (pingResult.isFailure) {
                        Log.i(TAG, "onStart: connection check failed, reconnecting to $host:$port")
                        commandLock.withLock { connectLocked(host, port) }
                    }
                }
            }
        })
    }

    /**
     * Manually releases the connection and suppresses every auto-connect
     * path (cold start, foreground resume, heartbeat, withDadb's on-demand
     * reconnect) until [setOnline] is called - the deliberate "let another
     * ADB client, e.g. scrcpy, use the TV's single connection slot" mode.
     */
    fun setOffline() {
        scope.launch {
            connectionModeStore.setOffline(true)
            commandLock.withLock {
                stopHeartbeat()
                closeCurrent()
                _state.value = ConnectionState.Disconnected
                // currentHost/currentPort deliberately kept (not nulled) so
                // going back online knows which TV to reconnect to, matching
                // disconnect()'s existing behavior being a distinct, harder reset.
            }
        }
    }

    /** Re-enables auto-connect and immediately attempts to reconnect to the last-active TV, same as a cold app launch. */
    fun setOnline() {
        scope.launch {
            connectionModeStore.setOffline(false)
            val host = currentHost
            val port = currentPort ?: 5555
            if (host != null) {
                commandLock.withLock { connectLocked(host, port) }
            }
        }
    }

    /** True if a live, usable connection is currently held. */
    val isConnected: Boolean
        get() = _state.value is ConnectionState.Connected

    /** Live view of the Online/Offline mode, for UI toggles - mirrors ConnectionModeStore. */
    val offlineMode: Flow<Boolean> = connectionModeStore.isOffline

    fun connect(host: String, port: Int = 5555) {
        scope.launch {
            connectSuspending(host, port)
        }
    }

    suspend fun connectSuspending(host: String, port: Int = 5555): Boolean = commandLock.withLock {
        connectLocked(host, port)
    }

    private suspend fun connectLocked(host: String, port: Int, restartHeartbeat: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect: starting connect to $host:$port")
        closeCurrent()
        _state.value = ConnectionState.Connecting(host)
        try {
            val connection = Dadb.create(host, port, keyPair)
            // Force a round-trip now so auth/refusal/timeout surface immediately
            // rather than lazily on the first real command later.
            connection.shell("echo connected")
            Log.i(TAG, "connect: succeeded")
            dadb = connection
            currentHost = host
            currentPort = port
            _state.value = ConnectionState.Connected(host, port)
            if (restartHeartbeat) startHeartbeat()
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect: failed with ${e.javaClass.simpleName}: ${e.message}", e)
            dadb = null
            _state.value = ConnectionState.Failed(host, classifyFailure(e))
            false
        }
    }

    fun disconnect() {
        scope.launch {
            commandLock.withLock {
                stopHeartbeat()
                closeCurrent()
                currentHost = null
                currentPort = null
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    private fun closeCurrent() {
        dadb?.let { runCatching { it.close() } }
        dadb = null
    }

    /**
     * Silently detects a dropped connection (TV sleep, Wi-Fi blip - spec §8)
     * and reconnects without the user needing to revisit Settings. Pings on
     * the same command lock as everything else, so it never races a real
     * command; a failed ping reconnects to the same host immediately. Runs
     * for the lifetime of one connection - started once per successful
     * connect, not re-armed by its own reconnect attempts.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isOffline) continue
                val host = currentHost ?: continue
                val port = currentPort ?: 5555
                val pingResult = withDadb { it.shell("echo ping") }
                if (pingResult.isFailure && isActive && !isOffline) {
                    Log.i(TAG, "heartbeat: connection dropped, reconnecting to $host:$port")
                    commandLock.withLock { connectLocked(host, port, restartHeartbeat = false) }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Runs [block] against a live Dadb instance, one command at a time across
     * the whole app. Reconnects first if no connection is cached; if [block]
     * throws a socket-level error, the cached connection is discarded so the
     * next call reconnects instead of reusing a broken pipe.
     */
    suspend fun <T> withDadb(block: suspend (Dadb) -> T): Result<T> = commandLock.withLock {
        val connection = dadb ?: run {
            if (isOffline) {
                return@withLock Result.failure(IllegalStateException("Offline"))
            }
            val host = currentHost
            if (host == null) {
                return@withLock Result.failure(IllegalStateException("Not connected"))
            }
            if (!connectLocked(host, currentPort ?: 5555)) {
                return@withLock Result.failure(IllegalStateException("Not connected"))
            }
            dadb
        } ?: return@withLock Result.failure(IllegalStateException("Not connected"))

        try {
            Result.success(withContext(Dispatchers.IO) { block(connection) })
        } catch (e: SocketException) {
            Log.e(TAG, "withDadb: socket died (${e.message}), discarding connection", e)
            closeCurrent()
            _state.value = currentHost?.let { ConnectionState.Failed(it, FailureReason.UNKNOWN) }
                ?: ConnectionState.Disconnected
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sets up a local TCP forward to [remotePort] on the active TV, for
     * features that need a persistent socket (e.g. the cursor companion
     * app) rather than a one-shot `shell`/`pull`/`push` command. Unlike
     * [withDadb], the returned forward is not torn down after one call -
     * the caller owns its lifetime and must call `close()` on the result.
     * Per dadb's implementation this spins up its own background threads,
     * so it does not contend with [withDadb]'s command serialization.
     *
     * Returns null if not currently connected. The forward does NOT survive
     * a reconnect (heartbeat-triggered or manual) - callers should watch
     * [state] and re-forward after a reconnect.
     */
    suspend fun tcpForward(localPort: Int, remotePort: Int): AutoCloseable? {
        val connection = dadb ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { connection.tcpForward(localPort, remotePort) }.getOrNull()
        }
    }

    private fun classifyFailure(e: Exception): FailureReason = when {
        e is UnknownHostException -> FailureReason.UNKNOWN_HOST
        e is ConnectException && e.message?.contains("refused", ignoreCase = true) == true ->
            FailureReason.CONNECTION_REFUSED
        e is SocketTimeoutException -> FailureReason.TIMEOUT
        e.message?.contains("bind", ignoreCase = true) == true ||
            e.message?.contains("already", ignoreCase = true) == true -> FailureReason.ALREADY_IN_USE
        e.message?.contains("auth", ignoreCase = true) == true ||
            e.message?.contains("device unauthorized", ignoreCase = true) == true -> FailureReason.AUTH_REJECTED
        e is IOException -> FailureReason.UNKNOWN
        else -> FailureReason.UNKNOWN
    }

    private fun loadOrGenerateKeyPair(): AdbKeyPair {
        val dir = File(appContext.filesDir, "adb_keys").apply { mkdirs() }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            Log.i(TAG, "loadOrGenerateKeyPair: no key found at ${dir.absolutePath}, generating new keypair")
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
