namespace PcCompanion;

public enum ClipboardItemType
{
    Text,
    Image,
    File,
}

public enum ClipboardItemDirection
{
    Received, // phone -> PC
    Sent,     // PC -> phone
}

public class ClipboardHistoryItem
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public ClipboardItemType Type { get; set; }
    public ClipboardItemDirection Direction { get; set; } = ClipboardItemDirection.Received;
    public string? Text { get; set; }
    public string? ImageFilePath { get; set; }
    // Saved location of a received file (Type == File) - separate from
    // ImageFilePath since files land in the user-configured receive folder,
    // not the app's private history_images cache dir.
    public string? FileName { get; set; }
    public string? FilePath { get; set; }
    // For a Received item this is who it came from; for a Sent item this is
    // who it was sent to - SourceDeviceName's meaning flips with Direction
    // rather than having two separate fields, since History only ever shows
    // one "other party" name per entry either way.
    public string SourceDeviceName { get; set; } = "Unknown device";
    public DateTime ReceivedAt { get; set; } = DateTime.Now;
}

public class PairedDevice
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string DeviceName { get; set; } = "";
    public string IpAddress { get; set; } = "";
    public DateTime PairedAt { get; set; } = DateTime.Now;
    public DateTime? LastSeenAt { get; set; }
    // Only one paired phone can be primary at a time - it's the target for
    // this PC's own clipboard-watcher pushes (PC -> phone direction), mirroring
    // the phone app's own "primary PC" concept for its Copy-to-PC direction.
    public bool IsPrimary { get; set; }
}

public class AppSettings
{
    public int Port { get; set; } = 58821;
    public string DeviceName { get; set; } = Environment.MachineName;
    public int MaxHistoryItems { get; set; } = 20;
    public bool LaunchAtStartup { get; set; } = true;
    public List<PairedDevice> PairedDevices { get; set; } = new();
    // Both off by default, and split by type rather than one combined
    // toggle - watching the PC clipboard and pushing every copy to a phone
    // could surface something sensitive (a password, temp text) the user
    // never meant to send anywhere, and images vs. text are different
    // enough in practice (e.g. wanting screenshots to sync but not every
    // copied password/snippet) that one shared switch wasn't granular enough.
    public bool AutoSendImagesToPhone { get; set; }
    public bool AutoSendTextToPhone { get; set; }
    public bool AutoSendFilesToPhone { get; set; }

    // Opposite direction: accepting files pushed FROM the phone (via its
    // Copy to PC share action) and where they get saved on this PC. Off by
    // default like the others - a file landing somewhere is a bigger deal
    // than text/images just going onto the clipboard, so it's opt-in.
    public bool ReceiveFilesFromPhone { get; set; }
    public string? ReceivedFilesFolder { get; set; }
}
