package com.tvfilebridge.a11ywatchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

private const val NOTIFICATION_CHANNEL_ID = "watchdog_running"
private const val NOTIFICATION_ID = 1

/**
 * Holds a runtime-registered BroadcastReceiver for ACTION_SCREEN_ON alive -
 * that broadcast can't be caught via a manifest-declared receiver on modern
 * Android (blocked since API 26), only by one registered while some
 * component is alive, so this foreground service exists purely to keep
 * that registration alive. The receiver itself does no polling and costs
 * no CPU between events - it's a dormant callback the OS invokes only when
 * the actual screen-on signal fires. The persistent notification is
 * Android's own requirement for any foreground service, not something this
 * app chose to add.
 */
class WakeWatcherService : Service() {

    private val screenOnReceiver = WakeReceiver()

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOnReceiver) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Accessibility Watchdog",
                NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Watching accessibility services")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WakeWatcherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
