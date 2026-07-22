package com.tvfilebridge.app.ui.files

import java.text.SimpleDateFormat
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (value < 10) "%.1f %s".format(value, units[unitIndex]) else "%.0f %s".format(value, units[unitIndex])
}

private val displayDateFormat = SimpleDateFormat("MMM d", Locale.US)
private val fullDateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US)

fun formatModifiedDate(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    return displayDateFormat.format(epochMillis)
}

fun formatFullDate(epochMillis: Long?): String {
    if (epochMillis == null) return "Unknown"
    return fullDateFormat.format(epochMillis)
}

/**
 * Expands the single-letter unit suffix from shell `du -h`/`df -h` output
 * (e.g. "4.6M", "8.0K", "1.6G") into a clear unit ("4.6 MB", "8.0 KB", "1.6 GB").
 */
fun expandSizeUnit(rawSize: String): String {
    val trimmed = rawSize.trim()
    val unit = trimmed.lastOrNull()?.uppercaseChar()
    val expanded = when (unit) {
        'K' -> "KB"
        'M' -> "MB"
        'G' -> "GB"
        'T' -> "TB"
        'B' -> "B"
        else -> return trimmed
    }
    val number = trimmed.dropLast(1)
    return "$number $expanded"
}
