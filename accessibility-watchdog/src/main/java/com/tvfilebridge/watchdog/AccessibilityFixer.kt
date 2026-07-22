package com.tvfilebridge.a11ywatchdog

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log

private const val TAG = "AccessibilityFixer"

/**
 * Re-enables whichever watched accessibility services (picked by the user
 * in MainActivity, stored in WatchedServicesStore) are missing from
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. Purely additive - reads
 * the current list, appends only what's missing, writes back - so it never
 * disturbs services this app isn't watching (other apps' accessibility
 * features stay exactly as the user left them) and is a safe no-op when
 * everything's already enabled.
 *
 * Requires WRITE_SECURE_SETTINGS, which a sideloaded app can't obtain
 * through normal Settings taps - granted once via
 * `adb shell pm grant com.tvfilebridge.a11ywatchdog android.permission.WRITE_SECURE_SETTINGS`
 * right after install. Without that grant, the write silently fails
 * (SecurityException, caught and logged) - this app has no way to prompt
 * for it itself.
 */
object AccessibilityFixer {

    fun fixIfNeeded(context: Context): Boolean {
        val watched = WatchedServicesStore(context).getWatchedServices()
        if (watched.isEmpty()) return false

        val resolver = context.contentResolver
        val current = readEnabledServices(resolver)
        val missing = watched.filter { it !in current }
        if (missing.isEmpty()) {
            Log.i(TAG, "All ${watched.size} watched service(s) already enabled")
            return false
        }

        val updated = (current + missing).joinToString(":")
        return try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Re-enabled ${missing.size} missing service(s): $missing")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS - grant it via adb shell pm grant $PACKAGE_NAME android.permission.WRITE_SECURE_SETTINGS", e)
            false
        }
    }

    private fun readEnabledServices(resolver: ContentResolver): List<String> {
        val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return raw.split(":").map { it.trim() }.filter { it.isNotBlank() }
    }
}

const val PACKAGE_NAME = "com.tvfilebridge.a11ywatchdog"
