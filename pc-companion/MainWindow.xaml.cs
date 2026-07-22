using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using Application = System.Windows.Application;

namespace PcCompanion;

public partial class MainWindow : Window
{
    private App App => (App)Application.Current;

    public ObservableCollection<ClipboardHistoryItem> HistoryItems => App.HistoryStore.Items;
    public ObservableCollection<PairedDevice> PairedDevices { get; } = new();
    public ObservableCollection<PcTransfer> SentTransfers => App.TransferManager.SentTransfers;
    public ObservableCollection<PcTransfer> ReceivedTransfers => App.TransferManager.ReceivedTransfers;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;

        RefreshPairedDevices();

        DeviceNameBox.Text = App.SettingsStore.Settings.DeviceName;
        PortBox.Text = App.SettingsStore.Settings.Port.ToString();
        MaxHistoryBox.Text = App.SettingsStore.Settings.MaxHistoryItems.ToString();
        LaunchAtStartupCheck.IsChecked = App.SettingsStore.Settings.LaunchAtStartup;
        AutoSendImagesCheck.IsChecked = App.SettingsStore.Settings.AutoSendImagesToPhone;
        AutoSendTextCheck.IsChecked = App.SettingsStore.Settings.AutoSendTextToPhone;
        AutoSendFilesCheck.IsChecked = App.SettingsStore.Settings.AutoSendFilesToPhone;

        ReceiveFilesCheck.IsChecked = App.SettingsStore.Settings.ReceiveFilesFromPhone;
        ReceivedFilesFolderBox.Text = App.SettingsStore.Settings.ReceivedFilesFolder ?? "";
        UpdateReceiveFilesFolderVisibility();

        Activated += (_, _) => RefreshPairedDevices();
    }

    private void UpdateReceiveFilesFolderVisibility()
    {
        ReceiveFilesFolderPanel.Visibility = ReceiveFilesCheck.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnReceiveFilesCheckChanged(object sender, RoutedEventArgs e) => UpdateReceiveFilesFolderVisibility();

    private void OnBrowseReceivedFilesFolderClick(object sender, RoutedEventArgs e)
    {
        var dialog = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Choose where files received from your phone are saved",
            UseDescriptionForTitle = true,
        };
        if (!string.IsNullOrWhiteSpace(ReceivedFilesFolderBox.Text) && Directory.Exists(ReceivedFilesFolderBox.Text))
        {
            dialog.SelectedPath = ReceivedFilesFolderBox.Text;
        }
        if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            ReceivedFilesFolderBox.Text = dialog.SelectedPath;
        }
    }

    /// <summary>
    /// PairedDevices is a snapshot copy, not a live view over
    /// SettingsStore.Settings.PairedDevices - a background push (which can
    /// pair a new device or update an existing one's name) updates the
    /// latter directly, so without this the window kept showing whatever it
    /// looked like when first opened, including a device's name never
    /// refreshing after the first (buggy) pairing attempt. Re-synced on
    /// every window activation rather than needing a full event/notify
    /// pipeline from the server thread, since "reopen the window and glance
    /// at it" is the realistic usage pattern here.
    /// </summary>
    private void RefreshPairedDevices()
    {
        PairedDevices.Clear();
        foreach (var device in App.SettingsStore.Settings.PairedDevices) PairedDevices.Add(device);
    }

    private void OnClearAllClick(object sender, RoutedEventArgs e)
    {
        App.HistoryStore.Clear();
    }

    private void OnCancelTransferClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PcTransfer transfer) return;
        App.TransferManager.Cancel(transfer.Id);
    }

    private void OnClearSentTransfersClick(object sender, RoutedEventArgs e)
    {
        App.TransferManager.ClearSent();
    }

    private void OnClearReceivedTransfersClick(object sender, RoutedEventArgs e)
    {
        App.TransferManager.ClearReceived();
    }

    private void OnHistoryImageClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ClipboardHistoryItem item) return;
        if (item.ImageFilePath == null || !File.Exists(item.ImageFilePath)) return;
        new ImagePreviewWindow(item.ImageFilePath).Show();
    }

    private void OnRecopyClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ClipboardHistoryItem item) return;
        if (item.Type == ClipboardItemType.Text && item.Text != null)
        {
            System.Windows.Clipboard.SetText(item.Text);
        }
        else if (item.Type == ClipboardItemType.Image && item.ImageFilePath != null && File.Exists(item.ImageFilePath))
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(item.ImageFilePath);
            bitmap.EndInit();
            System.Windows.Clipboard.SetImage(bitmap);
        }
        else if (item.Type == ClipboardItemType.File && item.FilePath != null && File.Exists(item.FilePath))
        {
            var files = new System.Collections.Specialized.StringCollection();
            files.Add(item.FilePath);
            System.Windows.Clipboard.SetFileDropList(files);
        }
    }

    private void OnDeleteItemClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is ClipboardHistoryItem item) App.HistoryStore.Remove(item);
    }

    private void OnOpenFileClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ClipboardHistoryItem item) return;
        OpenFile(item.FilePath);
    }

    private void OnShowInFolderClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ClipboardHistoryItem item) return;
        ShowInFolder(item.FilePath);
    }

    private void OnOpenTransferFileClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PcTransfer transfer) return;
        OpenFile(transfer.FilePath);
    }

    private void OnShowTransferFileInFolderClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PcTransfer transfer) return;
        ShowInFolder(transfer.FilePath);
    }

    /// <summary>Hands off to whatever app Windows has associated with this file's extension - same as double-clicking it in Explorer.</summary>
    private static void OpenFile(string? filePath)
    {
        if (filePath == null || !File.Exists(filePath)) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(filePath) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            AppDialog.ShowInfo($"Couldn't open the file: {ex.Message}", "Open failed");
        }
    }

    /// <summary>Opens Explorer with the file pre-selected in its containing folder.</summary>
    private static void ShowInFolder(string? filePath)
    {
        if (filePath == null || !File.Exists(filePath)) return;
        try
        {
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{filePath}\"");
        }
        catch (Exception ex)
        {
            AppDialog.ShowInfo($"Couldn't open the folder: {ex.Message}", "Open failed");
        }
    }

    private void OnRemoveDeviceClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PairedDevice device) return;
        App.SettingsStore.Settings.PairedDevices.RemoveAll(d => d.Id == device.Id);
        App.SettingsStore.Save();
        PairedDevices.Remove(device);
    }

    private void OnRenameDeviceClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PairedDevice device) return;

        var dialog = new RenameDeviceWindow(device.DeviceName);
        if (dialog.ShowDialog() != true) return;

        var stored = App.SettingsStore.Settings.PairedDevices.FirstOrDefault(d => d.Id == device.Id);
        if (stored == null) return;
        stored.DeviceName = dialog.NewName;
        App.SettingsStore.Save();
        RefreshPairedDevices();
    }

    private void OnTogglePrimaryClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PairedDevice device) return;

        // Only one paired phone can be primary at a time - it's the single
        // target for this PC's own clipboard-watcher pushes.
        var makingPrimary = !device.IsPrimary;
        foreach (var stored in App.SettingsStore.Settings.PairedDevices)
        {
            stored.IsPrimary = makingPrimary && stored.Id == device.Id;
        }
        App.SettingsStore.Save();
        RefreshPairedDevices();
    }

    private void OnSaveSettingsClick(object sender, RoutedEventArgs e)
    {
        var settings = App.SettingsStore.Settings;

        if (!int.TryParse(PortBox.Text, out var port) || port is < 1 or > 65535)
        {
            AppDialog.ShowInfo("Port must be a number between 1 and 65535.", "Invalid port");
            return;
        }
        if (!int.TryParse(MaxHistoryBox.Text, out var maxHistory) || maxHistory < 1)
        {
            AppDialog.ShowInfo("Max history items must be a positive number.", "Invalid value");
            return;
        }

        var portChanged = settings.Port != port;

        settings.DeviceName = DeviceNameBox.Text.Trim() is { Length: > 0 } name ? name : Environment.MachineName;
        settings.Port = port;
        settings.MaxHistoryItems = maxHistory;
        settings.LaunchAtStartup = LaunchAtStartupCheck.IsChecked == true;
        settings.AutoSendImagesToPhone = AutoSendImagesCheck.IsChecked == true;
        settings.AutoSendTextToPhone = AutoSendTextCheck.IsChecked == true;
        settings.AutoSendFilesToPhone = AutoSendFilesCheck.IsChecked == true;
        settings.ReceiveFilesFromPhone = ReceiveFilesCheck.IsChecked == true;
        settings.ReceivedFilesFolder = string.IsNullOrWhiteSpace(ReceivedFilesFolderBox.Text) ? null : ReceivedFilesFolderBox.Text;
        App.SettingsStore.Save();

        StartupRegistration.Apply(settings.LaunchAtStartup);
        if (portChanged) App.Server.Restart();

        AppDialog.ShowInfo("Settings saved.");
    }
}
