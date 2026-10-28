package com.tvfilebridge.app.remote;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses `dumpsys media_session` output into a NowPlayingInfo - ported from
 * Kotlin to Java with no behavior change. Package-private: only
 * RemoteControlRepository calls this, same as when it was a private function
 * in that file.
 */
final class NowPlayingParser {

    private NowPlayingParser() {
    }

    /**
     * Parses the top (most recently active) session out of `dumpsys
     * media_session`'s "Sessions Stack" block. Real sample line shapes this was
     * built against (captured live from a Sony Bravia):
     *   package=com.fox.foxone
     *   state=PlaybackState {state=3, position=-1, buffered position=-1, ...}
     *   metadata: null
     * state codes follow PlaybackState: 3=playing, 2=paused, 1=stopped, 6=buffering.
     * When metadata is present it looks like:
     *   metadata: size=3, description=..., mediaId=null, ...
     * but the exact metadata dump format varies enough across Android versions
     * that title/artist are pulled from the simpler `description=<title>, ...`
     * portion rather than a strict field-by-field parse.
     */
    static NowPlayingInfo parse(String dumpOutput) {
        String[] lines = dumpOutput.split("\r\n|\n|\r", -1);

        int packageIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (trimStart(lines[i]).startsWith("package=")) {
                packageIndex = i;
                break;
            }
        }
        if (packageIndex == -1) return null;

        String packageName = substringAfter(lines[packageIndex], "package=").trim();
        if (packageName.isEmpty()) return null;

        String stateLine = null;
        for (int i = packageIndex; i < lines.length; i++) {
            if (trimStart(lines[i]).startsWith("state=PlaybackState")) {
                stateLine = lines[i];
                break;
            }
        }
        if (stateLine == null) return null;

        Integer stateCode = findGroupAsInt(Pattern.compile("state=(\\d+)"), stateLine, 1);
        boolean isPlaying = stateCode != null && stateCode == 3;
        boolean isPaused = stateCode != null && stateCode == 2;

        Long position = findGroupAsLong(Pattern.compile("position=(-?\\d+)"), stateLine, 1);
        if (position != null && position < 0) position = null;

        String metadataLine = null;
        for (int i = packageIndex; i < lines.length; i++) {
            if (trimStart(lines[i]).startsWith("metadata:")) {
                metadataLine = lines[i];
                break;
            }
        }

        String title = null;
        String artist = null;
        Long duration = null;
        if (metadataLine != null && !metadataLine.contains("metadata: null")) {
            // The "description=" field is actually MediaDescription's own
            // toString(): "title, subtitle, description" comma-joined (there's
            // no separate "subtitle=" key in this dump format - confirmed live
            // against SmartTube: "description=Video Title, Channel • 538K
            // views • Premiered..., null"). Split on the FIRST two commas only,
            // since the subtitle/description text itself commonly contains
            // commas (e.g. "538K views, premiered..."), which would otherwise
            // truncate it.
            Matcher descriptionMatcher = Pattern.compile("description=(.+)").matcher(metadataLine);
            if (descriptionMatcher.find()) {
                String[] parts = descriptionMatcher.group(1).split(",", 3);
                title = blankOrNullToken(parts.length > 0 ? parts[0].trim() : null);
                artist = blankOrNullToken(parts.length > 1 ? parts[1].trim() : null);
            }
            Long parsedDuration = findGroupAsLong(Pattern.compile("duration=(\\d+)"), metadataLine, 1);
            if (parsedDuration != null && parsedDuration > 0) duration = parsedDuration;
        }

        return new NowPlayingInfo(
                packageName,
                AppLabelGuesser.guessLabel(packageName),
                title,
                artist,
                isPlaying,
                isPaused,
                position,
                duration
        );
    }

    private static String trimStart(String value) {
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        return value.substring(start);
    }

    private static String substringAfter(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        return index == -1 ? value : value.substring(index + delimiter.length());
    }

    private static String blankOrNullToken(String value) {
        if (value == null || value.isEmpty() || value.equals("null")) return null;
        return value;
    }

    private static Integer findGroupAsInt(Pattern pattern, String input, int group) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return null;
        try {
            return Integer.valueOf(matcher.group(group));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long findGroupAsLong(Pattern pattern, String input, int group) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return null;
        try {
            return Long.valueOf(matcher.group(group));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
