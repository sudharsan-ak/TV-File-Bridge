using System.Diagnostics;
using System.IO;

namespace PcCompanion;

/// <summary>
/// Launches scrcpy against a TV, replacing the user's previous manual
/// double-click of open-tv-control.bat - same two steps (adb connect, then
/// scrcpy), just run from inside the app against a SavedTv instead of a
/// hardcoded IP in the bat file. scrcpy opens its own native window; this
/// doesn't embed it, matching the bat file's existing behavior.
/// </summary>
public static class ScrcpyLauncher
{
    private static string AdbExePath => Path.Combine(AppContext.BaseDirectory, "Assets", "adb", "adb.exe");
    private static string ScrcpyExePath => Path.Combine(AppContext.BaseDirectory, "Assets", "scrcpy", "scrcpy.exe");

    public static async Task<Result<Unit>> LaunchAsync(string host, int port)
    {
        var serial = $"{host}:{port}";
        try
        {
            var connectResult = await RunProcessAsync(AdbExePath, $"connect {serial}");
            if (connectResult.ExitCode != 0)
            {
                return Result<Unit>.Failure($"adb connect failed: {connectResult.Output.Trim()}");
            }

            return LaunchBySerial(serial);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    /// <summary>
    /// For a device that's already visible to `adb devices` (USB, or wireless
    /// debugging already paired) - no `adb connect` step, since that only
    /// applies to a host:port TCP target and would fail on a USB/mDNS serial.
    /// </summary>
    public static Result<Unit> LaunchBySerialAsync(string serial)
    {
        try
        {
            return LaunchBySerial(serial);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    private static Result<Unit> LaunchBySerial(string serial)
    {
        // scrcpy manages its own long-lived window/process - not awaited,
        // just started and left running independently of PC Companion,
        // same as the bat file's console window used to stay open.
        var scrcpy = new ProcessStartInfo
        {
            FileName = ScrcpyExePath,
            Arguments = $"--no-audio -s {serial} --video-codec=h264 --video-encoder=c2.android.avc.encoder",
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(ScrcpyExePath),
        };
        scrcpy.EnvironmentVariables["ADB"] = AdbExePath;
        Process.Start(scrcpy);
        return Result<Unit>.Success(Unit.Value);
    }

    private static Task<(int ExitCode, string Output)> RunProcessAsync(string fileName, string arguments)
    {
        var tcs = new TaskCompletionSource<(int, string)>();
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
        process.ErrorDataReceived += (_, e) => { if (e.Data != null) output.AppendLine(e.Data); };
        process.Exited += (_, _) => tcs.TrySetResult((process.ExitCode, output.ToString()));

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        return tcs.Task;
    }
}
