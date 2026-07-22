using System.IO;
using System.Text.Json;

namespace PcCompanion;

/// <summary>
/// Persists AppSettings (port, device name, history limit, paired devices) as
/// JSON under %AppData%\PcCompanion - survives app restarts and system reboots.
/// </summary>
public class SettingsStore
{
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PcCompanion");
    private static readonly string FilePath = Path.Combine(DirectoryPath, "settings.json");

    public AppSettings Settings { get; private set; }

    public SettingsStore()
    {
        Settings = Load();
    }

    private AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var json = File.ReadAllText(FilePath);
                var loaded = JsonSerializer.Deserialize<AppSettings>(json);
                if (loaded != null) return loaded;
            }
        }
        catch
        {
            // Corrupt/unreadable settings file - fall back to defaults rather
            // than crashing the whole app on startup.
        }
        return new AppSettings();
    }

    public void Save()
    {
        Directory.CreateDirectory(DirectoryPath);
        var json = JsonSerializer.Serialize(Settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(FilePath, json);
    }
}
