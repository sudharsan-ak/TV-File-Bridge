using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Application = System.Windows.Application;

namespace PcCompanion;

public partial class MainWindow : Window, System.ComponentModel.INotifyPropertyChanged
{
    private App App => (App)Application.Current;

    public ObservableCollection<ClipboardHistoryItem> HistoryItems => App.HistoryStore.Items;
    public ObservableCollection<PairedDevice> PairedDevices { get; } = new();
    public ObservableCollection<PcTransfer> SentTransfers => App.TransferManager.SentTransfers;
    public ObservableCollection<PcTransfer> ReceivedTransfers => App.TransferManager.ReceivedTransfers;
    public ObservableCollection<TvFile> TvFiles { get; } = new();
    public ObservableCollection<SavedTv> SavedTvs { get; } = new();
    public ObservableCollection<AdbDevice> PhoneScreenDevices { get; } = new();
    public ObservableCollection<TvBreadcrumb> TvBreadcrumbs { get; } = new();

    private bool _isTvSelecting;
    // Drives per-card checkbox visibility - hidden until Ctrl/Shift-click
    // starts a selection (mirrors the phone app's long-press-to-select),
    // rather than always showing a checkbox on every card.
    public bool IsTvSelecting
    {
        get => _isTvSelecting;
        set
        {
            if (_isTvSelecting == value) return;
            _isTvSelecting = value;
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(IsTvSelecting)));
        }
    }

    private string? _screenViewError;
    public string? ScreenViewError
    {
        get => _screenViewError;
        set
        {
            _screenViewError = value;
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(ScreenViewError)));
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(HasScreenViewError)));
        }
    }
    public bool HasScreenViewError => !string.IsNullOrEmpty(ScreenViewError);

    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;

    private const string TvRootPath = "/sdcard";
    private string _tvCurrentPath = TvRootPath;
    private bool _tvGridView = true;

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int pvAttribute, int cbAttribute);

    private const int DwmwaCaptionColor = 35;
    private const int DwmwaTextColor = 36;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;

        SourceInitialized += (_, _) => ApplyTitleBarTheme();
        RefreshPairedDevices();
        RefreshSavedTvs();

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

        Activated += (_, _) => { RefreshPairedDevices(); RefreshSavedTvs(); };

        var lastTv = App.SettingsStore.Settings.SavedTvs.LastOrDefault();
        if (lastTv != null)
        {
            TvHostBox.Text = lastTv.Host;
            TvPortBox.Text = lastTv.Port.ToString();
            _ = AutoConnectTvAsync(lastTv);
        }
    }

    /// <summary>
    /// Silently retries the last-used TV once at startup so the tab is ready
    /// to browse without re-entering the IP every time - a failure (TV off,
    /// moved networks) just leaves the connect form showing with the host
    /// pre-filled, not an error dialog, since this runs unprompted.
    /// </summary>
    private async Task AutoConnectTvAsync(SavedTv tv)
    {
        TvStatusText.Text = "Reconnecting to last TV…";
        var result = await App.TvAdbClient.ConnectAsync(tv.Host, tv.Port);
        if (!result.IsSuccess)
        {
            TvStatusText.Text = "";
            return;
        }
        TvStatusText.Text = "Connected.";
        TvConnectPanel.Visibility = Visibility.Collapsed;
        TvBrowserPanel.Visibility = Visibility.Visible;
        _tvCurrentPath = TvRootPath;
        ApplyTvViewMode();
        RefreshSavedTvs();
        await RefreshTvFilesAsync();
    }

    private void ApplyTitleBarTheme()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        // DWMWA_CAPTION_COLOR/DWMWA_TEXT_COLOR expect COLORREF (0x00BBGGRR),
        // not the ARGB order WPF brushes use - only supported on Windows 11
        // (build 22000+); the call is a no-op on older Windows, which is fine,
        // it just keeps the default title bar there.
        var captionColor = ToColorRef(0x0B, 0x16, 0x15);
        var textColor = ToColorRef(0xEE, 0xF7, 0xF4);
        DwmSetWindowAttribute(hwnd, DwmwaCaptionColor, ref captionColor, sizeof(int));
        DwmSetWindowAttribute(hwnd, DwmwaTextColor, ref textColor, sizeof(int));
    }

    private static int ToColorRef(byte r, byte g, byte b) => r | (g << 8) | (b << 16);

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

    private void RefreshSavedTvs()
    {
        SavedTvs.Clear();
        foreach (var tv in App.SettingsStore.Settings.SavedTvs)
        {
            tv.IsConnected = App.TvAdbClient.IsConnected && App.TvAdbClient.ConnectedHost == tv.Host && App.TvAdbClient.ConnectedPort == tv.Port;
            SavedTvs.Add(tv);
        }
    }

    private void OnTvSectionDisconnectClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not SavedTv tv) return;
        if (App.TvAdbClient.ConnectedHost != tv.Host || App.TvAdbClient.ConnectedPort != tv.Port) return;

        App.TvAdbClient.Disconnect();
        TvFiles.Clear();
        TvConnectPanel.Visibility = Visibility.Visible;
        TvBrowserPanel.Visibility = Visibility.Collapsed;
        TvStatusText.Text = "";
        RefreshSavedTvs();
    }

    private void OnTvSectionRenameClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not SavedTv tv) return;

        var dialog = new RenameDeviceWindow(tv.Name);
        if (dialog.ShowDialog() != true) return;

        var stored = App.SettingsStore.Settings.SavedTvs.FirstOrDefault(t => t.Host == tv.Host && t.Port == tv.Port);
        if (stored == null) return;
        stored.Name = dialog.NewName;
        App.SettingsStore.Save();
        RefreshSavedTvs();
    }

    private async void OnScreenViewClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not SavedTv tv) return;
        ScreenViewError = null;

        var result = await ScrcpyLauncher.LaunchAsync(tv.Host, tv.Port);
        if (result.IsSuccess) return;
        ScreenViewError = result.ErrorMessage;
    }

    private void OnPhoneScreenViewClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not AdbDevice device) return;
        ScreenViewError = null;

        var result = ScrcpyLauncher.LaunchBySerialAsync(device.Serial);
        if (result.IsSuccess) return;
        ScreenViewError = result.ErrorMessage;
    }

    private async void OnRefreshPhoneScreenDevicesClick(object sender, RoutedEventArgs e) => await RefreshPhoneScreenDevicesAsync();

    private async void OnNavScreenViewChecked(object sender, RoutedEventArgs e) => await RefreshPhoneScreenDevicesAsync();

    private async Task RefreshPhoneScreenDevicesAsync()
    {
        var devices = await AdbDeviceLister.ListAsync();
        // Exclude anything matching a saved TV's host:port serial - that's
        // the TV tab's list already, not a phone. Anything left over (USB or
        // wireless-debugging serial) is assumed to be a phone.
        var tvSerials = App.SettingsStore.Settings.SavedTvs.Select(tv => $"{tv.Host}:{tv.Port}").ToHashSet();
        var names = App.SettingsStore.Settings.ScreenViewPhoneNames;
        PhoneScreenDevices.Clear();
        foreach (var device in devices.Where(d => !tvSerials.Contains(d.Serial)))
        {
            if (names.TryGetValue(device.Model, out var customName)) device.CustomName = customName;
            PhoneScreenDevices.Add(device);
        }
    }

    private void OnRenamePhoneScreenDeviceClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not AdbDevice device) return;

        var dialog = new RenameDeviceWindow(device.DisplayName);
        if (dialog.ShowDialog() != true) return;

        device.CustomName = dialog.NewName;
        App.SettingsStore.Settings.ScreenViewPhoneNames[device.Model] = dialog.NewName;
        App.SettingsStore.Save();
    }

    private void OnTvSectionForgetClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not SavedTv tv) return;
        if (tv.IsConnected)
        {
            App.TvAdbClient.Disconnect();
            TvFiles.Clear();
            TvConnectPanel.Visibility = Visibility.Visible;
            TvBrowserPanel.Visibility = Visibility.Collapsed;
            TvStatusText.Text = "";
        }
        App.SettingsStore.Settings.SavedTvs.RemoveAll(t => t.Host == tv.Host && t.Port == tv.Port);
        App.SettingsStore.Save();
        RefreshSavedTvs();
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

    private async void OnTvConnectClick(object sender, RoutedEventArgs e)
    {
        var host = TvHostBox.Text.Trim();
        if (host.Length == 0)
        {
            AppDialog.ShowInfo("Enter the TV's IP address.", "Missing host");
            return;
        }
        if (!int.TryParse(TvPortBox.Text, out var port) || port is < 1 or > 65535)
        {
            AppDialog.ShowInfo("Port must be a number between 1 and 65535.", "Invalid port");
            return;
        }

        TvConnectButton.IsEnabled = false;
        TvStatusText.Text = "Connecting… accept the prompt on your TV if this is the first time.";
        var result = await App.TvAdbClient.ConnectAsync(host, port);
        TvConnectButton.IsEnabled = true;

        if (!result.IsSuccess)
        {
            TvStatusText.Text = $"Connection failed: {result.ErrorMessage}";
            return;
        }

        RememberTv(host, port);

        TvStatusText.Text = "Connected.";
        TvConnectPanel.Visibility = Visibility.Collapsed;
        TvBrowserPanel.Visibility = Visibility.Visible;
        _tvCurrentPath = TvRootPath;
        ApplyTvViewMode();
        RefreshSavedTvs();
        await RefreshTvFilesAsync();
    }

    /// <summary>
    /// Moves this TV to the end of SavedTvs (most-recently-used), so a
    /// silent reconnect at next startup targets whichever TV was actually
    /// used last, not just whichever was added first.
    /// </summary>
    private void RememberTv(string host, int port)
    {
        var settings = App.SettingsStore.Settings;
        settings.SavedTvs.RemoveAll(t => t.Host == host && t.Port == port);
        settings.SavedTvs.Add(new SavedTv { Name = host, Host = host, Port = port });
        App.SettingsStore.Save();
    }

    private void OnTvConnectNewClick(object sender, RoutedEventArgs e)
    {
        App.TvAdbClient.Disconnect();
        TvFiles.Clear();
        TvHostBox.Text = "";
        TvPortBox.Text = "5555";
        TvConnectPanel.Visibility = Visibility.Visible;
        TvBrowserPanel.Visibility = Visibility.Collapsed;
        TvStatusText.Text = "";
        RefreshSavedTvs();
    }

    private async Task RefreshTvFilesAsync()
    {
        UpdateTvBreadcrumbs();
        var result = await App.TvAdbClient.ListAsync(_tvCurrentPath);
        TvFiles.Clear();
        if (!result.IsSuccess)
        {
            AppDialog.ShowInfo($"Couldn't list files: {result.ErrorMessage}", "TV Files");
            return;
        }
        foreach (var file in result.Value!) TvFiles.Add(file);
        UpdateTvSelectionBar();
        _ = LoadThumbnailsAsync(result.Value!);
    }

    /// <summary>
    /// Splits the current path into clickable segments, "/sdcard" itself
    /// shown as "Internal storage" to match the phone app's Files screen
    /// wording rather than the raw path.
    /// </summary>
    private void UpdateTvBreadcrumbs()
    {
        TvBreadcrumbs.Clear();
        var relative = _tvCurrentPath[TvRootPath.Length..].Trim('/');
        TvBreadcrumbs.Add(new TvBreadcrumb { Label = "Internal storage", Path = TvRootPath });
        if (relative.Length == 0) return;
        var accum = TvRootPath;
        foreach (var segment in relative.Split('/'))
        {
            accum += "/" + segment;
            TvBreadcrumbs.Add(new TvBreadcrumb { Label = segment, Path = accum });
        }
    }

    private async void OnTvBreadcrumbClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not TvBreadcrumb crumb) return;
        if (crumb.Path == _tvCurrentPath) return;
        _tvCurrentPath = crumb.Path;
        ClearTvSelection();
        await RefreshTvFilesAsync();
    }

    /// <summary>
    /// Fire-and-forget per file, not awaited from RefreshTvFilesAsync - the
    /// grid renders immediately with icon placeholders, thumbnails pop in as
    /// each pull completes rather than blocking the whole listing on them.
    /// </summary>
    private async Task LoadThumbnailsAsync(List<TvFile> files)
    {
        foreach (var file in files.Where(f => f.IsImage))
        {
            var path = await App.TvAdbClient.GetThumbnailAsync(file);
            if (path != null) file.ThumbnailPath = path;
        }
    }

    /// <summary>
    /// Clicking "TV Files" in the drawer always returns to the root listing,
    /// same as tapping a bottom-nav tab on the phone resets its own stack -
    /// otherwise leaving mid-browse and coming back via another tab would
    /// silently resume wherever you left off instead of a predictable home.
    /// </summary>
    private async void OnNavTvFilesChecked(object sender, RoutedEventArgs e)
    {
        if (!App.TvAdbClient.IsConnected || _tvCurrentPath == TvRootPath) return;
        _tvCurrentPath = TvRootPath;
        ClearTvSelection();
        await RefreshTvFilesAsync();
    }

    private void OnTvToggleViewModeClick(object sender, RoutedEventArgs e)
    {
        _tvGridView = !_tvGridView;
        ApplyTvViewMode();
    }

    private void ApplyTvViewMode()
    {
        TvGridViewScroll.Visibility = _tvGridView ? Visibility.Visible : Visibility.Collapsed;
        TvListViewScroll.Visibility = _tvGridView ? Visibility.Collapsed : Visibility.Visible;
        TvViewModeButton.Content = _tvGridView ? "List view" : "Grid view";
    }

    /// <summary>
    /// Single click handler for both list and grid cards - navigates into a
    /// folder, opens a file, or (if anything is already selected) toggles
    /// this item's selection instead, matching the phone app's "tap while
    /// selecting" behavior.
    /// </summary>
    private async void OnTvFileClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_tvDidDrag) { _tvDidDrag = false; return; }
        if ((sender as FrameworkElement)?.Tag is not TvFile file) return;

        var modifierHeld = (System.Windows.Input.Keyboard.Modifiers & (System.Windows.Input.ModifierKeys.Control | System.Windows.Input.ModifierKeys.Shift)) != 0;
        if (modifierHeld || TvFiles.Any(f => f.IsSelected))
        {
            ToggleTvSelection(file);
            return;
        }
        if (file.IsDirectory)
        {
            _tvCurrentPath = file.Path;
            ClearTvSelection();
            await RefreshTvFilesAsync();
        }
        else
        {
            await OpenTvFileAsync(file);
        }
    }

    private System.Windows.Point? _tvDragStartPoint;
    private bool _tvDidDrag;
    private bool _tvOutgoingDrag;

    private void OnTvCardPreviewMouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        // Ignore mouse-downs that originated on a button inside the card
        // (⋮ menu, Open/Save in list view) - otherwise a slightly-imprecise
        // double-click on ⋮ can register as a tiny drag and misfire the
        // whole drag-out flow instead of just opening the menu twice.
        if (e.OriginalSource is DependencyObject d && FindAncestor<System.Windows.Controls.Primitives.ButtonBase>(d) != null) return;
        _tvDragStartPoint = e.GetPosition(null);
        _tvDidDrag = false;
    }

    private static T? FindAncestor<T>(DependencyObject? node) where T : DependencyObject
    {
        while (node != null)
        {
            if (node is T match) return match;
            node = System.Windows.Media.VisualTreeHelper.GetParent(node);
        }
        return null;
    }

    /// <summary>
    /// Starts an OS drag-out (to Explorer/Desktop/another app) once the mouse
    /// has moved past WPF's standard drag threshold while the button is held -
    /// folders aren't draggable, only files. DoDragDrop blocks until the drag
    /// completes; _tvDidDrag then suppresses the click that would otherwise
    /// fire on mouse-up (open/select), since a completed drag isn't a click.
    /// </summary>
    private async void OnTvCardPreviewMouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (e.LeftButton != System.Windows.Input.MouseButtonState.Pressed) return;
        if (_tvDragStartPoint is not { } start) return;
        if ((sender as FrameworkElement)?.Tag is not TvFile file || file.IsDirectory) return;

        var current = e.GetPosition(null);
        var delta = start - current;
        if (Math.Abs(delta.X) < SystemParameters.MinimumHorizontalDragDistance &&
            Math.Abs(delta.Y) < SystemParameters.MinimumVerticalDragDistance) return;

        _tvDragStartPoint = null;
        _tvDidDrag = true;

        var localPath = await App.TvAdbClient.GetOrPullLocalFileAsync(file);
        if (localPath == null) return;

        _tvOutgoingDrag = true;
        try
        {
            var data = new System.Windows.DataObject(System.Windows.DataFormats.FileDrop, new[] { localPath });
            System.Windows.DragDrop.DoDragDrop((DependencyObject)sender, data, System.Windows.DragDropEffects.Copy);
        }
        finally
        {
            _tvOutgoingDrag = false;
        }
    }

    private void OnTvCheckboxClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        e.Handled = true;
        if ((sender as FrameworkElement)?.Tag is not TvFile file) return;
        ToggleTvSelection(file);
    }

    private void ToggleTvSelection(TvFile file)
    {
        file.IsSelected = !file.IsSelected;
        UpdateTvSelectionBar();
    }

    private void ClearTvSelection()
    {
        foreach (var file in TvFiles) file.IsSelected = false;
        UpdateTvSelectionBar();
    }

    private void UpdateTvSelectionBar()
    {
        var count = TvFiles.Count(f => f.IsSelected);
        TvSelectionBar.Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
        TvSelectionCountText.Text = count == 1 ? "1 selected" : $"{count} selected";
        IsTvSelecting = count > 0;
    }

    private void OnTvClearSelectionClick(object sender, RoutedEventArgs e) => ClearTvSelection();

    private async void OnTvBulkDownloadClick(object sender, RoutedEventArgs e)
    {
        var files = TvFiles.Where(f => f.IsSelected && !f.IsDirectory).ToList();
        ClearTvSelection();
        if (files.Count == 0) return;

        var dialog = new System.Windows.Forms.FolderBrowserDialog { Description = "Choose where to save the selected files" };
        if (dialog.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;

        var failures = 0;
        foreach (var file in files)
        {
            var localPath = Path.Combine(dialog.SelectedPath, file.Name);
            var result = await App.TvAdbClient.PullAsync(file.Path, localPath);
            if (!result.IsSuccess) failures++;
        }
        if (failures > 0) AppDialog.ShowInfo($"{failures} of {files.Count} file(s) couldn't be downloaded.", "TV Files");
    }

    private async void OnTvBulkDeleteClick(object sender, RoutedEventArgs e)
    {
        var files = TvFiles.Where(f => f.IsSelected).ToList();
        ClearTvSelection();
        if (files.Count == 0) return;

        var confirm = AppDialog.ShowConfirm(
            "This permanently deletes them from the TV. This can't be undone.",
            files.Count == 1 ? "Delete 1 item?" : $"Delete {files.Count} items?");
        if (!confirm) return;

        var failures = 0;
        foreach (var file in files)
        {
            var result = await App.TvAdbClient.DeleteAsync(file.Path);
            if (!result.IsSuccess) failures++;
        }
        await RefreshTvFilesAsync();
        if (failures > 0) AppDialog.ShowInfo($"{failures} of {files.Count} item(s) couldn't be deleted.", "TV Files");
    }

    private async void OnTvUpClick(object sender, RoutedEventArgs e)
    {
        if (_tvCurrentPath == TvRootPath) return;
        var parent = _tvCurrentPath.TrimEnd('/');
        var lastSlash = parent.LastIndexOf('/');
        _tvCurrentPath = lastSlash <= 0 ? TvRootPath : parent[..lastSlash];
        if (_tvCurrentPath.Length < TvRootPath.Length) _tvCurrentPath = TvRootPath;
        ClearTvSelection();
        await RefreshTvFilesAsync();
    }

    private static readonly string TvOpenCacheDir = Path.Combine(Path.GetTempPath(), "PcCompanionTvOpen");

    private async Task OpenTvFileAsync(TvFile file)
    {
        Directory.CreateDirectory(TvOpenCacheDir);
        var localPath = Path.Combine(TvOpenCacheDir, file.Name);
        var result = await App.TvAdbClient.PullAsync(file.Path, localPath);
        if (!result.IsSuccess)
        {
            AppDialog.ShowInfo($"Couldn't open the file: {result.ErrorMessage}", "TV Files");
            return;
        }
        OpenFile(localPath);
    }

    private async void OnTvDownloadClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not TvFile file || file.IsDirectory) return;

        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = file.Name };
        if (dialog.ShowDialog() != true) return;

        var result = await App.TvAdbClient.PullAsync(file.Path, dialog.FileName);
        if (!result.IsSuccess)
        {
            AppDialog.ShowInfo($"Download failed: {result.ErrorMessage}", "TV Files");
        }
    }

    /// <summary>
    /// Small context menu built in code rather than XAML ContextMenu on the
    /// button - lets Rename/Delete be assembled with the same click handlers
    /// as the rest of this file instead of duplicating logic in XAML.
    /// </summary>
    private void OnTvMenuClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not TvFile file) return;
        var button = (FrameworkElement)sender;

        var menuItemStyle = (Style)FindResource(typeof(System.Windows.Controls.MenuItem));
        var menu = new System.Windows.Controls.ContextMenu
        {
            Style = (Style)FindResource(typeof(System.Windows.Controls.ContextMenu)),
            PlacementTarget = button,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom,
        };
        if (!file.IsDirectory)
        {
            var open = new System.Windows.Controls.MenuItem { Header = "Open", Style = menuItemStyle };
            open.Click += async (_, _) => await OpenTvFileAsync(file);
            menu.Items.Add(open);

            var save = new System.Windows.Controls.MenuItem { Header = "Save as…", Style = menuItemStyle };
            save.Click += async (_, _) =>
            {
                var dialog = new Microsoft.Win32.SaveFileDialog { FileName = file.Name };
                if (dialog.ShowDialog() != true) return;
                var result = await App.TvAdbClient.PullAsync(file.Path, dialog.FileName);
                if (!result.IsSuccess) AppDialog.ShowInfo($"Download failed: {result.ErrorMessage}", "TV Files");
            };
            menu.Items.Add(save);
        }

        var rename = new System.Windows.Controls.MenuItem { Header = "Rename", Style = menuItemStyle };
        rename.Click += async (_, _) => await RenameTvFileAsync(file);
        menu.Items.Add(rename);

        var delete = new System.Windows.Controls.MenuItem
        {
            Header = "Delete",
            Style = menuItemStyle,
            Foreground = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#FF6B5E")),
        };
        delete.Click += async (_, _) => await DeleteTvFileAsync(file);
        menu.Items.Add(delete);

        button.ContextMenu = menu;
        menu.IsOpen = true;
    }

    private async Task RenameTvFileAsync(TvFile file)
    {
        var dialog = new RenameDeviceWindow(file.Name);
        if (dialog.ShowDialog() != true) return;
        var result = await App.TvAdbClient.RenameAsync(file.Path, dialog.NewName);
        if (!result.IsSuccess)
        {
            AppDialog.ShowInfo($"Rename failed: {result.ErrorMessage}", "TV Files");
            return;
        }
        await RefreshTvFilesAsync();
    }

    private async Task DeleteTvFileAsync(TvFile file)
    {
        var confirm = AppDialog.ShowConfirm("This permanently deletes it from the TV. This can't be undone.", "Delete this item?");
        if (!confirm) return;
        var result = await App.TvAdbClient.DeleteAsync(file.Path);
        if (!result.IsSuccess)
        {
            AppDialog.ShowInfo($"Delete failed: {result.ErrorMessage}", "TV Files");
            return;
        }
        await RefreshTvFilesAsync();
    }

    private void OnTvUploadClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true };
        if (dialog.ShowDialog() != true) return;
        _ = ConfirmAndUploadTvFilesAsync(dialog.FileNames);
    }

    private void OnTvDragEnter(object sender, System.Windows.DragEventArgs e)
    {
        // A card being dragged OUT of this same window also tunnels through
        // this drop target (it's the ancestor of the card being dragged) -
        // without this guard, moving the dragged card a few pixels shows the
        // "drop to upload" overlay over its own source window.
        if (_tvOutgoingDrag) return;
        if (!e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop)) return;
        TvDropOverlay.Visibility = Visibility.Visible;
    }

    private void OnTvDragLeave(object sender, System.Windows.DragEventArgs e)
    {
        TvDropOverlay.Visibility = Visibility.Collapsed;
    }

    private async void OnTvDrop(object sender, System.Windows.DragEventArgs e)
    {
        TvDropOverlay.Visibility = Visibility.Collapsed;
        if (_tvOutgoingDrag) return;
        if (!e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop)) return;
        if (e.Data.GetData(System.Windows.DataFormats.FileDrop) is not string[] paths) return;
        await ConfirmAndUploadTvFilesAsync(paths);
    }

    private async Task ConfirmAndUploadTvFilesAsync(string[] localPaths)
    {
        var files = localPaths.Where(File.Exists).ToArray();
        if (files.Length == 0) return;

        var message = files.Length == 1
            ? $"Upload \"{Path.GetFileName(files[0])}\" to {_tvCurrentPath} on the TV?"
            : $"Upload {files.Length} files to {_tvCurrentPath} on the TV?";
        var confirm = AppDialog.ShowConfirm(message, "Upload to TV?");
        if (!confirm) return;

        var failures = 0;
        foreach (var localPath in files)
        {
            var result = await App.TvAdbClient.PushAsync(localPath, _tvCurrentPath);
            if (!result.IsSuccess) failures++;
        }
        await RefreshTvFilesAsync();
        if (failures > 0) AppDialog.ShowInfo($"{failures} of {files.Length} file(s) couldn't be uploaded.", "TV Files");
    }
}
