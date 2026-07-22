package com.tvfilebridge.a11ywatchdog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "accessibility_fix_check"

/**
 * Backstop for the screen-on/boot triggers: WorkManager's minimum periodic
 * interval is 15 minutes, but a 4-hour cadence is plenty here (per the
 * user's own usage pattern - the TV going a full 4+ hours without a single
 * screen-on/wake event covers the rare case where the always-on foreground
 * service somehow got killed and didn't restart). Each run is just two
 * settings reads and a conditional write - negligible cost either way.
 */
object PeriodicCheckScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FixCheckWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class FixCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AccessibilityFixer.fixIfNeeded(applicationContext)
        return Result.success()
    }
}
