package com.tvfilebridge.app.files;

/** Single-quotes a path for safe inclusion in a shell command line. */
public final class ShellQuote {
    private ShellQuote() {
    }

    public static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
