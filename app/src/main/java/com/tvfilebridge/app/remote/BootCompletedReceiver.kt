package com.tvfilebridge.app.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvfilebridge.app.TvFileBridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** AlarmManager alarms are cleared on reboot - re-arm every enabled wake schedule once the device comes back up. */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as TvFileBridgeApp).container
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schedules = container.wakeScheduleStore.schedules.first()
                container.wakeAlarmScheduler.rescheduleAll(schedules)
                container.sonyAuthRefreshScheduler.ensureScheduled()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
