package com.tvfilebridge.app.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tvfilebridge.app.data.WakeSchedule
import java.util.Calendar

private const val TAG = "WakeAlarmScheduler"

const val EXTRA_SCHEDULE_ID = "schedule_id"

/**
 * Arms/disarms one exact AlarmManager alarm per [WakeSchedule] row (one row
 * per day of week in a multi-day schedule), keyed by the schedule's own id
 * so re-arming (edit, or after firing) naturally replaces rather than
 * duplicates a pending alarm - PendingIntent identity is (requestCode,
 * action, data...), and request code here is the schedule id's hashCode.
 *
 * Recurring weekly, like Samsung Clock's alarm weekday chips: after firing,
 * WakeAlarmReceiver re-arms the same row for its next occurrence a week out.
 *
 * setExactAndAllowWhileIdle is the only variant that still fires close to on
 * time under Doze; plain setExact gets deferred to the next maintenance
 * window, which defeats a "wake the TV at 7am" schedule.
 */
class WakeAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    fun schedule(entry: WakeSchedule) {
        if (!entry.enabled) {
            cancel(entry.id)
            return
        }
        val triggerAt = nextTriggerTimeMillis(entry.dayOfWeek, entry.hour, entry.minute)
        val pendingIntent = pendingIntentFor(entry.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.i(TAG, "scheduled ${entry.id} for $triggerAt (day=${entry.dayOfWeek} ${entry.hour}:${entry.minute})")
        } catch (e: SecurityException) {
            Log.e(TAG, "schedule(${entry.id}) failed - exact alarm permission missing: ${e.message}")
        }
    }

    fun cancel(scheduleId: String) {
        alarmManager.cancel(pendingIntentFor(scheduleId))
    }

    /** Re-arms every enabled entry - called after edits and from BootCompletedReceiver, since AlarmManager alarms don't survive a reboot on their own. */
    fun rescheduleAll(entries: List<WakeSchedule>) {
        entries.forEach { schedule(it) }
    }

    private fun pendingIntentFor(scheduleId: String): PendingIntent {
        val intent = Intent(context, WakeAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Next occurrence of [dayOfWeek] (java.time numbering, 1=Mon..7=Sun) at [hour]:[minute] - today if that time hasn't passed yet, otherwise the next matching weekday. */
    private fun nextTriggerTimeMillis(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetCalendarDow = isoDayOfWeekToCalendarDayOfWeek(dayOfWeek)
        while (calendar.get(Calendar.DAY_OF_WEEK) != targetCalendarDow || calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return calendar.timeInMillis
    }

    /** java.time DayOfWeek (1=Mon..7=Sun) to java.util.Calendar's (1=Sun..7=Sat). */
    private fun isoDayOfWeekToCalendarDayOfWeek(isoDayOfWeek: Int): Int =
        if (isoDayOfWeek == 7) Calendar.SUNDAY else isoDayOfWeek + 1
}
