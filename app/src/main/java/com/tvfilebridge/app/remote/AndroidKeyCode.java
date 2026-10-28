package com.tvfilebridge.app.remote;

/** Standard Android KeyEvent codes used by {@code input keyevent <code>}. */
public final class AndroidKeyCode {
    private AndroidKeyCode() {
    }

    public static final int DPAD_UP = 19;
    public static final int DPAD_DOWN = 20;
    public static final int DPAD_LEFT = 21;
    public static final int DPAD_RIGHT = 22;
    public static final int DPAD_CENTER = 23;
    public static final int BACK = 4;
    public static final int HOME = 3;
    public static final int MENU = 82;

    public static final int MEDIA_PLAY_PAUSE = 85;
    public static final int MEDIA_REWIND = 89;
    public static final int MEDIA_FAST_FORWARD = 90;
    public static final int MEDIA_STOP = 86;

    public static final int VOLUME_UP = 24;
    public static final int VOLUME_DOWN = 25;
    public static final int VOLUME_MUTE = 164;

    public static final int POWER = 26;

    /** Opens the TV's own input-switch overlay (Sony's "Control menu" - HDMI inputs + pinned casting/apps), same as the physical remote's INPUT button. Confirmed working against a Sony Bravia Google TV. */
    public static final int TV_INPUT = 178;

    /**
     * Opens SystemUI's QuickSettingsWindowImpl overlay (Light sensor, Sound
     * settings, Audio output, Network, Bluetooth, Accessibility row) - the
     * same screen the physical Sony remote's gear/settings button opens.
     * This is a different screen from android.settings.SETTINGS (the full
     * multi-pane Settings app, which openTvSettings() already opens) - that
     * intent launches com.android.tv.settings/.MainSettings, while this is a
     * SystemUI overlay window with no launchable Activity/Intent of its own,
     * so it can only be reached via this keycode, confirmed by physically
     * capturing the gear button's raw input event (getevent) and cross
     * checking with the Button Mapper app, which identified it as the
     * Android-standard KEYCODE_BUTTON_2.
     */
    public static final int QUICK_SETTINGS = 189;

    public static final int DEL = 67;
}
