using System.ComponentModel;
using System.Diagnostics;
using System.IO;

namespace PcCompanion;

public class AdbDevice : INotifyPropertyChanged
{
    public string Serial { get; set; } = "";
    public string Model { get; set; } = "";

    private string? _customName;
    // User-chosen name from Screen View's Phone tab rename, independent of
    // the clipboard-bridge's PairedDevices - falls back to the raw model
    // string when unset. See AppSettings.ScreenViewPhoneNames.
    public string? CustomName
    {
        get => _customName;
        set { _customName = value; OnPropertyChanged(nameof(CustomName)); OnPropertyChanged(nameof(DisplayName)); }
    }

    public string DisplayName => string.IsNullOrWhiteSpace(CustomName) ? Model : CustomName;

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>
/// Lists whatever's currently visible to `adb devices -l` - phones (USB or
/// already-paired wireless debugging) and TVs alike, no separate pairing
/// flow needed since a device either shows up here or it doesn't. Used by
/// Screen View's Phone tab; the TV tab still uses the app's own SavedTvs list
/// since a TV isn't auto-connected the way a USB/paired phone already is.
/// </summary>
public static class AdbDeviceLister
{
    private static string AdbExePath => Path.Combine(AppContext.BaseDirectory, "Assets", "adb", "adb.exe");

    public static async Task<List<AdbDevice>> ListAsync()
    {
        var output = await RunAsync(AdbExePath, "devices -l");
        var devices = new List<AdbDevice>();

        foreach (var rawLine in output.Split('\n'))
        {
            var line = rawLine.TrimEnd('\r').Trim();
            if (line.Length == 0 || line.StartsWith("List of devices")) continue;

            var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2 || parts[1] != "device") continue; // skip "unauthorized"/"offline" rows

            var serial = parts[0];
            var model = parts.FirstOrDefault(p => p.StartsWith("model:"))?.Substring("model:".Length) ?? serial;
            devices.Add(new AdbDevice { Serial = serial, Model = model.Replace('_', ' ') });
        }

        // Same physical device can show up more than once (e.g. duplicate
        // ADB-TLS mDNS advertisements) - dedup by model, keep the first serial seen.
        return devices
            .GroupBy(d => d.Model)
            .Select(g => g.First())
            .ToList();
    }

    private static Task<string> RunAsync(string fileName, string arguments)
    {
        var tcs = new TaskCompletionSource<string>();
        var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            },
            EnableRaisingEvents = true,
        };
        var output = new System.Text.StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data != null) output.AppendLine(e.Data); };
        process.Exited += (_, _) => tcs.TrySetResult(output.ToString());

        process.Start();
        process.BeginOutputReadLine();
        return tcs.Task;
    }
}
