using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using Application = System.Windows.Application;

namespace PcCompanion;

public enum PcTransferStatus { InProgress, Succeeded, Failed, Cancelled }

public class PcTransfer : INotifyPropertyChanged
{
    public string Id { get; init; } = Guid.NewGuid().ToString();
    public string FileName { get; init; } = "";
    // Full path on this PC - for Sent, wherever the user originally copied it
    // from in Explorer; for Received, wherever it was saved (the configured
    // receive folder, or the Downloads fallback). Used for "Open"/"Show in
    // folder" actions in the Transfers tab.
    public string? FilePath { get; init; }
    // Other party's device name - the phone this was sent to, or received from,
    // depending on which collection (SentTransfers/ReceivedTransfers) it's in.
    public string OtherDeviceName { get; init; } = "";
    public long SizeBytes { get; init; }
    public DateTime StartedAt { get; init; } = DateTime.Now;

    private PcTransferStatus _status = PcTransferStatus.InProgress;
    public PcTransferStatus Status
    {
        get => _status;
        set { _status = value; OnPropertyChanged(nameof(Status)); OnPropertyChanged(nameof(ProgressFraction)); }
    }

    private long _progressBytes;
    public long ProgressBytes
    {
        get => _progressBytes;
        set { _progressBytes = value; OnPropertyChanged(nameof(ProgressBytes)); OnPropertyChanged(nameof(ProgressFraction)); }
    }

    private string? _errorMessage;
    public string? ErrorMessage
    {
        get => _errorMessage;
        set { _errorMessage = value; OnPropertyChanged(nameof(ErrorMessage)); }
    }

    public double ProgressFraction => SizeBytes > 0 ? Math.Clamp((double)ProgressBytes / SizeBytes, 0, 1) : 0;

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>
/// Tracks file transfers with live progress and cancellation, in both
/// directions: SentTransfers (PC -> phone, ClipboardWatcher's Explorer-copy
/// auto-send) and ReceivedTransfers (phone -> PC, ClipboardServer's incoming
/// file handling). Kept as two independent collections in one manager rather
/// than a single list filtered by direction, since Sent and Received each
/// need their own Clear all that doesn't touch the other - a plain filtered
/// view would make "clear this filtered subset" awkward and error-prone.
/// Separate from ClipboardHistoryStore, which keeps its own full combined
/// record (both directions, all content types) for the History tab - that
/// tab's Clear all is independent of these two.
/// </summary>
public class PcTransferManager
{
    public ObservableCollection<PcTransfer> SentTransfers { get; } = new();
    public ObservableCollection<PcTransfer> ReceivedTransfers { get; } = new();

    private readonly Dictionary<string, CancellationTokenSource> _cancellationSources = new();

    public PcTransfer StartSend(string fileName, string filePath, string targetDeviceName, long sizeBytes, out CancellationToken token) =>
        Start(SentTransfers, fileName, filePath, targetDeviceName, sizeBytes, out token);

    public PcTransfer StartReceive(string fileName, string filePath, string sourceDeviceName, long sizeBytes, out CancellationToken token) =>
        Start(ReceivedTransfers, fileName, filePath, sourceDeviceName, sizeBytes, out token);

    private PcTransfer Start(ObservableCollection<PcTransfer> collection, string fileName, string filePath, string otherDeviceName, long sizeBytes, out CancellationToken token)
    {
        var transfer = new PcTransfer
        {
            FileName = fileName,
            FilePath = filePath,
            OtherDeviceName = otherDeviceName,
            SizeBytes = sizeBytes,
        };

        var cts = new CancellationTokenSource();
        _cancellationSources[transfer.Id] = cts;
        token = cts.Token;

        Application.Current.Dispatcher.Invoke(() => collection.Insert(0, transfer));
        return transfer;
    }

    public void ReportProgress(PcTransfer transfer, long bytesTransferred)
    {
        Application.Current.Dispatcher.Invoke(() => transfer.ProgressBytes = bytesTransferred);
    }

    public void Complete(PcTransfer transfer, PcTransferStatus status, string? errorMessage = null)
    {
        _cancellationSources.Remove(transfer.Id);
        Application.Current.Dispatcher.Invoke(() =>
        {
            transfer.Status = status;
            transfer.ErrorMessage = errorMessage;
            if (status == PcTransferStatus.Succeeded) transfer.ProgressBytes = transfer.SizeBytes;
        });
    }

    public void Cancel(string transferId)
    {
        if (_cancellationSources.TryGetValue(transferId, out var cts)) cts.Cancel();
    }

    public void ClearSent() => ClearCompleted(SentTransfers);
    public void ClearReceived() => ClearCompleted(ReceivedTransfers);

    private static void ClearCompleted(ObservableCollection<PcTransfer> collection)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            for (var i = collection.Count - 1; i >= 0; i--)
            {
                if (collection[i].Status != PcTransferStatus.InProgress) collection.RemoveAt(i);
            }
        });
    }
}
