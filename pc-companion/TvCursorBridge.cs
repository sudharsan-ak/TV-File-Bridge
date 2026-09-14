using System.IO;
using System.Net.Sockets;
using System.Text;

namespace PcCompanion;

/// <summary>
/// PC-side counterpart to the phone app's CursorBridge - same plain-text
/// command protocol (MOVE/CLICK/SHOW/HIDE) over an `adb forward` tunnel to
/// the TV companion app's command server (port 7912), so a mouse drag on
/// this PC's touchpad area drives the same on-screen cursor overlay the
/// phone's touchpad does. Requires TV Bridge Cursor installed and its
/// accessibility service enabled on the TV - same prerequisite as the phone.
///
/// Keeps ONE persistent socket open rather than reconnecting per command - a
/// touchpad drag fires MOVE many times a second, and the phone side found
/// that reconnecting per command flooded the forward's connection handling
/// badly enough to crash it. All sends are serialized through a lock since
/// the socket isn't safe for concurrent writers.
/// </summary>
public class TvCursorBridge
{
    private const int LocalForwardPort = 17913; // distinct from the phone's own 17912, so both can run against the same TV at once without a port clash
    private const int RemoteCommandPort = 7912;

    private readonly TvAdbClient _tvAdbClient;
    private readonly SemaphoreSlim _sendLock = new(1, 1);

    private bool _forwardActive;
    private TcpClient? _socket;
    private NetworkStream? _stream;
    private StreamReader? _reader;

    public TvCursorBridge(TvAdbClient tvAdbClient)
    {
        _tvAdbClient = tvAdbClient;
    }

    private async Task<bool> EnsureConnectedAsync()
    {
        if (_socket is { Connected: true }) return true;

        CloseSocket();
        if (!_forwardActive)
        {
            if (_tvAdbClient.RawConnection is not { } connection) return false;
            try
            {
                await connection.Client.CreateForwardAsync(connection.Device, $"tcp:{LocalForwardPort}", $"tcp:{RemoteCommandPort}", allowRebind: true, CancellationToken.None);
                _forwardActive = true;
            }
            catch
            {
                return false;
            }
        }

        try
        {
            var socket = new TcpClient();
            await socket.ConnectAsync("127.0.0.1", LocalForwardPort);
            socket.ReceiveTimeout = 3000;
            _socket = socket;
            _stream = socket.GetStream();
            _reader = new StreamReader(_stream, Encoding.UTF8);
            return true;
        }
        catch
        {
            // The forward itself may be stale (e.g. after a TV reconnect) -
            // drop it too so the next attempt rebuilds both, not just the socket.
            _forwardActive = false;
            return false;
        }
    }

    private void CloseSocket()
    {
        try { _reader?.Dispose(); } catch { /* best-effort */ }
        try { _stream?.Dispose(); } catch { /* best-effort */ }
        try { _socket?.Dispose(); } catch { /* best-effort */ }
        _reader = null;
        _stream = null;
        _socket = null;
    }

    private async Task<string?> SendCommandAsync(string command, bool expectReply = false)
    {
        await _sendLock.WaitAsync();
        try
        {
            if (!await EnsureConnectedAsync()) return null;
            try
            {
                var bytes = Encoding.UTF8.GetBytes(command + "\n");
                await _stream!.WriteAsync(bytes);
                await _stream.FlushAsync();

                if (!expectReply) return null;

                var lines = new StringBuilder();
                while (true)
                {
                    var line = await _reader!.ReadLineAsync();
                    if (line == null || line == "END") break;
                    lines.AppendLine(line);
                }
                return lines.ToString();
            }
            catch
            {
                CloseSocket();
                return null;
            }
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public Task MoveAsync(float dx, float dy) => SendCommandAsync($"MOVE {dx} {dy}");

    /// <summary>
    /// Absolute cursor position as a fraction (0.0-1.0) of the TV's screen
    /// width/height - lets the touchpad track plain mouse hover 1:1, no drag
    /// needed, unlike MoveAsync's relative deltas which fight a "reposition
    /// between strokes" motion. Needs the companion app's MOVE_TO command
    /// (added alongside this), not supported by older companion builds.
    /// </summary>
    public Task MoveToAsync(float xFraction, float yFraction) => SendCommandAsync($"MOVE_TO {xFraction} {yFraction}");

    /// <summary>
    /// Same split as the phone: the companion app's CLICK reply only reports
    /// where the on-screen cursor currently is - the actual tap is issued
    /// separately via `adb shell input tap`, the same mechanism the D-pad's
    /// keyevents already use reliably on this TV.
    /// </summary>
    public async Task ClickAsync()
    {
        var reply = await SendCommandAsync("CLICK", expectReply: true);
        if (reply == null) return;
        var coords = reply.Trim().Split(' ');
        if (coords.Length < 2) return;
        if (!int.TryParse(coords[0], out var x) || !int.TryParse(coords[1], out var y)) return;
        await _tvAdbClient.ShellAsync($"input tap {x} {y}");
    }

    public Task ShowAsync() => SendCommandAsync("SHOW");
    public Task HideAsync() => SendCommandAsync("HIDE");

    /// <summary>
    /// Real app labels (and icons, unused here) via the companion app's own
    /// PackageManager.loadLabel() call, same as the phone gets - unlike
    /// TvAdbClient.ListLaunchableAppsAsync's `pm list packages` + guessed
    /// label, which only has a bare package name to work with and produces
    /// wrong/ugly labels ("Android", "Api") for anything its heuristic can't
    /// parse cleanly.
    /// </summary>
    public async Task<List<RemoteApp>> ListAppsAsync()
    {
        var reply = await SendCommandAsync("LIST_APPS", expectReply: true);
        if (reply == null) return new List<RemoteApp>();

        var apps = new List<RemoteApp>();
        foreach (var line in reply.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = line.Split('|', 3);
            if (parts.Length < 2) continue;
            apps.Add(new RemoteApp { PackageName = parts[0], Label = parts[1] });
        }
        return apps;
    }

    public Task LaunchAppAsync(string packageName) => SendCommandAsync($"LAUNCH {packageName}");
}

/// <summary>App entry from the companion's LIST_APPS reply - PackageName + a real display Label, unlike TvAdbClient's InstalledApp which only has a guessed label from the bare package name.</summary>
public class RemoteApp
{
    public string PackageName { get; set; } = "";
    public string Label { get; set; } = "";
}
