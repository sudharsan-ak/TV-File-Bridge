package com.tvfilebridge.app.files;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Parses toybox {@code ls -la} and {@code find -printf} shell output into
 * {@link RemoteFile} entries, since dadb's sync API only exposes send/recv
 * (no LIST/STAT) and offers no directory-listing or search primitives.
 */
final class FileListParser {
    private FileListParser() {
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getDefault());
    }

    /** One line of {@code find <root> -type f ... -printf '%s %T@ %p\n'} output. */
    static RemoteFile parseFindLine(String line) {
        String[] parts = WHITESPACE.split(line, 3);
        if (parts.length < 3) {
            return null;
        }
        long size = parseLongOrZero(parts[0]);
        Long epochSeconds = parseLongOrNull(substringBefore(parts[1], '.'));
        String path = parts[2];

        return new RemoteFile(
                substringAfterLast(path, '/'),
                path,
                false,
                size,
                epochSeconds == null ? null : epochSeconds * 1000
        );
    }

    /** One line of {@code ls -la <path>} output, excluding the leading "total N" line. */
    static RemoteFile parseLsLine(String line, String parentPath) {
        if (line.trim().isEmpty() || line.startsWith("total ")) {
            return null;
        }

        String trimmed = trimEnd(line);
        String[] parts = WHITESPACE.split(trimmed, 8);
        if (parts.length < 8) {
            return null;
        }

        String perms = parts[0];
        String sizeStr = parts[4];
        String date = parts[5];
        String time = parts[6];
        String name = parts[7];

        // Symlinks: "name -> target" - keep just the link name.
        int arrowIndex = name.indexOf(" -> ");
        if (arrowIndex >= 0) {
            name = name.substring(0, arrowIndex);
        }

        if (name.equals(".") || name.equals("..")) {
            return null;
        }

        boolean isDirectory = perms.startsWith("d");
        long size = parseLongOrZero(sizeStr);
        Long modifiedAt = parseModifiedAt(date + " " + time);
        String fullPath = parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;

        return new RemoteFile(name, fullPath, isDirectory, size, modifiedAt);
    }

    private static Long parseModifiedAt(String dateTime) {
        try {
            return DATE_FORMAT.parse(dateTime).getTime();
        } catch (ParseException | NullPointerException e) {
            return null;
        }
    }

    private static long parseLongOrZero(String value) {
        Long parsed = parseLongOrNull(value);
        return parsed == null ? 0L : parsed;
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String substringBefore(String value, char delimiter) {
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private static String substringAfterLast(String value, char delimiter) {
        int index = value.lastIndexOf(delimiter);
        return index < 0 ? value : value.substring(index + 1);
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
