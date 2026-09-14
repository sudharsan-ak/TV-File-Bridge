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

private const val TAG = "SonyAuthRefreshReceiver"

/**
 * Fires every few days (see SonyAuthRefreshScheduler) to silently renew the
 * IRCC-IP session cookie for every saved device before Sony's 14-day expiry
 * hits - keeps "Wake TV" and scheduled wakes working without the user ever
 * having to notice or re-pair. A device unreachable right now (TV off, off
 * Wi-Fi) just fails silently and gets picked up on the next cycle.
 */
class SonyAuthRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TvFileBridgeApp).container
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = container.deviceStore.devices.first()
                devices.forEach { device ->
                    container.sonyIrccWaker.startRegistration(device.host)
                        .onFailure { Log.w(TAG, "refresh for ${device.host} failed (will retry next cycle): ${it.message}") }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
