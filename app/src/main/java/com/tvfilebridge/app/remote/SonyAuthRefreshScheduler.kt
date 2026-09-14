package com.tvfilebridge.app.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

private const val REFRESH_REQUEST_CODE = 9001
private const val REFRESH_INTERVAL_MILLIS = 3L * 24 * 60 * 60 * 1000 // 3 days

/**
 * Sony's IRCC-IP session cookie (from SonyIrccWaker's registration handshake)
 * expires after 14 days server-side (confirmed via Sony's own Max-Age
 * header) - after that, both the "Wake TV" button and any scheduled wake
 * start failing with HTTP 403 until the user re-pairs by hand.
 *
 * Re-registering with no PIN (SonyIrccWaker.startRegistration) silently
 * issues a fresh cookie with no user interaction, as long as the TV still
 * trusts this client at the network level - only a genuinely revoked
 * pairing falls back to needing a PIN. Refreshing every 3 days (well inside
 * the 14-day window, so a few missed attempts - TV off, phone off - still
 * leave margin) keeps the cookie from ever expiring under normal use.
 *
 * Inexact repeating, not setExactAndAllowWhileIdle: this has no user-visible
 * deadline the way a wake schedule does, so there's no reason to spend the
 * stricter (and permission-gated) exact-alarm budget on it.
 */
class SonyAuthRefreshScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureScheduled() {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            Intent(context, SonyAuthRefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + REFRESH_INTERVAL_MILLIS,
            REFRESH_INTERVAL_MILLIS,
            pendingIntent,
        )
    }
}
