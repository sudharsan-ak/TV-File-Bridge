package com.tvfilebridge.a11ywatchdog

import android.content.Context

private const val PREFS_NAME = "watched_services"
private const val KEY_SERVICES = "services"

/**
 * Plain SharedPreferences, not DataStore - this app has exactly one small
 * setting (a set of strings) and no async/Flow consumers, so pulling in
 * DataStore's coroutine machinery would add dependency weight for no real
 * benefit on an app meant to stay as lightweight as possible.
 */
class WatchedServicesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWatchedServices(): Set<String> = prefs.getStringSet(KEY_SERVICES, emptySet()) ?: emptySet()

    fun setWatchedServices(services: Set<String>) {
        prefs.edit().putStringSet(KEY_SERVICES, services).apply()
    }
}
