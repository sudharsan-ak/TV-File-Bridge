package com.tvfilebridge.app.data

import kotlinx.serialization.Serializable

/**
 * One recurring "wake the TV" entry - a day of week plus a time of day,
 * fires every week (like Samsung Clock's alarm weekday chips). A UI
 * "schedule" covering multiple days (e.g. Mon/Wed/Fri) is several of these
 * sharing the same [groupId], [hour] and [minute] - lets the edit dialog
 * show/change them together while WakeAlarmScheduler still arms one
 * AlarmManager alarm per day. [dayOfWeek] uses java.time.DayOfWeek's
 * 1(Mon)-7(Sun) numbering so it sorts and compares naturally without a
 * custom enum.
 */
@Serializable
data class WakeSchedule(
    val id: String,
    val groupId: String,
    val dayOfWeek: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
)
