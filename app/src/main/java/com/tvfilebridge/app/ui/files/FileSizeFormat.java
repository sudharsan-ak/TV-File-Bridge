package com.tvfilebridge.app.ui.files;

import java.text.SimpleDateFormat;
import java.util.Locale;

public final class FileSizeFormat {
    private FileSizeFormat() {
    }

    private static final SimpleDateFormat DISPLAY_DATE_FORMAT = new SimpleDateFormat("MMM d", Locale.US);
    private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US);

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return value < 10
                ? String.format(Locale.US, "%.1f %s", value, units[unitIndex])
                : String.format(Locale.US, "%.0f %s", value, units[unitIndex]);
    }

    public static String formatModifiedDate(Long epochMillis) {
        if (epochMillis == null) {
            return "";
        }
        return DISPLAY_DATE_FORMAT.format(epochMillis);
    }

    public static String formatFullDate(Long epochMillis) {
        if (epochMillis == null) {
            return "Unknown";
        }
        return FULL_DATE_FORMAT.format(epochMillis);
    }

    /**
     * Expands the single-letter unit suffix from shell `du -h`/`df -h` output
     * (e.g. "4.6M", "8.0K", "1.6G") into a clear unit ("4.6 MB", "8.0 KB", "1.6 GB").
     */
    public static String expandSizeUnit(String rawSize) {
        String trimmed = rawSize.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        char unit = Character.toUpperCase(trimmed.charAt(trimmed.length() - 1));
        String expanded;
        switch (unit) {
            case 'K':
                expanded = "KB";
                break;
            case 'M':
                expanded = "MB";
                break;
            case 'G':
                expanded = "GB";
                break;
            case 'T':
                expanded = "TB";
                break;
            case 'B':
                expanded = "B";
                break;
            default:
                return trimmed;
        }
        String number = trimmed.substring(0, trimmed.length() - 1);
        return number + " " + expanded;
    }
}
