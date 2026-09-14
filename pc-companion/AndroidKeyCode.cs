namespace PcCompanion;

/// <summary>Standard Android KeyEvent codes used by `input keyevent &lt;code&gt;` - same values as the phone app's AndroidKeyCode.kt.</summary>
public static class AndroidKeyCode
{
    public const int DpadUp = 19;
    public const int DpadDown = 20;
    public const int DpadLeft = 21;
    public const int DpadRight = 22;
    public const int DpadCenter = 23;
    public const int Back = 4;
    public const int Home = 3;
    public const int Menu = 82;

    public const int MediaPlayPause = 85;
    public const int MediaRewind = 89;
    public const int MediaFastForward = 90;
    public const int MediaStop = 86;

    public const int VolumeUp = 24;
    public const int VolumeDown = 25;
    public const int VolumeMute = 164;

    public const int Power = 26;

    /// <summary>Opens the TV's own input-switch overlay (Sony's "Control menu" - HDMI inputs + pinned casting/apps), same as the physical remote's INPUT button. Confirmed working against a Sony Bravia Google TV.</summary>
    public const int TvInput = 178;
}
