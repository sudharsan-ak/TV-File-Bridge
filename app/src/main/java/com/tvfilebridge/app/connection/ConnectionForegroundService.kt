package com.tvfilebridge.app.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tvfilebridge.app.MainActivity
import com.tvfilebridge.app.R
import com.tvfilebridge.app.TvFileBridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "connection_hold"

/**
 * User-controlled foreground service: exists purely to give the app process
 * the OS's "do not suspend a foreground service" guarantee while the user
 * has explicitly chosen to hold the TV connection open in the background
 * (via the Quick Settings tile). It doesn't own the TV connection itself -
 * AdbConnectionManager already does, including its own heartbeat/reconnect -
 * this only keeps the process alive long enough for that logic to keep
 * running instead of being suspended.
 *
 * ClipboardReceiverServer (PC -> phone clipboard pushes) is started by
 * AppContainer itself, not here - it needs to run whenever the app process
 * is alive at all, not only while this service is (this service is strictly
 * a subset of "process alive": it only runs when the user has separately
 * opted into the Quick Settings tile). This service's notification text
 * still reflects that the listener is active, since in practice it's
 * running for as long as this service is.
 */
class ConnectionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as TvFileBridgeApp).container
        val connectionManager = container.connectionManager
        startForeground(NOTIFICATION_ID, buildNotification(connectionManager.state.value))

        stateJob?.cancel()
        stateJob = connectionManager.state.onEach { state ->
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        }.launchIn(scope)

        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: ConnectionState): Notification {
        val connectionText = when (state) {
            is ConnectionState.Connected -> "Connected to ${state.host}"
            is ConnectionState.Connecting -> "Connecting…"
            is ConnectionState.AwaitingAuthorization -> "Waiting for TV authorization"
            is ConnectionState.Failed -> "Connection failed, will keep retrying"
            is ConnectionState.Disconnected -> "Not connected"
        }
        val statusText = "$connectionText · Clipboard listening"

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV File Bridge")
            .setContentText(statusText)
            // Deliberately a built-in system icon, not our own vector
            // drawable: on this device, inflating that vector through any of
            // the resource-ID-based Drawable/Icon paths throws a platform
            // bug ("viewportWidth > 0" on a resource confirmed correct in
            // the compiled APK) - a notification icon failure here would
            // crash the foreground service itself, worse than a generic icon.
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TV connection kept alive",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
