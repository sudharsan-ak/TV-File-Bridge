package com.tvfilebridge.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.wakeScheduleDataStore by preferencesDataStore(name = "wake_schedules")

/** Same shape as [DeviceStore] - a JSON-encoded list under one DataStore key. */
class WakeScheduleStore(private val context: Context) {

    private val schedulesKey = stringPreferencesKey("wake_schedules_json")
    private val json = Json { ignoreUnknownKeys = true }

    val schedules: Flow<List<WakeSchedule>> = context.wakeScheduleDataStore.data.map { prefs ->
        val raw = prefs[schedulesKey] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<WakeSchedule>>(raw) }.getOrDefault(emptyList())
    }

    /** Creates one WakeSchedule per selected day, all sharing a fresh groupId - the UI treats them as a single multi-day schedule. */
    suspend fun addGroup(daysOfWeek: Set<Int>, hour: Int, minute: Int): List<WakeSchedule> {
        val groupId = UUID.randomUUID().toString()
        val newSchedules = daysOfWeek.map { day ->
            WakeSchedule(id = UUID.randomUUID().toString(), groupId = groupId, dayOfWeek = day, hour = hour, minute = minute)
        }
        saveSchedules(schedules.first() + newSchedules)
        return newSchedules
    }

    /**
     * Replaces every row in [groupId] with a fresh set for [daysOfWeek] -
     * simplest way to reconcile "used to be Mon/Wed, now just Wed" without
     * diffing day-by-day. Reuses one row's id per surviving day where
     * possible isn't necessary since WakeAlarmScheduler re-keys its
     * AlarmManager entries by id anyway; the caller cancels the old ids
     * before calling this.
     */
    suspend fun replaceGroup(groupId: String, daysOfWeek: Set<Int>, hour: Int, minute: Int): List<WakeSchedule> {
        val newSchedules = daysOfWeek.map { day ->
            WakeSchedule(id = UUID.randomUUID().toString(), groupId = groupId, dayOfWeek = day, hour = hour, minute = minute)
        }
        val withoutOldGroup = schedules.first().filterNot { it.groupId == groupId }
        saveSchedules(withoutOldGroup + newSchedules)
        return newSchedules
    }

    suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
        saveSchedules(schedules.first().map { if (it.groupId == groupId) it.copy(enabled = enabled) else it })
    }

    suspend fun updateSchedule(schedule: WakeSchedule) {
        saveSchedules(schedules.first().map { if (it.id == schedule.id) schedule else it })
    }

    suspend fun deleteSchedule(id: String) {
        saveSchedules(schedules.first().filterNot { it.id == id })
    }

    suspend fun deleteGroup(groupId: String) {
        saveSchedules(schedules.first().filterNot { it.groupId == groupId })
    }

    private suspend fun saveSchedules(list: List<WakeSchedule>) {
        context.wakeScheduleDataStore.edit { prefs ->
            prefs[schedulesKey] = json.encodeToString(list)
        }
    }
}
