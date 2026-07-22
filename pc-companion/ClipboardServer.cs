using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Media.Imaging;
using Application = System.Windows.Application;
using Clipboard = System.Windows.Clipboard;

namespace PcCompanion;

/// <summary>
/// A "push" header sent as a length-prefixed JSON blob before the payload
/// bytes. Kept simple (framed JSON + raw bytes) rather than a line-based text
/// protocol like the TV companion uses, since this one has to carry binary
/// image data too, not just short text commands.
/// </summary>
public class PushHeader
{
    public string Type { get; set; } = ""; // "text", "image", or "file"
    public string DeviceName { get; set; } = "";
    public string? Text { get; set; }
    public int ImageByteLength { get; set; }
    public string? FileName { get; set; }
    // long, not int - files aren't capped, so this needs to hold sizes well
    // past int.MaxValue (~2GB) for a large video.
    public long FileByteLength { get; set; }
}

public class ClipboardServer
{
    private readonly SettingsStore _settingsStore;
    private readonly ClipboardHistoryStore _historyStore;
    private readonly PcTransferManager _transferManager;
    private readonly Action<string, Action<bool>> _requestPairingApproval;

    // Set after construction (App.xaml.cs creates ClipboardWatcher after this,
    // since Watcher itself depends on TransferManager/HistoryStore which are
    // shared) - used to suppress the watcher's auto-send-back reaction to
    // this server's own clipboard writes. Null-safe: if never set, this
    // server just doesn't suppress anything, matching pre-fix behavior.
    public ClipboardWatcher? Watcher { get; set; }

    private TcpListener? _listener;
    private CancellationTokenSource? _cts;

    public ClipboardServer(
        SettingsStore settingsStore,
        ClipboardHistoryStore historyStore,
        PcTransferManager transferManager,
        Action<string, Action<bool>> requestPairingApproval)
    {
        _settingsStore = settingsStore;
        _historyStore = historyStore;
        _transferManager = transferManager;
        _requestPairingApproval = requestPairingApproval;
    }

    public void Start()
    {
        Stop();
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _listener = new TcpListener(IPAddress.Any, _settingsStore.Settings.Port);
        _listener.Start();

        Task.Run(() => AcceptLoop(token), token);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _listener?.Stop();
        _listener = null;
    }

    /// <summary>Restarts the listener on the currently configured port - call after a port change.</summary>
    public void Restart() => Start();

    private async Task AcceptLoop(CancellationToken token)
    {
        if (_listener == null) return;
        while (!token.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            Console.WriteLine($"[ClipboardServer] accepted connection from {client.Client.RemoteEndPoint}");
            _ = Task.Run(() => HandleClient(client, token), token);
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken token)
    {
        using (client)
        {
            var remoteIp = (client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "unknown";
            var stream = client.GetStream();

            try
            {
                var header = await ReadHeaderAsync(stream, token);
                if (header == null)
                {
                    Console.WriteLine("[ClipboardServer] header was null, closing");
                    return;
                }

                var isPaired = _settingsStore.Settings.PairedDevices.Any(d => d.IpAddress == remoteIp);
                Console.WriteLine($"[ClipboardServer] remoteIp={remoteIp} isPaired={isPaired} headerDeviceName='{header.DeviceName}'");
                if (!isPaired)
                {
                    var approved = await RequestApprovalAsync(header.DeviceName, remoteIp);
                    Console.WriteLine($"[ClipboardServer] approval result: {approved}");
                    if (!approved)
                    {
                        await WriteResponseAsync(stream, "denied", token);
                        return;
                    }
                    _settingsStore.Settings.PairedDevices.Add(new PairedDevice
                    {
                        DeviceName = header.DeviceName,
                        IpAddress = remoteIp,
                        LastSeenAt = DateTime.Now,
                    });
                    _settingsStore.Save();
                    Console.WriteLine("[ClipboardServer] saved new paired device");
                }
                else
                {
                    var device = _settingsStore.Settings.PairedDevices.First(d => d.IpAddress == remoteIp);
                    device.LastSeenAt = DateTime.Now;
                    // Only fill in the name if it's currently blank (e.g. a
                    // device paired during the earlier connection-reset bug,
                    // before deserialization was fixed) - never overwrite a
                    // name that's already set, whether it got set by a
                    // previous successful auto-fill or the user explicitly
                    // renamed it in the Devices tab. Without this guard, a
                    // user's custom rename would get silently reverted back
                    // to the phone's raw device name on the very next push.
                    if (string.IsNullOrWhiteSpace(device.DeviceName))
                    {
                        device.DeviceName = header.DeviceName;
                    }
                    _settingsStore.Save();
                    Console.WriteLine($"[ClipboardServer] existing device name is now '{device.DeviceName}'");
                }

                if (header.Type == "screenshot_request")
                {
                    // A genuinely different shape from every other push type
                    // here: phone asks, PC replies with binary image data
                    // instead of the usual "ok"/"denied" status string - so
                    // this bypasses ProcessPush (which only ever handles
                    // fire-and-forget pushes FROM the phone) and writes its
                    // own response directly.
                    await HandleScreenshotRequest(stream, token);
                    Console.WriteLine("[ClipboardServer] screenshot request handled");
                    await Task.Delay(300, token);
                    return;
                }

                await ProcessPush(header, remoteIp, stream, token);
                await WriteResponseAsync(stream, "ok", token);
                Console.WriteLine("[ClipboardServer] push handled successfully");

                // WriteAsync completing only means the bytes were handed to
                // the OS send buffer, not that the client has read them yet -
                // disposing the TcpClient immediately after can send a TCP
                // reset instead of a clean close if the phone hasn't finished
                // its read, which showed up as the phone reporting "Connection
                // reset" on every push even though the server had already
                // completed successfully. A short grace period before
                // disposal (the using block below) gives the read time to land.
                await Task.Delay(300, token);
            }
            catch (Exception ex)
            {
                // A single bad/dropped connection shouldn't affect the
                // listener or any other in-flight client.
                Console.WriteLine($"[ClipboardServer] client error: {ex}");
            }
        }
    }

    private async Task<PushHeader?> ReadHeaderAsync(NetworkStream stream, CancellationToken token)
    {
        var lengthBytes = new byte[4];
        if (!await ReadExactAsync(stream, lengthBytes, token)) return null;
        var headerLength = BitConverter.ToInt32(lengthBytes, 0);
        if (headerLength <= 0 || headerLength > 1_000_000) return null;

        var headerBytes = new byte[headerLength];
        if (!await ReadExactAsync(stream, headerBytes, token)) return null;

        var json = Encoding.UTF8.GetString(headerBytes);
        Console.WriteLine($"[ClipboardServer] received header: {json}");
        // System.Text.Json is case-SENSITIVE by default (unlike some other
        // JSON libraries) - the phone sends camelCase field names
        // (kotlinx.serialization's default), which never matched this
        // class's PascalCase properties without this option, so every
        // field silently came through as its default/empty value.
        var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
        return JsonSerializer.Deserialize<PushHeader>(json, options);
    }

    /// <summary>
    /// Prefers the paired device's own (possibly user-renamed) DeviceName
    /// over whatever the phone happened to send in this particular header -
    /// otherwise a rename in the Devices tab would only ever show up there,
    /// while History kept showing the raw name frozen at push-time forever.
    /// </summary>
    private string ResolveDisplayName(string remoteIp, string headerDeviceName)
    {
        var paired = _settingsStore.Settings.PairedDevices.FirstOrDefault(d => d.IpAddress == remoteIp);
        return !string.IsNullOrWhiteSpace(paired?.DeviceName) ? paired!.DeviceName : headerDeviceName;
    }

    private async Task ProcessPush(PushHeader header, string remoteIp, NetworkStream stream, CancellationToken token)
    {
        var displayName = ResolveDisplayName(remoteIp, header.DeviceName);

        if (header.Type == "text" && header.Text != null)
        {
            Watcher?.SuppressNextChange(TimeSpan.FromSeconds(2));
            Application.Current.Dispatcher.Invoke(() => Clipboard.SetText(header.Text));
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.Text,
                Direction = ClipboardItemDirection.Received,
                Text = header.Text,
                SourceDeviceName = displayName,
            });
        }
        else if (header.Type == "image" && header.ImageByteLength > 0)
        {
            var imageBytes = new byte[header.ImageByteLength];
            if (!await ReadExactAsync(stream, imageBytes, token)) return;

            var filePath = SaveImageToCache(imageBytes);
            Watcher?.SuppressNextChange(TimeSpan.FromSeconds(2));
            Application.Current.Dispatcher.Invoke(() => SetClipboardImage(imageBytes));
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.Image,
                Direction = ClipboardItemDirection.Received,
                ImageFilePath = filePath,
                SourceDeviceName = displayName,
            });
        }
        else if (header.Type == "file" && header.FileByteLength > 0 && !string.IsNullOrWhiteSpace(header.FileName))
        {
            if (!_settingsStore.Settings.ReceiveFilesFromPhone)
            {
                Console.WriteLine("[ClipboardServer] file received but ReceiveFilesFromPhone is off, dropping");
                // Still drain the payload bytes off the stream even though
                // it's being discarded - the phone is waiting on the "ok"
                // response after writing them, and leaving them unread would
                // desync the framing for anything else on this connection.
                await DrainAsync(stream, header.FileByteLength, token);
                return;
            }

            var savedPath = await ReceiveFileStreamedAsync(stream, header.FileName, header.FileByteLength, displayName, token);
            if (savedPath == null) return; // connection dropped mid-transfer (e.g. phone-side cancel)

            // Deliberately NOT put on the Windows clipboard (unlike text/image
            // pushes) - this is a file transfer, landing in the configured
            // folder is the whole point, and setting it on the clipboard too
            // was what caused ClipboardWatcher to see its own received file as
            // a "new" clipboard change and auto-send it straight back to the
            // phone (an echo loop). No clipboard write, no echo risk, and no
            // need to lean on SuppressNextChange for this path at all.
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.File,
                Direction = ClipboardItemDirection.Received,
                FileName = header.FileName,
                FilePath = savedPath,
                SourceDeviceName = displayName,
            });
        }
    }

    private const int StreamChunkSize = 64 * 1024;

    /// <summary>
    /// Reads the file payload straight to disk in fixed-size chunks instead
    /// of buffering the whole thing in a byte[] first - files aren't capped
    /// in size (could be a multi-GB video), so peak memory needs to stay
    /// constant regardless of file size. Returns null if the connection drops
    /// before all bytes arrive (phone-side cancel closes its socket, which
    /// surfaces here as a failed/short read).
    /// </summary>
    private async Task<string?> ReceiveFileStreamedAsync(NetworkStream stream, string fileName, long totalBytes, string sourceDeviceName, CancellationToken token)
    {
        var filePath = ResolveReceivedFilePath(fileName);
        var buffer = new byte[StreamChunkSize];
        var remaining = totalBytes;
        var received = 0L;
        var transfer = _transferManager.StartReceive(fileName, filePath, sourceDeviceName, totalBytes, out var cancelToken);
        using var linkedToken = CancellationTokenSource.CreateLinkedTokenSource(token, cancelToken);

        try
        {
            await using var fileStream = new FileStream(filePath, FileMode.Create, FileAccess.Write);
            while (remaining > 0)
            {
                var toRead = (int)Math.Min(buffer.Length, remaining);
                var read = await stream.ReadAsync(buffer.AsMemory(0, toRead), linkedToken.Token);
                if (read == 0) throw new IOException("Connection closed before all file bytes arrived");
                await fileStream.WriteAsync(buffer.AsMemory(0, read), linkedToken.Token);
                remaining -= read;
                received += read;
                _transferManager.ReportProgress(transfer, received);
            }
            _transferManager.Complete(transfer, PcTransferStatus.Succeeded);
            return filePath;
        }
        catch (OperationCanceledException) when (cancelToken.IsCancellationRequested)
        {
            Console.WriteLine("[ClipboardServer] file receive cancelled from PC side");
            _transferManager.Complete(transfer, PcTransferStatus.Cancelled);
            try { File.Delete(filePath); } catch { /* best effort cleanup of a partial file */ }
            return null;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardServer] file receive failed/cancelled: {ex.Message}");
            _transferManager.Complete(transfer, PcTransferStatus.Failed, ex.Message);
            try { File.Delete(filePath); } catch { /* best effort cleanup of a partial file */ }
            return null;
        }
    }

    private static async Task DrainAsync(NetworkStream stream, long totalBytes, CancellationToken token)
    {
        var buffer = new byte[StreamChunkSize];
        var remaining = totalBytes;
        while (remaining > 0)
        {
            var toRead = (int)Math.Min(buffer.Length, remaining);
            var read = await stream.ReadAsync(buffer.AsMemory(0, toRead), token);
            if (read == 0) return;
            remaining -= read;
        }
    }

    private static void SetClipboardImage(byte[] imageBytes)
    {
        using var memoryStream = new MemoryStream(imageBytes);
        var decoder = BitmapDecoder.Create(memoryStream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        var frame = decoder.Frames[0];

        // Clipboard.SetImage() (and WPF/Win32 clipboard access generally) can
        // fail with "OpenClipboard failed" if another process (or even
        // Windows itself, briefly) has the clipboard open at that exact
        // moment - a well-known Windows clipboard quirk, not specific to this
        // app. A short retry loop is the standard workaround.
        Exception? lastError = null;
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                Clipboard.SetImage(frame);
                Console.WriteLine("[ClipboardServer] Clipboard.SetImage succeeded");
                return;
            }
            catch (Exception ex)
            {
                lastError = ex;
                Console.WriteLine($"[ClipboardServer] Clipboard.SetImage attempt {attempt} failed: {ex.Message}");
                System.Threading.Thread.Sleep(100);
            }
        }
        Console.WriteLine($"[ClipboardServer] Clipboard.SetImage gave up: {lastError}");
    }

    private static string SaveImageToCache(byte[] imageBytes)
    {
        var cacheDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "PcCompanion", "history_images");
        Directory.CreateDirectory(cacheDir);
        var filePath = Path.Combine(cacheDir, $"{Guid.NewGuid()}.png");
        File.WriteAllBytes(filePath, imageBytes);
        return filePath;
    }

    private string ResolveReceivedFilePath(string fileName)
    {
        var folder = _settingsStore.Settings.ReceivedFilesFolder;
        if (string.IsNullOrWhiteSpace(folder))
        {
            folder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "TV File Bridge");
        }
        Directory.CreateDirectory(folder);

        // Avoid silently overwriting an existing file of the same name -
        // append a numeric suffix instead, same pattern as Windows Explorer's
        // own "file (1).ext" behavior for a save into an occupied name.
        var filePath = Path.Combine(folder, fileName);
        if (File.Exists(filePath))
        {
            var nameOnly = Path.GetFileNameWithoutExtension(fileName);
            var ext = Path.GetExtension(fileName);
            var counter = 1;
            do
            {
                filePath = Path.Combine(folder, $"{nameOnly} ({counter}){ext}");
                counter++;
            } while (File.Exists(filePath));
        }

        return filePath;
    }

    private Task<bool> RequestApprovalAsync(string deviceName, string ipAddress)
    {
        var tcs = new TaskCompletionSource<bool>();
        Application.Current.Dispatcher.Invoke(() =>
        {
            _requestPairingApproval($"{deviceName} ({ipAddress})", approved => tcs.SetResult(approved));
        });
        return tcs.Task;
    }

    private static async Task<bool> ReadExactAsync(NetworkStream stream, byte[] buffer, CancellationToken token)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset, buffer.Length - offset), token);
            if (read == 0) return false;
            offset += read;
        }
        return true;
    }

    private static async Task WriteResponseAsync(NetworkStream stream, string status, CancellationToken token)
    {
        var bytes = Encoding.UTF8.GetBytes(status);
        var lengthBytes = BitConverter.GetBytes(bytes.Length);
        await stream.WriteAsync(lengthBytes, token);
        await stream.WriteAsync(bytes, token);
    }

    /// <summary>
    /// Captures the primary display and sends it back on the same
    /// connection: status string (length-prefixed, same framing as every
    /// other response here), then - only when status is "ok" - a 4-byte
    /// image length followed by the raw PNG bytes. Primary display only
    /// (not the full virtual desktop spanning every monitor), matching a
    /// single-screen capture the same way the TV's own screenshot feature
    /// works.
    /// </summary>
    private async Task HandleScreenshotRequest(NetworkStream stream, CancellationToken token)
    {
        byte[]? pngBytes;
        try
        {
            pngBytes = CapturePrimaryScreenPng();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardServer] screenshot capture failed: {ex.Message}");
            await WriteResponseAsync(stream, $"error: {ex.Message}", token);
            return;
        }

        await WriteResponseAsync(stream, "ok", token);
        var lengthBytes = BitConverter.GetBytes(pngBytes.Length);
        await stream.WriteAsync(lengthBytes, token);
        await stream.WriteAsync(pngBytes, token);
    }

    private static byte[] CapturePrimaryScreenPng()
    {
        var bounds = System.Windows.Forms.Screen.PrimaryScreen!.Bounds;
        using var bitmap = new System.Drawing.Bitmap(bounds.Width, bounds.Height);
        using (var graphics = System.Drawing.Graphics.FromImage(bitmap))
        {
            graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
        }
        using var memoryStream = new MemoryStream();
        bitmap.Save(memoryStream, System.Drawing.Imaging.ImageFormat.Png);
        return memoryStream.ToArray();
    }
}
