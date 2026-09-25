using System.Windows;

namespace PcCompanion;

/// <summary>
/// Add TV dialog - scans the LAN for hosts with ADB's port (5555) open
/// (TvDiscovery, a C# port of the phone app's TvDiscovery.kt), same as the
/// phone app's own "Add TV" flow. Picking a scan result just prefills the
/// Host/Port fields (matching the phone's behavior) rather than connecting
/// immediately, so the name can still be set/edited before committing.
/// Connects right here on Add (like ReconnectTvWindow) so a bad IP shows
/// its error inline instead of saving a device that doesn't actually work.
/// </summary>
public partial class AddTvWindow : Window
{
    private readonly TvAdbClient _tvAdbClient;

    public string TvName { get; private set; } = "";
    public string TvHost { get; private set; } = "";
    public int TvPort { get; private set; }

    public AddTvWindow(TvAdbClient tvAdbClient)
    {
        InitializeComponent();
        _tvAdbClient = tvAdbClient;
        Loaded += async (_, _) => await ScanAsync();
    }

    private async void OnRescanClick(object sender, RoutedEventArgs e) => await ScanAsync();

    private async Task ScanAsync()
    {
        RescanButton.IsEnabled = false;
        ScanStatusText.Text = "Scanning your network for devices with ADB open…";
        ResultsList.ItemsSource = null;

        var results = await TvDiscovery.ScanAsync();

        RescanButton.IsEnabled = true;
        if (results.Count == 0)
        {
            ScanStatusText.Text = "No devices found. Make sure the TV is on, ADB debugging is enabled, and enter its IP manually below.";
        }
        else
        {
            ScanStatusText.Text = $"{results.Count} device(s) found - tap one to fill it in below, or enter an IP manually.";
            ResultsList.ItemsSource = results;
        }
    }

    private void OnResultClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not DiscoveredDevice device) return;
        HostBox.Text = device.Host;
        PortBox.Text = "5555";
    }

    private async void OnAddClick(object sender, RoutedEventArgs e)
    {
        var name = NameBox.Text.Trim();
        var host = HostBox.Text.Trim();
        if (name.Length == 0)
        {
            ShowError("Enter a name for this TV.");
            return;
        }
        if (host.Length == 0)
        {
            ShowError("Enter the TV's IP address.");
            return;
        }
        if (!int.TryParse(PortBox.Text, out var port) || port is < 1 or > 65535)
        {
            ShowError("Port must be a number between 1 and 65535.");
            return;
        }

        AddButton.IsEnabled = false;
        CancelButton.IsEnabled = false;
        ErrorText.Visibility = Visibility.Collapsed;

        var result = await _tvAdbClient.ConnectAsync(host, port);

        AddButton.IsEnabled = true;
        CancelButton.IsEnabled = true;

        if (!result.IsSuccess)
        {
            ShowError($"Connection failed: {result.ErrorMessage}");
            return;
        }

        TvName = name;
        TvHost = host;
        TvPort = port;
        DialogResult = true;
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
