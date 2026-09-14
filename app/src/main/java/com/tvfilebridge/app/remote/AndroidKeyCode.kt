package com.tvfilebridge.app.remote

/** Standard Android KeyEvent codes used by `input keyevent <code>`. */
object AndroidKeyCode {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val BACK = 4
    const val HOME = 3
    const val MENU = 82

    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MEDIA_STOP = 86

    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val VOLUME_MUTE = 164

    const val POWER = 26

    /** Opens the TV's own input-switch overlay (Sony's "Control menu" - HDMI inputs + pinned casting/apps), same as the physical remote's INPUT button. Confirmed working against a Sony Bravia Google TV. */
    const val TV_INPUT = 178

    const val DEL = 67
}
