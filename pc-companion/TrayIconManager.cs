using System.Windows.Forms;

namespace PcCompanion;

/// <summary>
/// Owns the system tray icon and its right-click menu. WPF has no built-in
/// tray icon type, so this uses WinForms' NotifyIcon (see UseWindowsForms in
/// the csproj) purely for this - no other WinForms UI is used anywhere else.
/// </summary>
public class TrayIconManager : IDisposable
{
    private readonly NotifyIcon _notifyIcon;
    private readonly App _app;

    public TrayIconManager(App app)
    {
        _app = app;
        _notifyIcon = new NotifyIcon
        {
            Icon = new System.Drawing.Icon(
                System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico")),
            Text = "TV File Bridge - Clipboard",
            Visible = true,
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => _app.OpenMainWindow());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => QuitApp());
        _notifyIcon.ContextMenuStrip = menu;

        _notifyIcon.DoubleClick += (_, _) => _app.OpenMainWindow();
    }

    public void Show() => _notifyIcon.Visible = true;

    private void QuitApp()
    {
        var confirmed = AppDialog.ShowConfirm(
            "Stop the clipboard bridge? Your phone won't be able to push items to this PC until it's running again.",
            "Quit TV File Bridge - Clipboard",
            confirmLabel: "Quit");
        if (confirmed)
        {
            _app.Shutdown();
        }
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
    }
}
