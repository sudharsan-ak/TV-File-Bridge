package com.tvfilebridge.app.remote

import android.content.Context
import android.net.Uri
import com.tvfilebridge.app.clipboard.ReceivedFilesFolderStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the TV's current screen and saves it into the same folder the
 * user already picked for PC->phone file transfers (ReceivedFilesFolderStore,
 * via ScreenshotSaveHelper) - one place to look for anything that lands on
 * the phone from either the TV or PC side, rather than separate locations.
 */
class TvScreenshotSaver(
    private val context: Context,
    private val remoteControlRepository: RemoteControlRepository,
    receivedFilesFolderStore: ReceivedFilesFolderStore,
) {
    private val saveHelper = ScreenshotSaveHelper(context, receivedFilesFolderStore)

    suspend fun captureAndSave(): Result<Uri> {
        val tempFile = File(context.cacheDir, "tv_screenshot_${System.currentTimeMillis()}.png")
        val captureResult = remoteControlRepository.screenshot(tempFile)
        if (captureResult.isFailure) {
            // The DRM-blocked-black-image case still writes real bytes to
            // tempFile before failing its post-hoc pixel check - clean that
            // up here too, not just on the success path below.
            tempFile.delete()
            return Result.failure(captureResult.exceptionOrNull()!!)
        }

        val fileName = "TV Screenshot ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())}.png"
        val result = saveHelper.saveFromTempFile(tempFile, fileName)
        tempFile.delete()
        return result
    }
}
