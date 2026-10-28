package com.tvfilebridge.app.remote;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses network interface info from `ip addr show` output - ported from
 * Kotlin to Java with no behavior change. Package-private: only
 * RemoteControlRepository calls this, same as when it was a private function
 * in that file.
 */
final class NetworkInfoParser {

    private NetworkInfoParser() {
    }

    /** Extracts the `link/ether XX:XX:XX:XX:XX:XX` MAC line that follows an interface's own header line (e.g. `13: wlan0: <...>`) in `ip addr show` output. */
    static String parseMacAddress(String ipAddrOutput, String interfaceName) {
        String[] lines = ipAddrOutput.split("\r\n|\n|\r", -1);

        int headerIndex = -1;
        String headerNeedleColon = ": " + interfaceName + ":";
        String headerNeedleAt = ": " + interfaceName + "@";
        for (int i = 0; i < lines.length; i++) {
            String line = trimStart(lines[i]);
            if (line.contains(headerNeedleColon) || line.contains(headerNeedleAt)) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex == -1) return null;

        Pattern macPattern = Pattern.compile("link/ether ([0-9a-fA-F:]{17})");
        int end = Math.min(headerIndex + 4, lines.length);
        for (int i = headerIndex + 1; i < end; i++) {
            Matcher macMatcher = macPattern.matcher(lines[i]);
            if (macMatcher.find()) return macMatcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String trimStart(String value) {
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        return value.substring(start);
    }
}
