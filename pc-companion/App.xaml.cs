using System.Threading;
using System.Windows;
using Application = System.Windows.Application;

namespace PcCompanion;

public partial class App : Application
{
    // Both named kernel objects, scoped per-user (Local\) - the mutex detects
    // whether an instance is already running, the event lets a second launch
    // tell that instance to show its window instead of booting a whole new
    // process (new TCP listener, new ADB client, full WPF startup) just to
    // end up activating the same window.
    private const string SingleInstanceMutexName = @"Local\PcCompanion.SingleInstance";
    private const string ShowWindowEventName = @"Local\PcCompanion.ShowWindow";

    private Mutex? _singleInstanceMutex;
    private EventWaitHandle? _showWindowEvent;

    public SettingsStore SettingsStore { get; private set; } = null!;
    public ClipboardHistoryStore HistoryStore { get; private set; } = null!;
    public ClipboardServer Server { get; private set; } = null!;
    public ClipboardWatcher Watcher { get; private set; } = null!;
    public PcTransferManager TransferManager { get; private set; } = null!;
    public TvAdbClient TvAdbClient { get; } = new();
    private TrayIconManager _trayIcon = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _singleInstanceMutex = new Mutex(initiallyOwned: true, SingleInstanceMutexName, out var createdNew);
        _showWindowEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowWindowEventName);
        if (!createdNew)
        {
            // Another instance already owns the mutex - ask it to show its
            // window instead of standing up a second full app instance.
            _showWindowEvent.Set();
            Shutdown();
            return;
        }

        var thread = new Thread(WatchForShowWindowSignal) { IsBackground = true };
        thread.Start();

        SettingsStore = new SettingsStore();
        HistoryStore = new ClipboardHistoryStore(SettingsStore);
        TransferManager = new PcTransferManager();
        Server = new ClipboardServer(SettingsStore, HistoryStore, TransferManager, RequestPairingApproval);
        Server.Start();

        Watcher = new ClipboardWatcher(SettingsStore, TransferManager, HistoryStore);
        Watcher.Start();
        Server.Watcher = Watcher;

        StartupRegistration.Apply(SettingsStore.Settings.LaunchAtStartup);

        _trayIcon = new TrayIconManager(this);
        _trayIcon.Show();

        OpenMainWindow();
    }

    private void WatchForShowWindowSignal()
    {
        while (_showWindowEvent!.WaitOne())
        {
            Dispatcher.Invoke(OpenMainWindow);
        }
    }

    private void RequestPairingApproval(string deviceLabel, Action<bool> onDecision)
    {
        var window = new PairingPromptWindow(deviceLabel);
        var approved = window.ShowDialog() == true;
        onDecision(approved);
    }

    public void OpenMainWindow()
    {
        var existing = Windows.OfType<MainWindow>().FirstOrDefault();
        if (existing != null)
        {
            existing.Activate();
            existing.WindowState = WindowState.Normal;
            return;
        }
        new MainWindow().Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Server?.Stop();
        Watcher?.Dispose();
        _trayIcon?.Dispose();
        _singleInstanceMutex?.Dispose();
        base.OnExit(e);
    }
}
