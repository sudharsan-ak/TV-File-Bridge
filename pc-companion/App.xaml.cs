using System.Windows;
using Application = System.Windows.Application;

namespace PcCompanion;

public partial class App : Application
{
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
        Server.Stop();
        Watcher.Dispose();
        _trayIcon.Dispose();
        base.OnExit(e);
    }
}
