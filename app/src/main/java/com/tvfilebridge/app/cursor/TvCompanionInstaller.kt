package com.tvfilebridge.app.cursor

import android.content.Context
import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import java.io.File

private const val TAG = "TvCompanionInstaller"
const val TV_COMPANION_PACKAGE = "com.tvfilebridge.tvcompanion"
const val TV_COMPANION_ACCESSIBILITY_SERVICE = "$TV_COMPANION_PACKAGE/$TV_COMPANION_PACKAGE.CursorAccessibilityService"

/**
 * Pushes and installs the bundled TV companion APK (see app/build.gradle.kts
 * `copyCompanionApk` task) onto the active TV over the same ADB connection -
 * no separate distribution needed, the phone app carries its own companion.
 */
class TvCompanionInstaller(
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
            val response = dadb.shell("pm list packages $TV_COMPANION_PACKAGE")
            response.output.contains(TV_COMPANION_PACKAGE)
        }
        return result.getOrDefault(false)
    }

    private fun extractBundledApk(): File {
        val outFile = File(context.cacheDir, "tv_companion_${System.currentTimeMillis()}.apk")
        context.assets.open("tv_companion.apk").use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }
}
