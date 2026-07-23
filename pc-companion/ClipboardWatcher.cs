using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Application = System.Windows.Application;
using Clipboard = System.Windows.Clipboard;

namespace PcCompanion;

/// <summary>
/// Watches the Windows clipboard for changes and, when enabled, pushes new
/// text/image content to whichever paired phone is marked primary - the
/// reverse direction of ClipboardServer (phone -> PC), using the same wire
/// protocol so the phone's existing receiver can consume it unchanged.
///
/// WPF has no direct "clipboard changed" event; this hooks the Win32
/// AddClipboardFormatListener API via a hidden window's message loop
/// (HwndSourceHook), which is the standard way to observe clipboard changes
/// on Windows without polling.
/// </summary>
public class ClipboardWatcher : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;
    private const int PhoneReceiverPort = 58822;

    private readonly SettingsStore _settingsStore;
    private readonly PcTransferManager _transferManager;
    private readonly ClipboardHistoryStore _historyStore;
    private HwndSource? _hwndSource;
    private string? _lastSeenText;

    public ClipboardWatcher(SettingsStore settingsStore, PcTransferManager transferManager, ClipboardHistoryStore historyStore)
    {
        _settingsStore = settingsStore;
        _transferManager = transferManager;
        _historyStore = historyStore;
    }

    public void Start()
    {
        if (_hwndSource != null) return;

        var parameters = new HwndSourceParameters("ClipboardWatcherWindow")
        {
            Width = 0,
            Height = 0,
            WindowStyle = 0,
        };
        _hwndSource = new HwndSource(parameters);
        _hwndSource.AddHook(WndProc);
        var added = NativeMethods.AddClipboardFormatListener(_hwndSource.Handle);
        Console.WriteLine($"[ClipboardWatcher] Start: hwnd={_hwndSource.Handle}, AddClipboardFormatListener returned {added}");
    }

    public void Dispose()
    {
        if (_hwndSource == null) return;
        NativeMethods.RemoveClipboardFormatListener(_hwndSource.Handle);
        _hwndSource.RemoveHook(WndProc);
        _hwndSource.Dispose();
        _hwndSource = null;
    }

    // Set by ClipboardServer right before it writes something it just
    // received onto the Windows clipboard (SetClipboardImage/SetClipboardFile/
    // Clipboard.SetText) - without this, that write fires WM_CLIPBOARDUPDATE
    // like any other clipboard change, and this watcher would auto-send the
    // very thing it just received straight back to the phone: an infinite
    // echo (phone -> PC -> phone -> ...). A short time window rather than a
    // one-shot flag, since Windows can fire WM_CLIPBOARDUPDATE more than once
    // for a single SetClipboardImage/SetFileDropList call (observed in
    // practice - a retry loop or the OS's own internal clipboard-format
    // negotiation), so a flag cleared after the first message would still
    // miss the second one.
    private DateTime _suppressUntil = DateTime.MinValue;

    public void SuppressNextChange(TimeSpan window)
    {
        _suppressUntil = DateTime.UtcNow + window;
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_CLIPBOARDUPDATE)
        {
            if (DateTime.UtcNow < _suppressUntil)
            {
                Console.WriteLine("[ClipboardWatcher] WM_CLIPBOARDUPDATE received but suppressed (self-triggered)");
                return IntPtr.Zero;
            }
            Console.WriteLine("[ClipboardWatcher] WM_CLIPBOARDUPDATE received");
            OnClipboardChanged();
        }
        return IntPtr.Zero;
    }

    private void OnClipboardChanged()
    {
        var settings = _settingsStore.Settings;
        if (!settings.AutoSendImagesToPhone && !settings.AutoSendTextToPhone && !settings.AutoSendFilesToPhone)
        {
            Console.WriteLine("[ClipboardWatcher] all auto-send toggles are off, ignoring");
            return;
        }

        var primaryPhone = settings.PairedDevices.FirstOrDefault(d => d.IsPrimary);
        if (primaryPhone == null)
        {
            Console.WriteLine("[ClipboardWatcher] no primary phone set, ignoring");
            return;
        }

        try
        {
            var hasFileDrop = Clipboard.ContainsFileDropList();
            var hasImage = Clipboard.ContainsImage();
            var hasText = Clipboard.ContainsText();
            Console.WriteLine($"[ClipboardWatcher] ContainsFileDropList={hasFileDrop} ContainsImage={hasImage} ContainsText={hasText}");
            if (hasFileDrop)
            {
                var paths = Clipboard.GetFileDropList().Cast<string>().Where(File.Exists).ToList();
                if (paths.Count == 0)
                {
                    Console.WriteLine("[ClipboardWatcher] file drop list had no readable files");
                    return;
                }

                // Explorer's Ctrl+C on a file always produces a file-drop-list,
                // never raw clipboard image data - even for a .png/.jpg. Without
                // this check, copying a single image FILE was gated only by the
                // Files toggle, never the two image toggles below, which didn't
                // match what "images" settings mean to a user copying a picture
                // the normal way (via Explorer, not an in-app "copy image").
                if (paths.Count == 1 && IsImageFile(paths[0]))
                {
                    if (settings.SendImagesAsFileToPhone)
                    {
                        Console.WriteLine($"[ClipboardWatcher] copied file is an image, sending as file to {primaryPhone.DeviceName}");
                        _ = PushFileAsync(primaryPhone, paths[0]);
                        return;
                    }
                    if (settings.AutoSendImagesToPhone)
                    {
                        Console.WriteLine($"[ClipboardWatcher] copied file is an image, pushing to clipboard on {primaryPhone.DeviceName}");
                        _ = PushImageFileAsync(primaryPhone, paths[0]);
                        return;
                    }
                    Console.WriteLine("[ClipboardWatcher] both image auto-send toggles are off, skipping copied image file");
                    return;
                }

                if (!settings.AutoSendFilesToPhone)
                {
                    Console.WriteLine("[ClipboardWatcher] file auto-send is off, skipping");
                    return;
                }
                var totalBytes = paths.Sum(p => new FileInfo(p).Length);
                Console.WriteLine($"[ClipboardWatcher] pushing {paths.Count} file(s), {totalBytes} bytes total, to {primaryPhone.DeviceName}");
                foreach (var path in paths)
                {
                    _ = PushFileAsync(primaryPhone, path);
                }
            }
            else if (hasImage)
            {
                if (!settings.SendImagesAsFileToPhone && !settings.AutoSendImagesToPhone)
                {
                    Console.WriteLine("[ClipboardWatcher] both image auto-send toggles are off, skipping");
                    return;
                }
                var image = Clipboard.GetImage();
                if (image == null)
                {
                    Console.WriteLine("[ClipboardWatcher] GetImage returned null");
                    return;
                }
                var bytes = EncodeAsPng(image);

                if (settings.SendImagesAsFileToPhone)
                {
                    var tempPath = Path.Combine(Path.GetTempPath(), $"Clipboard Image {DateTime.Now:yyyy-MM-dd HH-mm-ss}.png");
                    File.WriteAllBytes(tempPath, bytes);
                    Console.WriteLine($"[ClipboardWatcher] encoded {bytes.Length} bytes, sending as file to {primaryPhone.DeviceName}");
                    _ = PushFileAsync(primaryPhone, tempPath, deleteAfterSend: true);
                    return;
                }

                Console.WriteLine($"[ClipboardWatcher] encoded {bytes.Length} bytes, pushing image to {primaryPhone.DeviceName}");
                _ = PushImageAsync(primaryPhone, bytes);
            }
            else if (hasText)
            {
                if (!settings.AutoSendTextToPhone)
                {
                    Console.WriteLine("[ClipboardWatcher] text auto-send is off, skipping");
                    return;
                }
                var text = Clipboard.GetText();
                // Clipboard change notifications can fire more than once for
                // the same content (e.g. some apps write multiple formats in
                // sequence) - skip re-sending identical text back to back.
                if (string.IsNullOrEmpty(text) || text == _lastSeenText)
                {
                    Console.WriteLine("[ClipboardWatcher] empty or duplicate text, skipping");
                    return;
                }
                _lastSeenText = text;
                Console.WriteLine($"[ClipboardWatcher] pushing text to {primaryPhone.DeviceName}");
                _ = PushTextAsync(primaryPhone, text);
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] exception reading clipboard: {ex}");
            // Clipboard access can throw if another process has it open at
            // the exact moment we read it - a transient, well-known Windows
            // quirk (same one ClipboardServer's SetImage retry works around).
            // Skipping this change is safe; the next one will be caught.
        }
    }

    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp",
    };

    private static bool IsImageFile(string path) => ImageExtensions.Contains(Path.GetExtension(path));

    /// <summary>Same wire shape as PushImageAsync, reading the payload from a file on disk instead of the clipboard's raw bitmap.</summary>
    private async Task PushImageFileAsync(PairedDevice device, string filePath)
    {
        try
        {
            var imageBytes = await File.ReadAllBytesAsync(filePath);
            await PushImageAsync(device, imageBytes);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] image file push failed: {ex.Message}");
        }
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

    private static byte[] EncodeAsPng(BitmapSource image)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    /// <summary>
    /// Sends a no-op "ping" push and waits for the phone's ack - used by the
    /// Reconnect action to test reachability without touching the phone's
    /// clipboard. ClipboardReceiverServer's type switch has no "ping" case,
    /// but its fallback still writes the "ok" response after the switch, so
    /// no phone-side change was needed for this to already work.
    /// </summary>
    public async Task<bool> PingAsync(PairedDevice device, int timeoutMs = 5000)
    {
        try
        {
            using var client = new TcpClient();
            var connectTask = client.ConnectAsync(device.IpAddress, PhoneReceiverPort);
            if (await Task.WhenAny(connectTask, Task.Delay(timeoutMs)) != connectTask) return false;
            await connectTask;

            var stream = client.GetStream();
            stream.ReadTimeout = timeoutMs;
            var header = JsonSerializer.Serialize(new { type = "ping", deviceName = _settingsStore.Settings.DeviceName });
            await WriteFramedAsync(stream, header);

            var lengthBytes = new byte[4];
            if (!await ReadExactAsync(stream, lengthBytes)) return false;
            var responseLength = BitConverter.ToInt32(lengthBytes, 0);
            if (responseLength <= 0 || responseLength > 1_000_000) return false;
            var responseBytes = new byte[responseLength];
            return await ReadExactAsync(stream, responseBytes);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] ping failed: {ex.Message}");
            return false;
        }
    }

    private static async Task<bool> ReadExactAsync(NetworkStream stream, byte[] buffer)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset, buffer.Length - offset));
            if (read == 0) return false;
            offset += read;
        }
        return true;
    }

    private async Task PushTextAsync(PairedDevice device, string text)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(device.IpAddress, PhoneReceiverPort);
            var stream = client.GetStream();

            var header = JsonSerializer.Serialize(new { type = "text", deviceName = _settingsStore.Settings.DeviceName, text });
            await WriteFramedAsync(stream, header);
            Console.WriteLine("[ClipboardWatcher] text push succeeded");
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.Text,
                Direction = ClipboardItemDirection.Sent,
                Text = text,
                SourceDeviceName = device.DeviceName,
            });
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] text push failed: {ex.Message}");
            // Best-effort: the phone may be unreachable (asleep, off Wi-Fi,
            // app process suspended) - there's no user-facing action to take
            // here beyond not crashing the watcher for the next clipboard change.
        }
    }

    private async Task PushImageAsync(PairedDevice device, byte[] imageBytes)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(device.IpAddress, PhoneReceiverPort);
            var stream = client.GetStream();

            var header = JsonSerializer.Serialize(new
            {
                type = "image",
                deviceName = _settingsStore.Settings.DeviceName,
                imageByteLength = imageBytes.Length,
            });
            await WriteFramedAsync(stream, header);
            await stream.WriteAsync(imageBytes);
            await stream.FlushAsync();
            Console.WriteLine("[ClipboardWatcher] image push succeeded");
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.Image,
                Direction = ClipboardItemDirection.Sent,
                ImageFilePath = SaveImageToCache(imageBytes),
                SourceDeviceName = device.DeviceName,
            });
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] image push failed: {ex.Message}");
            // Same best-effort handling as PushTextAsync.
        }
    }

    private const int FileStreamChunkSize = 64 * 1024;

    /// <summary>
    /// Streams the file straight from disk in fixed-size chunks instead of
    /// loading it into a byte[] first - files pushed this way aren't capped
    /// in size (could be a multi-GB video copied in Explorer), so this keeps
    /// peak memory constant regardless of file size, same reasoning as the
    /// phone-to-PC direction's PcFileTransferManager.
    /// </summary>
    private async Task PushFileAsync(PairedDevice device, string filePath, bool deleteAfterSend = false)
    {
        var fileName = Path.GetFileName(filePath);
        var fileLength = new FileInfo(filePath).Length;
        var transfer = _transferManager.StartSend(fileName, filePath, device.DeviceName, fileLength, out var cancellationToken);

        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(device.IpAddress, PhoneReceiverPort, cancellationToken);
            var stream = client.GetStream();

            var header = JsonSerializer.Serialize(new
            {
                type = "file",
                deviceName = _settingsStore.Settings.DeviceName,
                fileName,
                fileByteLength = fileLength,
            });
            await WriteFramedAsync(stream, header);

            await using (var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read))
            {
                var buffer = new byte[FileStreamChunkSize];
                long sent = 0;
                int read;
                while ((read = await fileStream.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    await stream.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    sent += read;
                    _transferManager.ReportProgress(transfer, sent);
                }
            }
            await stream.FlushAsync(cancellationToken);
            Console.WriteLine($"[ClipboardWatcher] file push succeeded: {fileName}");
            _transferManager.Complete(transfer, PcTransferStatus.Succeeded);
            _historyStore.Add(new ClipboardHistoryItem
            {
                Type = ClipboardItemType.File,
                Direction = ClipboardItemDirection.Sent,
                FileName = fileName,
                FilePath = filePath,
                SourceDeviceName = device.DeviceName,
            });
        }
        catch (OperationCanceledException)
        {
            Console.WriteLine($"[ClipboardWatcher] file push cancelled: {fileName}");
            _transferManager.Complete(transfer, PcTransferStatus.Cancelled);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ClipboardWatcher] file push failed: {ex.Message}");
            _transferManager.Complete(transfer, PcTransferStatus.Failed, ex.Message);
            // Best-effort otherwise, same as PushTextAsync/PushImageAsync -
            // the phone may simply be unreachable.
        }
        finally
        {
            if (deleteAfterSend)
            {
                try { File.Delete(filePath); } catch { /* best-effort cleanup of a scratch temp file */ }
            }
        }
    }

    private static async Task WriteFramedAsync(NetworkStream stream, string headerJson)
    {
        var headerBytes = Encoding.UTF8.GetBytes(headerJson);
        var lengthBytes = BitConverter.GetBytes(headerBytes.Length);
        await stream.WriteAsync(lengthBytes);
        await stream.WriteAsync(headerBytes);
        await stream.FlushAsync();
    }
}

internal static class NativeMethods
{
    [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
    public static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
    public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
}
