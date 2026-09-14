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
 * Owns every live ADB connection, keyed by SavedDevice id - a TV and a Fire
 * Stick (say) can both be connected and held open at once, each with its own
 * command lock and heartbeat, since dadb's one-socket-per-Dadb constraint
 * ("running two commands on the same Dadb concurrently corrupts the stream")
 * is per-connection, not global. Exactly one connection is "active" at a
 * time though: [state], [withDadb], [currentDadb] and [tcpForward] all
 * resolve to whichever session is active, so every existing caller (Files,
 * Transfers, Remote's key/tap commands, Cursor, Screen Mirroring) keeps
 * working unchanged against "the" connection - only Settings (to show every
 * connected device) and Remote's device switcher need the multi-connection
 * view via [connectionsState].
 *
 * Not tab-scoped - lives above the nav shell so any tab can read connection
 * state or issue commands through the same sessions.
 */
class AdbConnectionManager(context: Context, private val connectionModeStore: ConnectionModeStore) {

    private class Session(
        val host: String,
        val port: Int,
        val lock: Mutex = Mutex(),
        var dadb: Dadb? = null,
        var heartbeatJob: Job? = null,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards the sessions map and activeDeviceId themselves (add/remove/switch
    // active) - separate from each Session's own lock, which guards that one
    // session's command serialization. A command running on session A never
    // blocks connecting/switching to session B.
    private val sessionsLock = Mutex()
    private val sessions = mutableMapOf<String, Session>()
    private var activeDeviceId: String? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _connectionsState = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    /** Every currently-tracked connection's state, keyed by device id - for Settings and Remote's device switcher. */
    val connectionsState: StateFlow<Map<String, ConnectionState>> = _connectionsState.asStateFlow()

    // Cached in-memory mirror of ConnectionModeStore's persisted flag - the
    // hot paths below (heartbeat tick, withDadb on every command, lifecycle
    // resume) need a synchronous read, not a suspend/Flow collection each
    // time. Kept in sync via the onEach below, started at construction.
    @Volatile private var isOffline: Boolean = false

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
                scope.launch {
                    sessionsLock.withLock { sessions.keys.toList() }.forEach { id -> checkAndReconnect(id) }
                }
            }
        })
    }

    private suspend fun checkAndReconnect(deviceId: String) {
        val session = sessionsLock.withLock { sessions[deviceId] } ?: return
        val pingResult = withSession(session, deviceId) { it.shell("echo ping") }
        if (pingResult.isFailure) {
            Log.i(TAG, "checkAndReconnect: connection check failed, reconnecting to ${session.host}:${session.port}")
            session.lock.withLock { connectSessionLocked(deviceId, session) }
        }
    }

    /**
     * Manually releases the active connection and suppresses every
     * auto-connect path (cold start, foreground resume, heartbeat, withDadb's
     * on-demand reconnect) until [setOnline] is called - the deliberate "let
     * another ADB client, e.g. scrcpy, use this TV's single connection slot"
     * mode. Other devices' connections are left alone - this is scoped to
     * the active one, matching what a single-device app would call "the"
     * connection.
     */
    fun setOffline() {
        scope.launch {
            connectionModeStore.setOffline(true)
            val id = activeDeviceId ?: return@launch
            val session = sessionsLock.withLock { sessions[id] } ?: return@launch
            session.lock.withLock {
                stopHeartbeat(session)
                closeSession(session)
                updateState(id, ConnectionState.Disconnected)
                // The session is deliberately kept in the map (not removed)
                // so going back online knows which device to reconnect to,
                // matching disconnectDevice()'s existing behavior being a
                // distinct, harder reset.
            }
        }
    }

    /** Re-enables auto-connect and immediately attempts to reconnect to the active device, same as a cold app launch. */
    fun setOnline() {
        scope.launch {
            connectionModeStore.setOffline(false)
            val id = activeDeviceId ?: return@launch
            val session = sessionsLock.withLock { sessions[id] } ?: return@launch
            session.lock.withLock { connectSessionLocked(id, session) }
        }
    }

    /** True if the active connection is currently live. */
    val isConnected: Boolean
        get() = _state.value is ConnectionState.Connected

    /** Live view of the Online/Offline mode, for UI toggles - mirrors ConnectionModeStore. */
    val offlineMode: Flow<Boolean> = connectionModeStore.isOffline

    /** Connects to [deviceId] and makes it the active device - existing single-device call sites (Settings, cold-start auto-connect) use this. */
    fun connect(deviceId: String, host: String, port: Int = 5555) {
        scope.launch {
            connectSuspending(deviceId, host, port)
        }
    }

    /**
     * Connects to [deviceId] without disturbing any other already-connected
     * device, then makes it the active one (so [state]/[withDadb] immediately
     * reflect it, matching a plain single-device connect's behavior). This is
     * what actually allows a TV and a Fire Stick to both stay connected -
     * previously any new connect closed whatever was open first.
     */
    suspend fun connectSuspending(deviceId: String, host: String, port: Int = 5555): Boolean {
        val session = sessionsLock.withLock {
            sessions.getOrPut(deviceId) { Session(host, port) }
        }
        val success = session.lock.withLock { connectSessionLocked(deviceId, session) }
        if (success) setActive(deviceId)
        return success
    }

    private suspend fun connectSessionLocked(deviceId: String, session: Session, restartHeartbeat: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "connect: starting connect to ${session.host}:${session.port}")
            closeSession(session)
            updateState(deviceId, ConnectionState.Connecting(session.host))
            try {
                val connection = Dadb.create(session.host, session.port, keyPair)
                // Force a round-trip now so auth/refusal/timeout surface immediately
                // rather than lazily on the first real command later.
                connection.shell("echo connected")
                Log.i(TAG, "connect: succeeded")
                session.dadb = connection
                updateState(deviceId, ConnectionState.Connected(session.host, session.port))
                if (restartHeartbeat) startHeartbeat(deviceId, session)
                true
            } catch (e: Exception) {
                Log.e(TAG, "connect: failed with ${e.javaClass.simpleName}: ${e.message}", e)
                session.dadb = null
                updateState(deviceId, ConnectionState.Failed(session.host, classifyFailure(e)))
                false
            }
        }

    /** Makes [deviceId] the target for [state]/[withDadb]/[currentDadb]/[tcpForward] - instant, no reconnect, since it must already be a live or previously-tracked session. */
    fun setActive(deviceId: String) {
        activeDeviceId = deviceId
        _state.value = _connectionsState.value[deviceId] ?: ConnectionState.Disconnected
    }

    /** Disconnects the active device only - kept for existing single-device callers. */
    fun disconnect() {
        val id = activeDeviceId ?: return
        disconnectDevice(id)
    }

    /** Disconnects and forgets [deviceId]'s session, leaving every other connected device untouched. */
    fun disconnectDevice(deviceId: String) {
        scope.launch {
            val session = sessionsLock.withLock { sessions.remove(deviceId) } ?: return@launch
            session.lock.withLock {
                stopHeartbeat(session)
                closeSession(session)
            }
            _connectionsState.value = _connectionsState.value - deviceId
            if (activeDeviceId == deviceId) {
                activeDeviceId = null
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    private fun closeSession(session: Session) {
        session.dadb?.let { runCatching { it.close() } }
        session.dadb = null
    }

    private fun updateState(deviceId: String, newState: ConnectionState) {
        _connectionsState.value = _connectionsState.value + (deviceId to newState)
        if (activeDeviceId == deviceId) _state.value = newState
    }

    /**
     * Silently detects a dropped connection (TV sleep, Wi-Fi blip) and
     * reconnects without the user needing to revisit Settings. Pings on the
     * same per-session lock as everything else for that device, so it never
     * races a real command on that same connection; a failed ping reconnects
     * to the same host immediately. Runs for the lifetime of one connection -
     * started once per successful connect, not re-armed by its own reconnect
     * attempts. Independent per device, so one device's dropped Wi-Fi doesn't
     * affect another's heartbeat.
     */
    private fun startHeartbeat(deviceId: String, session: Session) {
        session.heartbeatJob?.cancel()
        session.heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isOffline && deviceId == activeDeviceId) continue
                val pingResult = withSession(session, deviceId) { it.shell("echo ping") }
                if (pingResult.isFailure && isActive) {
                    Log.i(TAG, "heartbeat: connection dropped, reconnecting to ${session.host}:${session.port}")
                    session.lock.withLock { connectSessionLocked(deviceId, session, restartHeartbeat = false) }
                }
            }
        }
    }

    private fun stopHeartbeat(session: Session) {
        session.heartbeatJob?.cancel()
        session.heartbeatJob = null
    }

    /**
     * Runs [block] against the active device's live Dadb instance, one
     * command at a time for that device. Reconnects first if no connection
     * is cached; if [block] throws a socket-level error, the cached
     * connection is discarded so the next call reconnects instead of reusing
     * a broken pipe. Every existing caller (Files, Transfers, Remote, Cursor,
     * installers) means "the active device" by "the connection", so this
     * keeps that meaning - it's [connectSuspending]/[setActive] that decide
     * which device is active, not this.
     */
    suspend fun <T> withDadb(block: suspend (Dadb) -> T): Result<T> {
        val id = activeDeviceId ?: return Result.failure(IllegalStateException("Not connected"))
        val session = sessionsLock.withLock { sessions[id] } ?: return Result.failure(IllegalStateException("Not connected"))
        if (isOffline) return Result.failure(IllegalStateException("Offline"))
        return withSession(session, id, block).also { result ->
            result.exceptionOrNull()?.let { e ->
                if (e is SocketException) updateState(id, ConnectionState.Failed(session.host, FailureReason.UNKNOWN))
            }
        }
    }

    /**
     * Same as [withDadb] but against a caller-supplied session directly -
     * used internally by the heartbeat/foreground-resume reconnect checks,
     * which already have the Session in hand and shouldn't go through the
     * activeDeviceId indirection (a background device's heartbeat must still
     * run even while a different device is active). Reconnects on demand if
     * the cached Dadb is gone, same as the single-connection version did.
     */
    private suspend fun <T> withSession(session: Session, deviceIdForReconnect: String, block: suspend (Dadb) -> T): Result<T> = session.lock.withLock {
        val connection = session.dadb ?: run {
            if (isOffline) return@withLock Result.failure(IllegalStateException("Offline"))
            if (!connectSessionLocked(deviceIdForReconnect, session)) {
                return@withLock Result.failure(IllegalStateException("Not connected"))
            }
            session.dadb
        } ?: return@withLock Result.failure(IllegalStateException("Not connected"))

        try {
            Result.success(withContext(Dispatchers.IO) { block(connection) })
        } catch (e: SocketException) {
            Log.e(TAG, "withSession: socket died (${e.message}), discarding connection", e)
            closeSession(session)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sets up a local TCP forward to [remotePort] on the active device, for
     * features that need a persistent socket (e.g. the cursor companion app)
     * rather than a one-shot `shell`/`pull`/`push` command. Unlike
     * [withDadb], the returned forward is not torn down after one call - the
     * caller owns its lifetime and must call `close()` on the result. Per
     * dadb's implementation this spins up its own background threads, so it
     * does not contend with [withDadb]'s command serialization.
     *
     * Returns null if the active device isn't currently connected. The
     * forward does NOT survive a reconnect (heartbeat-triggered or manual) -
     * callers should watch [state] and re-forward after a reconnect.
     */
    suspend fun tcpForward(localPort: Int, remotePort: Int): AutoCloseable? {
        val connection = currentDadb() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { connection.tcpForward(localPort, remotePort) }.getOrNull()
        }
    }

    /**
     * Exposes the raw Dadb instance for the active device, for callers that
     * need a persistent stream outside withDadb's one-command-at-a-time
     * serialization (screen mirroring's video/control sockets and the
     * scrcpy-server shell process, which all stay open for the session's
     * whole lifetime) - same exception to the lock that [tcpForward] already
     * relies on. Null if the active device isn't connected.
     */
    fun currentDadb(): Dadb? {
        val id = activeDeviceId ?: return null
        return sessions[id]?.dadb
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
