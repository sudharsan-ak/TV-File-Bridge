package com.tvfilebridge.a11ywatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest-registered (BOOT_COMPLETED can be, unlike SCREEN_ON) - runs the fix once immediately on a real cold boot, then starts the foreground service so ACTION_SCREEN_ON keeps getting caught for the rest of the TV's uptime. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AccessibilityFixer.fixIfNeeded(context)
        WakeWatcherService.start(context)
    }
}
