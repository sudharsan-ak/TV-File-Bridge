using Microsoft.Win32;

namespace PcCompanion;

/// <summary>
/// Registers/unregisters this app in the per-user Run key so it launches
/// automatically at Windows sign-in - no installer/scheduled-task needed for
/// a per-user tray app like this.
/// </summary>
public static class StartupRegistration
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "TvFileBridgeClipboard";

    public static void Apply(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
        if (key == null) return;

        if (enabled)
        {
            // --minimized tells App.OnStartup this launch came from Windows
            // sign-in, not the user double-clicking the app - so it skips
            // OpenMainWindow() and starts quiet in the tray instead.
            var exePath = Environment.ProcessPath;
            if (exePath != null) key.SetValue(ValueName, $"\"{exePath}\" --minimized");
        }
        else
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
