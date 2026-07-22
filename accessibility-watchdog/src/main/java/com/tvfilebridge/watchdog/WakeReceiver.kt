package com.tvfilebridge.a11ywatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Triggers the fix check on both a genuine cold boot (BOOT_COMPLETED) and
 * every time the TV's screen turns back on (ACTION_SCREEN_ON) - the latter
 * covers what's actually the TV's normal daily "on/off" via the remote's
 * power button, which is standby, not a real shutdown, so BOOT_COMPLETED
 * alone would almost never fire in practice. ACTION_SCREEN_ON also covers
 * waking from idle-dimming, not just an explicit standby toggle - same
 * broadcast either way, no need to distinguish them.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AccessibilityFixer.fixIfNeeded(context)
    }
}
