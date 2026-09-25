using System.Diagnostics;
using System.IO;

namespace PcCompanion;

/// <summary>
/// Installs an APK onto a phone via the raw adb.exe binary (`adb -s
/// &lt;serial&gt; install -r &lt;path&gt;`), same process-shelling approach as
/// AdbDeviceLister rather than AdvancedSharpAdbClient - the library's
/// InstallAsync needs a DeviceData from TvAdbClient's own single ADB
/// connection (currently pointed at the TV), so a phone target needs its
/// own path. Coarser progress than the TV's InstallApkAsync (no per-chunk
/// upload percentage, just "installing" until the process exits) since adb
/// install's own stdout doesn't expose progress - acceptable for a phone
/// install, which is typically much faster than pushing to a TV over Wi-Fi.
/// </summary>
public static class PhoneAdbInstaller
{
    private static string AdbExePath => Path.Combine(AppContext.BaseDirectory, "Assets", "adb", "adb.exe");

    public static async Task<Result<Unit>> InstallAsync(string serial, string localApkPath)
    {
        try
        {
            var (exitCode, output) = await RunAsync(AdbExePath, $"-s {serial} install -r \"{localApkPath}\"");
            if (exitCode != 0 || output.Contains("Failure", StringComparison.OrdinalIgnoreCase))
            {
                return Result<Unit>.Failure(output.Trim().Length > 0 ? output.Trim() : $"adb install exited with code {exitCode}");
            }
            return Result<Unit>.Success(Unit.Value);
        }
        catch (Exception ex)
        {
            return Result<Unit>.Failure(ex.Message);
        }
    }

    private static Task<(int ExitCode, string Output)> RunAsync(string fileName, string arguments)
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
