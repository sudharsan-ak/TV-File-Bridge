using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.CompilerServices;
using System.Text;
using AdvancedSharpAdbClient;
using AdvancedSharpAdbClient.Models;
using AdvancedSharpAdbClient.Receivers;

namespace PcCompanion;

public class TvFile : INotifyPropertyChanged
{
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase) { "jpg", "jpeg", "png", "gif", "webp", "bmp" };
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase) { "mp4", "mkv", "webm", "3gp", "mov", "avi" };

    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    public bool IsDirectory { get; set; }
    public long SizeBytes { get; set; }
    // Parsed from "ls -la"'s date/time columns - DateTime.MinValue when that
    // parse fails, so sorting by date still works (falls to the back) rather
    // than throwing.
    public DateTime ModifiedAt { get; set; } = DateTime.MinValue;

    private string Extension => Name.Contains('.') ? Name[(Name.LastIndexOf('.') + 1)..] : "";
    public bool IsImage => !IsDirectory && ImageExtensions.Contains(Extension);
    public bool IsVideo => !IsDirectory && VideoExtensions.Contains(Extension);
    public bool IsOther => !IsDirectory && !IsImage && !IsVideo;

    private string? _thumbnailPath;
    // Set asynchronously after the list first renders (thumbnails are pulled
    // on demand, not up front) - INotifyPropertyChanged so the bound Image
    // in the grid view picks it up once the pull completes.
    public string? ThumbnailPath
    {
        get => _thumbnailPath;
        set { _thumbnailPath = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ThumbnailPath))); }
    }

    private bool _isSelected;
    public bool IsSelected
    {
        get => _isSelected;
        set { _isSelected = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsSelected))); }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}

public class TvBreadcrumb
{
    public string Label { get; set; } = "";
    public string Path { get; set; } = "";
}

/// <summary>
/// PC-side counterpart to the phone app's AdbConnectionManager/FileRepository -
/// same protocol shape (ls -la over shell, pull over sync), separate
/// implementation since this runs against AdvancedSharpAdbClient (which needs
/// the bundled adb.exe as a local server) rather than dadb's direct wire
/// protocol. Independent of any phone's own connection to the same TV - both
/// can be connected simultaneously, ADB supports multiple trusted clients.
/// </summary>
public class TvAdbClient
{
    private readonly AdbClient _client = new();
    private DeviceData? _device;

    public bool IsConnected => _device != null;
    public string? ConnectedHost { get; private set; }
    public int? ConnectedPort { get; private set; }

    /// <summary>Exposes the underlying AdbClient/DeviceData for TvCursorBridge's port-forward setup - that needs the raw client/device pair, not the higher-level shell/push/pull helpers this class otherwise wraps them in.</summary>
    internal (AdbClient Client, DeviceData Device)? RawConnection => _device is { } device ? (_client, device) : null;

    private static string AdbExePath => Path.Combine(AppContext.BaseDirectory, "Assets", "adb", "adb.exe");

    public async Task<Result<Unit>> ConnectAsync(string host, int port)
    {
        try
        {
            EnsureServerStarted();
            await _client.ConnectAsync(host, port, CancellationToken.None);
            var serial = $"{host}:{port}";
            _device = _client.GetDevices().FirstOrDefault(d => d.Serial == serial);
            if (_device == null)
            {
                return Result<Unit>.Failure("TV didn't appear in device list after connect - check that \"Allow USB debugging\" was accepted on the TV.");
            }
            ConnectedHost = host;
            ConnectedPort = port;
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            _device = null;
            ConnectedHost = null;
            ConnectedPort = null;
            return Result<Unit>.Failure(ex.Message);
        }
    }

    public void Disconnect()
    {
        _device = null;
        ConnectedHost = null;
        ConnectedPort = null;
    }

    private void EnsureServerStarted()
    {
        if (AdbServer.Instance.GetStatus().IsRunning) return;
        var server = new AdbServer();
        server.StartServer(AdbExePath, restartServerIfNewer: false);
    }

    public async Task<Result<List<TvFile>>> ListAsync(string path)
    {
        if (_device is not { } device) return Result<List<TvFile>>.Failure("Not connected");
        try
        {
            var listPath = path.EndsWith('/') ? path : path + "/";
            var receiver = new ConsoleOutputReceiver();
            await _client.ExecuteRemoteCommandAsync($"ls -la '{listPath.Replace("'", "'\\''")}'", device, receiver, Encoding.UTF8, CancellationToken.None);
            var entries = receiver.ToString()
                .Split('\n')
                .Select(line => ParseLsLine(line, path))
                .Where(f => f != null)
                .Select(f => f!)
                .OrderByDescending(f => f.IsDirectory)
                .ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase)
                .ToList();
            return Result<List<TvFile>>.Success(entries);
        }
        catch (Exception ex)
        {
            return Result<List<TvFile>>.Failure(ex.Message);
        }
    }

    private static readonly string ThumbnailCacheDir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "PcCompanionTvThumbnails");

    /// <summary>
    /// Pulls and caches an image file for grid-view thumbnails, keyed by
    /// path+size so a file is never re-pulled once cached - same approach as
    /// the phone app's ThumbnailRepository, separate cache dir since this
    /// runs as a different process on a different device.
    /// </summary>
    public async Task<string?> GetThumbnailAsync(TvFile file)
    {
        if (!file.IsImage) return null;
        Directory.CreateDirectory(ThumbnailCacheDir);
        var key = $"{file.Path}:{file.SizeBytes}".GetHashCode().ToString("x8");
        var ext = file.Name.Contains('.') ? file.Name[(file.Name.LastIndexOf('.') + 1)..] : "jpg";
        var cachePath = System.IO.Path.Combine(ThumbnailCacheDir, $"{key}.{ext}");
        if (File.Exists(cachePath)) return cachePath;

        var result = await PullAsync(file.Path, cachePath);
        return result.IsSuccess ? cachePath : null;
    }

    private static readonly string DragCacheDir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "PcCompanionTvDragCache");

    /// <summary>
    /// Returns a local path for this file under a hash-named subfolder, so
    /// the leaf filename itself stays exactly the real name (no prefix) -
    /// that leaf name is what Explorer shows once dropped. Reuses the
    /// thumbnail cache's bytes via a local copy when available rather than
    /// re-pulling from the TV, since a same-disk copy is effectively free
    /// next to a network pull.
    /// </summary>
    public async Task<string?> GetOrPullLocalFileAsync(TvFile file)
    {
        var key = $"{file.Path}:{file.SizeBytes}".GetHashCode().ToString("x8");
        var folder = System.IO.Path.Combine(DragCacheDir, key);
        Directory.CreateDirectory(folder);
        var cachePath = System.IO.Path.Combine(folder, file.Name);
        if (File.Exists(cachePath)) return cachePath;

        if (file.ThumbnailPath != null && File.Exists(file.ThumbnailPath))
        {
            File.Copy(file.ThumbnailPath, cachePath, overwrite: true);
            return cachePath;
        }

        var result = await PullAsync(file.Path, cachePath);
        return result.IsSuccess ? cachePath : null;
    }

    public async Task<Result<Unit>> PullAsync(string remotePath, string localPath)
    {
        if (_device is not { } device) return Result<Unit>.Failure("Not connected");
        try
        {
            using var service = new SyncService(device);
            await using var stream = File.OpenWrite(localPath);
            await service.PullAsync(remotePath, stream, null, useV2: true, CancellationToken.None);
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    /// <summary>
    /// `adb install -r` equivalent, straight from a local file - lets the app
    /// sideload any picked APK onto the TV without first copying it into TV
    /// storage, same as running `adb install` from a shell at the platform-tools
    /// folder. `-r` matches the "safe to install again" behavior used
    /// elsewhere (TvCompanionInstaller.install on the phone passes the same flag).
    /// </summary>
    public async Task<Result<Unit>> InstallApkAsync(string localApkPath, Action<InstallProgressEventArgs> onProgress)
    {
        if (_device is not { } device) return Result<Unit>.Failure("Not connected");
        try
        {
            await using var stream = File.OpenRead(localApkPath);
            await _client.InstallAsync(device, stream, onProgress, CancellationToken.None, "-r");
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    public async Task<Result<Unit>> PushAsync(string localPath, string remoteDir)
    {
        if (_device is not { } device) return Result<Unit>.Failure("Not connected");
        try
        {
            var fileName = Path.GetFileName(localPath);
            var remotePath = remoteDir.TrimEnd('/') + "/" + fileName;
            using var service = new SyncService(device);
            await using var stream = File.OpenRead(localPath);
            await service.PushAsync(stream, remotePath, UnixFileStatus.DefaultFileMode, DateTimeOffset.Now, null, useV2: true, CancellationToken.None);
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    /// <summary>
    /// Shell `mv` in place, same approach as the phone app's FileRepository -
    /// AdvancedSharpAdbClient's sync protocol has no dedicated rename/move,
    /// same limitation dadb has on the phone side.
    /// </summary>
    public async Task<Result<string>> RenameAsync(string path, string newName)
    {
        if (_device is not { } device) return Result<string>.Failure("Not connected");
        try
        {
            var parent = path[..path.LastIndexOf('/')];
            var newPath = $"{parent}/{newName}";
            var receiver = new ConsoleOutputReceiver();
            await _client.ExecuteRemoteCommandAsync($"mv '{path.Replace("'", "'\\''")}' '{newPath.Replace("'", "'\\''")}'", device, receiver, Encoding.UTF8, CancellationToken.None);
            var output = receiver.ToString();
            if (!string.IsNullOrWhiteSpace(output)) return Result<string>.Failure(output.Trim());
            return Result<string>.Success(newPath);
        }
        catch (Exception ex)
        {
            return Result<string>.Failure(ex.Message);
        }
    }

    /// <summary>Same `input keyevent` mechanism the phone's RemoteControlRepository.keyEvent uses - see AndroidKeyCode for the standard codes.</summary>
    public async Task<Result<Unit>> SendKeyEventAsync(int code) => ToUnitResult(await ShellAsync($"input keyevent {code}"));

    /// <summary>Opens the TV's own system Settings, matching the phone's Remote tab Settings button - via the standard android.settings.SETTINGS intent action rather than a keyevent, since there's no reliable KEYCODE_SETTINGS equivalent on Android TV.</summary>
    public async Task<Result<Unit>> OpenTvSettingsAsync() => ToUnitResult(await ShellAsync("am start -a android.settings.SETTINGS"));

    /// <summary>Same `monkey` launcher-intent trick the phone's RemoteControlRepository.launchApp uses - works without needing each app's specific launch activity name.</summary>
    public async Task<Result<Unit>> LaunchAppAsync(string packageName) => ToUnitResult(await ShellAsync($"monkey -p {packageName} -c android.intent.category.LAUNCHER 1"));

    private const string TvCompanionPackage = "com.tvfilebridge.tvcompanion";
    private const string TvCompanionAccessibilityService = TvCompanionPackage + "/" + TvCompanionPackage + ".CursorAccessibilityService";

    /// <summary>Same `pm list packages` check the phone's TvCompanionInstaller.isInstalled uses - true only if TV Bridge Cursor (installed from the phone app's Install screen) is present on the connected TV.</summary>
    public async Task<bool> IsCursorCompanionInstalledAsync()
    {
        var result = await ShellAsync($"pm list packages {TvCompanionPackage}");
        return result.IsSuccess && result.Value!.Contains(TvCompanionPackage);
    }

    /// <summary>
    /// Same read-modify-write settings trick the phone's
    /// RemoteControlRepository.ensureCursorAccessibilityEnabled uses - ADB's
    /// shell UID is trusted with WRITE_SECURE_SETTINGS for exactly this, no
    /// root needed. Appends the companion's accessibility service to
    /// whatever's already enabled rather than overwriting, so other apps'
    /// accessibility features (a different remote app, etc.) aren't silently
    /// disabled. No-ops if already enabled.
    /// </summary>
    public async Task<Result<Unit>> EnsureCursorAccessibilityEnabledAsync()
    {
        var currentResult = await ShellAsync("settings get secure enabled_accessibility_services");
        if (!currentResult.IsSuccess) return Result<Unit>.Failure(currentResult.ErrorMessage!);

        var services = currentResult.Value!.Trim().Split(':').Select(s => s.Trim()).Where(s => s.Length > 0 && s != "null").ToList();
        if (!services.Contains(TvCompanionAccessibilityService))
        {
            var newList = string.Join(":", services.Append(TvCompanionAccessibilityService));
            var setResult = await ShellAsync($"settings put secure enabled_accessibility_services '{newList}'");
            if (!setResult.IsSuccess) return Result<Unit>.Failure(setResult.ErrorMessage!);
        }

        var globalEnabledResult = await ShellAsync("settings get secure accessibility_enabled");
        if (!globalEnabledResult.IsSuccess) return Result<Unit>.Failure(globalEnabledResult.ErrorMessage!);
        if (globalEnabledResult.Value!.Trim() != "1")
        {
            var setGlobalResult = await ShellAsync("settings put secure accessibility_enabled 1");
            if (!setGlobalResult.IsSuccess) return Result<Unit>.Failure(setGlobalResult.ErrorMessage!);
        }

        return Result<Unit>.Success(Unit.Value);
    }

    private static Result<Unit> ToUnitResult(Result<string> result) =>
        result.IsSuccess ? Result<Unit>.Success(Unit.Value) : Result<Unit>.Failure(result.ErrorMessage!);

    /// <summary>Runs an arbitrary shell command on the connected TV and returns its combined output - the general-purpose primitive the key/settings/launch helpers above are built on.</summary>
    public async Task<Result<string>> ShellAsync(string command)
    {
        if (_device is not { } device) return Result<string>.Failure("Not connected");
        try
        {
            var receiver = new ConsoleOutputReceiver();
            await _client.ExecuteRemoteCommandAsync(command, device, receiver, Encoding.UTF8, CancellationToken.None);
            return Result<string>.Success(receiver.ToString());
        }
        catch (Exception ex)
        {
            return Result<string>.Failure(ex.Message);
        }
    }

    /// <summary>Installed third-party apps with a launcher entry, via `pm list packages -3` plus a guessed display label - same shape as the phone's RemoteControlRepository.listLaunchableApps.</summary>
    public async Task<Result<List<InstalledApp>>> ListLaunchableAppsAsync()
    {
        var result = await ShellAsync("pm list packages -3");
        if (!result.IsSuccess) return Result<List<InstalledApp>>.Failure(result.ErrorMessage!);

        var apps = result.Value!
            .Split('\n')
            .Select(line => line.Trim())
            .Where(line => line.StartsWith("package:"))
            .Select(line => line["package:".Length..].Trim())
            .Where(pkg => pkg.Length > 0)
            .Select(pkg => new InstalledApp { PackageName = pkg, Label = GuessAppLabel(pkg) })
            .OrderBy(app => app.Label, StringComparer.OrdinalIgnoreCase)
            .ToList();
        return Result<List<InstalledApp>>.Success(apps);
    }

    private static readonly string[] KnownAppSuffixes = { "livingroomplus", "androidtv", "android_tv", "androidtvunplugged", "tvunplugged", "android", "tv", "app" };
    private static readonly HashSet<string> VariantSegments = new(StringComparer.OrdinalIgnoreCase) { "stable", "beta", "debug", "release", "free", "pro", "lite", "nightly", "dev" };

    /// <summary>
    /// Best-effort human label from a bare package name, same heuristic as
    /// the phone's guessLabel - not meant to be exact, just good enough for a
    /// launcher list instead of showing raw package names like
    /// "com.google.android.youtube.tv".
    /// </summary>
    private static string GuessAppLabel(string packageName)
    {
        var segments = packageName.Split('.');
        var nameSegment = segments.LastOrDefault(s => !VariantSegments.Contains(s)) ?? segments[^1];
        var cleaned = nameSegment.ToLowerInvariant();
        foreach (var suffix in KnownAppSuffixes)
        {
            if (cleaned.EndsWith(suffix) && cleaned.Length > suffix.Length) cleaned = cleaned[..^suffix.Length];
        }
        if (cleaned.Length == 0) cleaned = nameSegment;

        var words = cleaned.Replace('_', ' ').Replace('-', ' ')
            .Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Select(w => char.ToUpperInvariant(w[0]) + w[1..]);
        var label = string.Join(" ", words);
        return label.Length == 0 ? packageName : label;
    }

    public async Task<Result<Unit>> DeleteAsync(string path)
    {
        if (_device is not { } device) return Result<Unit>.Failure("Not connected");
        try
        {
            var receiver = new ConsoleOutputReceiver();
            await _client.ExecuteRemoteCommandAsync($"rm -rf '{path.Replace("'", "'\\''")}'", device, receiver, Encoding.UTF8, CancellationToken.None);
            var output = receiver.ToString();
            if (!string.IsNullOrWhiteSpace(output)) return Result<Unit>.Failure(output.Trim());
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    /// <summary>
    /// Same toybox `ls -la` parsing approach as the phone app's FileRepository
    /// (dadb's sync API has no LIST/STAT either) - perms links owner group
    /// size date time name, skipping the leading "total N" line.
    /// </summary>
    private static TvFile? ParseLsLine(string line, string parentPath)
    {
        line = line.TrimEnd('\r');
        if (string.IsNullOrWhiteSpace(line) || line.StartsWith("total ")) return null;

        var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 8) return null;

        var perms = parts[0];
        var isDirectory = perms.StartsWith('d');
        if (!long.TryParse(parts[4], out var size)) return null;

        // Columns 5 and 6 are the date and time (e.g. "2024-03-11 14:07") on
        // Android's toolbox/toybox "ls -la" - best-effort parse, falls back
        // to MinValue (sorts to the back) rather than failing the whole row.
        DateTime.TryParse($"{parts[5]} {parts[6]}", CultureInfo.InvariantCulture, DateTimeStyles.None, out var modifiedAt);

        // Name is everything after the fixed date/time columns (index 7
        // onward), joined back together, so filenames containing spaces
        // still parse correctly.
        var name = string.Join(' ', parts.Skip(7));
        if (name == "." || name == "..") return null;

        var fullPath = parentPath.TrimEnd('/') + "/" + name;
        return new TvFile { Name = name, Path = fullPath, IsDirectory = isDirectory, SizeBytes = isDirectory ? 0 : size, ModifiedAt = modifiedAt };
    }
}

public readonly struct Unit
{
    public static readonly Unit Value = new();
}

public class Result<T>
{
    public bool IsSuccess { get; private init; }
    public T? Value { get; private init; }
    public string? ErrorMessage { get; private init; }

    public static Result<T> Success(T value) => new() { IsSuccess = true, Value = value };
    public static Result<T> Failure(string message) => new() { IsSuccess = false, ErrorMessage = message };
}
