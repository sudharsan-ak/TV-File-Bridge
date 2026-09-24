using System.ComponentModel;
using System.IO;

namespace PcCompanion;

public enum ApkQueueItemStatus { Pending, Installing, Installed, Failed }

/// <summary>One APK in the Install APK page's queue - one row from drop/pick through confirm through install.</summary>
public class ApkQueueItem : INotifyPropertyChanged
{
    public string LocalPath { get; }
    public string FileName { get; }
    public string SizeLabel { get; }

    public ApkQueueItem(string localPath)
    {
        LocalPath = localPath;
        FileName = Path.GetFileName(localPath);
        var bytes = new FileInfo(localPath).Length;
        SizeLabel = bytes >= 1024 * 1024
            ? $"{bytes / (1024.0 * 1024.0):0.#} MB"
            : $"{bytes / 1024.0:0.#} KB";
    }

    private ApkQueueItemStatus _status = ApkQueueItemStatus.Pending;
    public ApkQueueItemStatus Status
    {
        get => _status;
        set
        {
            _status = value;
            OnPropertyChanged(nameof(Status));
            OnPropertyChanged(nameof(IsPending));
            OnPropertyChanged(nameof(IsInstalling));
            OnPropertyChanged(nameof(IsInstalled));
            OnPropertyChanged(nameof(IsFailed));
        }
    }

    public bool IsPending => Status == ApkQueueItemStatus.Pending;
    public bool IsInstalling => Status == ApkQueueItemStatus.Installing;
    public bool IsInstalled => Status == ApkQueueItemStatus.Installed;
    public bool IsFailed => Status == ApkQueueItemStatus.Failed;

    private double _progress;
    public double Progress
    {
        get => _progress;
        set { _progress = value; OnPropertyChanged(nameof(Progress)); }
    }

    private string _statusText = "Waiting…";
    public string StatusText
    {
        get => _statusText;
        set { _statusText = value; OnPropertyChanged(nameof(StatusText)); }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
