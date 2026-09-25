using System.Windows;

namespace PcCompanion;

/// <summary>
/// Reconnects a saved TV whose IP address changed (e.g. DHCP reassigned it)
/// without having to Forget + re-add it from scratch - prefilled with the
/// old IP so the common case is just editing a couple of digits. Connects
/// right here (not just collecting text and letting the caller connect
/// after closing) so a bad IP/refused connection shows its error inline and
/// keeps the dialog open, instead of silently saving a host that doesn't work.
/// </summary>
public partial class ReconnectTvWindow : Window
{
    private readonly TvAdbClient _tvAdbClient;
    private readonly int _port;

    public string NewHost { get; private set; } = "";

    public ReconnectTvWindow(TvAdbClient tvAdbClient, string currentHost, int port)
    {
        InitializeComponent();
        _tvAdbClient = tvAdbClient;
        _port = port;
        HostBox.Text = currentHost;
        HostBox.Focus();
        HostBox.SelectAll();
    }

    private async void OnConnectClick(object sender, RoutedEventArgs e)
    {
        var host = HostBox.Text.Trim();
        if (host.Length == 0)
        {
            ShowError("Enter the TV's IP address.");
            return;
        }

        ConnectButton.IsEnabled = false;
        CancelButton.IsEnabled = false;
        ErrorText.Visibility = Visibility.Collapsed;

        var result = await _tvAdbClient.ConnectAsync(host, _port);

        ConnectButton.IsEnabled = true;
        CancelButton.IsEnabled = true;

        if (!result.IsSuccess)
        {
            ShowError($"Connection failed: {result.ErrorMessage}");
            return;
        }

        NewHost = host;
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
