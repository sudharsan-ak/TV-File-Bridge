package com.tvfilebridge.app.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Pair;

/**
 * Parses `uiautomator dump` XML output - time labels, seek bar bounds,
 * title/subtitle text, and XML entity decoding. Ported from Kotlin to Java
 * with no behavior change. Package-private: only RemoteControlRepository
 * calls these, same as when they were private functions in that file.
 */
final class UiDumpParser {

    private UiDumpParser() {
    }

    /**
     * Scans a `uiautomator dump` XML string for text nodes shaped like a
     * timestamp ("3:55", "1:23:45") and returns the first TWO as
     * (currentMs, totalMs) - generic across apps rather than matching a
     * specific app's resource-id (SmartTube's are current_time/total_time,
     * other apps use different ones), since the "current / total" pair
     * convention for a seek bar's time labels is near-universal even when the
     * exact view IDs aren't. Confirmed live against SmartTube's dump:
     * text="3:55" then text="/" then text="15:22", in that document order.
     */
    static Pair<Long, Long> parseTimePair(String dumpXml) {
        Pattern timePattern = Pattern.compile("text=\"(\\d{1,2}(?::\\d{2}){1,2})\"");
        Matcher matcher = timePattern.matcher(dumpXml);
        List<String> matches = new ArrayList<>();
        while (matcher.find() && matches.size() < 2) {
            matches.add(matcher.group(1));
        }
        if (matches.size() < 2) return null;

        Long current = parseTimestamp(matches.get(0));
        Long total = parseTimestamp(matches.get(1));
        if (current == null || total == null) return null;
        if (total <= current) return null;
        return new Pair<>(current, total);
    }

    static Long parseTimestamp(String text) {
        String[] rawParts = text.split(":");
        List<Integer> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            Integer value = toIntOrNull(rawPart);
            if (value != null) parts.add(value);
        }
        if (parts.isEmpty() || parts.size() > 3) return null;
        long seconds = 0L;
        for (int part : parts) seconds = seconds * 60 + part;
        return seconds * 1000;
    }

    /**
     * Finds the on-screen seek bar's real tappable pixel bounds in a
     * `uiautomator dump`. Generic across apps: rather than matching a specific
     * resource-id (SmartTube's is "playback_progress"), looks for any
     * clickable node whose class name contains "SeekBar" - confirmed live
     * against SmartTube: `class="...misc.SeekBar" clickable="true"
     * bounds="[112,842][1808,898]"`, and a direct `input tap` within those
     * bounds (mapped from a target fraction of the bar's width) reliably
     * jumped playback straight to that position - a real seek, not an
     * approximation via repeated fast-forward/rewind presses.
     */
    static SeekBarBounds parseSeekBarBounds(String dumpXml) {
        // Attribute order within a <node .../> tag isn't guaranteed, so match
        // each whole node tag first, then pull class/clickable/bounds out of it
        // independently rather than relying on one fixed attribute sequence.
        Pattern nodeTagPattern = Pattern.compile("<node\\b[^>]*/>");
        Pattern seekBarClassPattern = Pattern.compile("class=\"[^\"]*SeekBar[^\"]*\"");
        Pattern boundsPattern = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"");

        Matcher nodeMatcher = nodeTagPattern.matcher(dumpXml);
        while (nodeMatcher.find()) {
            String tag = nodeMatcher.group();
            boolean isSeekBarClass = seekBarClassPattern.matcher(tag).find();
            boolean isClickable = tag.contains("clickable=\"true\"");
            if (!isSeekBarClass || !isClickable) continue;

            Matcher boundsMatcher = boundsPattern.matcher(tag);
            if (!boundsMatcher.find()) continue;

            return new SeekBarBounds(
                    Integer.parseInt(boundsMatcher.group(1)),
                    Integer.parseInt(boundsMatcher.group(2)),
                    Integer.parseInt(boundsMatcher.group(3)),
                    Integer.parseInt(boundsMatcher.group(4))
            );
        }
        return null;
    }

    /**
     * Scrapes title/subtitle text directly from the on-screen overlay - a
     * DIFFERENT, fresher source than dumpsys media_session's metadata line.
     * Confirmed live: dumpsys reported "539K views" while the on-screen overlay
     * (captured in the exact same uiautomator dump used for duration/seek-bar
     * bounds) showed "541K views" at that same instant - SmartTube's own
     * session metadata publish lags behind what it actually renders. Generic
     * across apps: takes the first two substantial (>3 char, non-timestamp)
     * text nodes in document order, matching the title-then-subtitle layout
     * convention rather than a fixed resource-id.
     */
    static Pair<String, String> parseTitleSubtitle(String dumpXml) {
        Pattern textPattern = Pattern.compile("text=\"([^\"]+)\"");
        Pattern timestampPattern = Pattern.compile("^\\d{1,2}(?::\\d{2}){1,2}$");

        List<String> candidates = new ArrayList<>();
        Matcher matcher = textPattern.matcher(dumpXml);
        while (matcher.find()) {
            String candidate = unescapeXml(matcher.group(1));
            if (candidate.length() > 3 && !timestampPattern.matcher(candidate).matches() && !candidate.equals("Remaining")) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) return null;
        String title = candidates.get(0);
        String subtitle = candidates.size() > 1 ? candidates.get(1) : null;
        return new Pair<>(title, subtitle);
    }

    /**
     * uiautomator dump writes plain XML, so any non-ASCII character in an
     * app's on-screen text (emoji like 👍/👎 in SmartTube's like/dislike
     * counts, confirmed live) comes through as a numeric character reference
     * ("&#128077;") rather than the literal glyph - decodes both numeric
     * (&#NNNN; / &#xHHHH;) and the standard named XML entities.
     */
    static String unescapeXml(String text) {
        Pattern entityPattern = Pattern.compile("&#x([0-9a-fA-F]+);|&#(\\d+);|&(amp|lt|gt|quot|apos);");
        Matcher matcher = entityPattern.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(text, lastEnd, matcher.start());
            String hex = matcher.group(1);
            String dec = matcher.group(2);
            String named = matcher.group(3);
            if (hex != null && !hex.isEmpty()) {
                result.appendCodePoint(Integer.parseInt(hex, 16));
            } else if (dec != null && !dec.isEmpty()) {
                result.appendCodePoint(Integer.parseInt(dec));
            } else if ("amp".equals(named)) {
                result.append("&");
            } else if ("lt".equals(named)) {
                result.append("<");
            } else if ("gt".equals(named)) {
                result.append(">");
            } else if ("quot".equals(named)) {
                result.append("\"");
            } else if ("apos".equals(named)) {
                result.append("'");
            } else {
                result.append(matcher.group());
            }
            lastEnd = matcher.end();
        }
        result.append(text.substring(lastEnd));
        return result.toString();
    }

    private static Integer toIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
