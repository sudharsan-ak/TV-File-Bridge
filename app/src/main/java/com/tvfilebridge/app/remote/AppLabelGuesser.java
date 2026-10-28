package com.tvfilebridge.app.remote;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Derives a display label from an app's package name - ported from Kotlin to
 * Java with no behavior change. Package-private: only RemoteControlRepository
 * and NowPlayingParser call this, same callers as when it was a private
 * function in RemoteControlRepository.kt.
 */
final class AppLabelGuesser {

    private AppLabelGuesser() {
    }

    /**
     * ADB shell has no clean way to read an app's compiled string-resource label
     * (that lives in resources.arsc, not something `pm`/`dumpsys` prints as text
     * without root/aapt on the TV) - so labels are best-effort, derived from the
     * package name. Good enough for a launcher grid; not meant to be exact.
     */
    private static final List<String> KNOWN_SUFFIXES = List.of(
            "livingroomplus", "androidtv", "android_tv", "androidtvunplugged",
            "tvunplugged", "android", "tv", "app"
    );

    // Build-flavor/variant names some apps (commonly sideloaded ones, e.g.
    // SmartTube's "org.smarttube.stable") use as their actual LAST package
    // segment - naively taking substringAfterLast('.') on those produces a
    // meaningless label ("Stable") instead of the real app name, so those
    // segments are skipped in favor of the one before them.
    private static final Set<String> VARIANT_SEGMENTS = new LinkedHashSet<>(List.of(
            "stable", "beta", "debug", "release", "free", "pro", "lite", "nightly", "dev"
    ));

    static String guessLabel(String packageName) {
        String[] segments = packageName.split("\\.");
        String nameSegment = null;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!VARIANT_SEGMENTS.contains(segments[i].toLowerCase(Locale.ROOT))) {
                nameSegment = segments[i];
                break;
            }
        }
        if (nameSegment == null) nameSegment = segments[segments.length - 1];

        String cleaned = nameSegment.toLowerCase(Locale.ROOT);
        for (String suffix : KNOWN_SUFFIXES) {
            if (cleaned.endsWith(suffix) && cleaned.length() > suffix.length()) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
            }
        }
        if (cleaned.isEmpty()) cleaned = nameSegment;

        String spaced = cleaned.replaceAll("[_-]", " ");
        StringBuilder labelBuilder = new StringBuilder();
        for (String word : spaced.split(" ")) {
            if (word.isEmpty()) continue;
            if (labelBuilder.length() > 0) labelBuilder.append(" ");
            labelBuilder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String label = labelBuilder.toString();
        return label.isEmpty() ? packageName : label;
    }
}
