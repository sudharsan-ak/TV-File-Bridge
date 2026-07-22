package com.tvfilebridge.app.files

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts a representative frame from a local video file and saves it as a
 * JPEG at [outputFile] - shared by ThumbnailRepository (TV, ADB pull) and
 * PcThumbnailRepository (PC, TCP pull), which each pull the source video to a
 * scratch file first since neither transport supports a partial/range read.
 */
fun extractVideoFrame(videoFile: File, outputFile: File) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
        FileOutputStream(outputFile).use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
    } finally {
        retriever.release()
    }
}
