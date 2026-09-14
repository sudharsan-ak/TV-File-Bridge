package com.tvfilebridge.app.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tvfilebridge.app.TvFileBridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "WakeAlarmReceiver"

/**
 * Fires on a scheduled wake time. Calls the same IRCC-IP wake the drawer's
 * "Wake TV" button uses (SonyIrccWaker.wake, a plain HTTP POST using the
 * already-stored pairing cookie) - no ADB connection needed, so this works
 * even if the app process was fully backgrounded/killed and the TV is in
 * standby.
 *
 * Recurring weekly - re-arms this same row for its next occurrence
 * afterward, since setExactAndAllowWhileIdle is one-shot.
 */
class WakeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val container = (context.applicationContext as TvFileBridgeApp).container
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val activeId = container.deviceStore.activeDeviceId.first()
                val device = activeId?.let { id -> container.deviceStore.devices.first().find { it.id == id } }
                if (device != null) {
                    container.sonyIrccWaker.wake(device.host)
                        .onFailure { Log.e(TAG, "scheduled wake failed: ${it.message}", it) }
                } else {
                    Log.w(TAG, "scheduled wake skipped - no active TV configured")
                }

                val schedule = container.wakeScheduleStore.schedules.first().find { it.id == scheduleId }
                if (schedule != null) {
                    container.wakeAlarmScheduler.schedule(schedule)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
