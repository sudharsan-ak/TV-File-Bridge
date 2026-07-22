package com.tvfilebridge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tvfilebridge.app.clipboard.ClipboardBridge
import com.tvfilebridge.app.clipboard.ClipboardReceiverServer
import com.tvfilebridge.app.clipboard.ClipboardSendLog
import com.tvfilebridge.app.clipboard.PcDeviceStore
import com.tvfilebridge.app.clipboard.PcFileTransferManager
import com.tvfilebridge.app.clipboard.ReceivedFileTransferManager
import com.tvfilebridge.app.clipboard.ReceivedFilesFolderStore
import com.tvfilebridge.app.connection.AdbConnectionManager
import com.tvfilebridge.app.connection.ConnectionModeStore
import com.tvfilebridge.app.cursor.CursorBridge
import com.tvfilebridge.app.cursor.TV_COMPANION_ACCESSIBILITY_SERVICE
import com.tvfilebridge.app.cursor.TvCompanionInstaller
import com.tvfilebridge.app.data.DeviceStore
import com.tvfilebridge.app.discovery.TvDiscovery
import com.tvfilebridge.app.files.FileRepository
import com.tvfilebridge.app.files.ThumbnailRepository
import com.tvfilebridge.app.remote.FabPositionStore
import com.tvfilebridge.app.remote.RemoteControlRepository
import com.tvfilebridge.app.sync.SyncManager
import com.tvfilebridge.app.sync.SyncPairStore
import com.tvfilebridge.app.transfers.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Minimal manual dependency holder - no DI framework needed for this app's size.
 * One instance per process, created from Application so it outlives any single
 * Activity/tab and matches AdbConnectionManager's app-lifetime connection.
 */
class AppContainer(private val appContext: Context) {
    val connectionModeStore = ConnectionModeStore(appContext)
    val connectionManager = AdbConnectionManager(appContext, connectionModeStore)
    val deviceStore = DeviceStore(appContext)
    val tvDiscovery = TvDiscovery(appContext)
    val fileRepository = FileRepository(connectionManager)
    val transferManager = TransferManager(appContext, connectionManager)
    val thumbnailRepository = ThumbnailRepository(appContext, connectionManager)
    val syncPairStore = SyncPairStore(appContext)
    val syncManager = SyncManager(appContext, fileRepository, transferManager, syncPairStore)
    val remoteControlRepository = RemoteControlRepository(connectionManager)
    val tvCompanionInstaller = TvCompanionInstaller(appContext, connectionManager)
    val cursorBridge = CursorBridge(connectionManager, remoteControlRepository)
    val pcDeviceStore = PcDeviceStore(appContext)
    val clipboardBridge = ClipboardBridge(appContext)
    val clipboardSendLog = ClipboardSendLog()
    val receivedFilesFolderStore = ReceivedFilesFolderStore(appContext)
    val receivedFileTransferManager = ReceivedFileTransferManager()
    val clipboardReceiverServer = ClipboardReceiverServer(appContext, receivedFilesFolderStore, clipboardSendLog, receivedFileTransferManager)
    val pcFileTransferManager = PcFileTransferManager(appContext, clipboardSendLog)
    val fabPositionStore = FabPositionStore(appContext)
    val sonyAuthStore = com.tvfilebridge.app.remote.SonyAuthStore(appContext)
    val sonyIrccWaker = com.tvfilebridge.app.remote.SonyIrccWaker(sonyAuthStore)
    val tvScreenshotSaver = com.tvfilebridge.app.remote.TvScreenshotSaver(appContext, remoteControlRepository, receivedFilesFolderStore)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        autoConnectToLastActiveDevice()
        // Started here, not just from ConnectionForegroundService, so PC ->
        // phone clipboard pushes work whenever the app process is alive at
        // all (opened, even if not currently in the foreground) - not only
        // when the user has separately turned the "TV Connection" tile on.
        // This path has no foreground-service guarantee behind it though:
        // Android can still suspend a plain background process, so it's
        // best-effort, not reliable, unlike the tile-backed path.
        clipboardReceiverServer.start()
    }

    /** Keeps a SAF folder-tree grant valid across app restarts (sync folders). */
    fun contentResolverPersistPermission(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private fun autoConnectToLastActiveDevice() {
        appScope.launch {
            if (connectionModeStore.isOffline.first()) return@launch
            val activeId = deviceStore.activeDeviceId.first() ?: return@launch
            val device = deviceStore.devices.first().find { it.id == activeId } ?: return@launch
            // Silent: a failed attempt just leaves connection state as Failed,
            // surfaced ambiently rather than blocking app startup (spec §5.1).
            val success = connectionManager.connectSuspending(device.host, device.port)
            if (success) {
                deviceStore.markConnected(device.id)
                if (device.macAddress == null) {
                    remoteControlRepository.fetchMacAddress()
                        .onSuccess { mac -> if (mac != null) deviceStore.updateMacAddress(device.id, mac) }
                }
                ensureCursorAccessibilityIfInstalled()
            }
        }
    }

    /**
     * Auto-heals the companion's accessibility service if it's installed
     * but got turned off (e.g. after a TV reboot or update, which can clear
     * enabled_accessibility_services) - purely additive, only writes if the
     * service is genuinely missing from the list, never touches anything
     * that's already correctly enabled (see ensureCursorAccessibilityEnabled's
     * doc: idempotent, read-only when nothing needs fixing).
     */
    fun ensureCursorAccessibilityIfInstalled() {
        appScope.launch {
            if (tvCompanionInstaller.isInstalled()) {
                remoteControlRepository.ensureCursorAccessibilityEnabled(TV_COMPANION_ACCESSIBILITY_SERVICE)
            }
        }
    }
}
