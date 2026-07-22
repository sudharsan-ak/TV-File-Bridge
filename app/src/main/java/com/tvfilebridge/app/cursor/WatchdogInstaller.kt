package com.tvfilebridge.app.cursor

import android.content.Context
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import java.io.File

private const val TAG = "WatchdogInstaller"
const val WATCHDOG_PACKAGE = "com.tvfilebridge.a11ywatchdog"

/**
 * Pushes and installs the bundled Accessibility Watchdog APK onto the active
 * TV over the same ADB connection - same mechanism as TvCompanionInstaller,
 * separate class since the two are installed independently (a user might
 * want the cursor companion but not the watchdog, or vice versa) and are
 * genuinely different apps with different purposes.
 */
class WatchdogInstaller(
    private val context: Context,
    private val connectionManager: AdbConnectionManager,
) {
    suspend fun install(): Result<Unit> {
        val result = connectionManager.withDadb { dadb ->
            val apkFile = extractBundledApk()
            try {
                dadb.install(apkFile, "-r", "-g")
            } finally {
                apkFile.delete()
            }
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "install failed: ${it.message}", it) }
        return result
    }

    suspend fun isInstalled(): Boolean {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("pm list packages $WATCHDOG_PACKAGE")
            response.output.contains(WATCHDOG_PACKAGE)
        }
        return result.getOrDefault(false)
    }

    private fun extractBundledApk(): File {
        val outFile = File(context.cacheDir, "accessibility_watchdog_${System.currentTimeMillis()}.apk")
        context.assets.open("accessibility_watchdog.apk").use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }
}
