using System.Windows;

namespace PcCompanion;

/// <summary>
/// Groups the TV's three Sony IP control lifecycle actions (Wake/Power
/// off/Restart) behind one dialog instead of three separate buttons on the
/// TV card - they all share one pairing/cookie, so this also makes that
/// relationship obvious to the user. Mirrors the phone app's equivalent
/// collapsed dialog in AppScaffold.kt.
/// </summary>
public partial class TvPowerWindow : Window
{
    private readonly SonyControl _sonyControl;
    private readonly string _host;
    private bool _awaitingPin;

    public TvPowerWindow(SonyControl sonyControl, string host)
    {
        InitializeComponent();
        _sonyControl = sonyControl;
        _host = host;
    }

    private async void OnWakeClick(object sender, RoutedEventArgs e) => await RunActionAsync("Waking…", () => _sonyControl.WakeAsync(_host));
    private async void OnPowerOffClick(object sender, RoutedEventArgs e) => await RunActionAsync("Turning off…", () => _sonyControl.PowerOffAsync(_host));
    private async void OnRestartClick(object sender, RoutedEventArgs e) => await RunActionAsync("Restarting…", () => _sonyControl.RestartAsync(_host));

    private async System.Threading.Tasks.Task RunActionAsync(string label, Func<System.Threading.Tasks.Task<SonyControlResult>> action)
    {
        SetActionsEnabled(false);
        StatusText.Text = label;
        var result = await action();
        StatusText.Text = result switch
        {
            SonyControlResult.Done => "Done.",
            SonyControlResult.NeedsPairing => "Not paired yet - use Pair below first.",
            _ => "Failed - make sure the TV is reachable.",
        };
        SetActionsEnabled(true);
    }

    private void SetActionsEnabled(bool enabled) => ActionsPanel.IsEnabled = enabled;

    private void OnPairClick(object sender, RoutedEventArgs e)
    {
        ActionsPanel.Visibility = Visibility.Collapsed;
        PinPanel.Visibility = Visibility.Visible;
        _awaitingPin = false;
        PinLabel.Visibility = Visibility.Collapsed;
        PinBox.Visibility = Visibility.Collapsed;
        PinErrorText.Visibility = Visibility.Collapsed;
        PinActionButton.Content = "Start";
    }

    private async void OnPinActionClick(object sender, RoutedEventArgs e)
    {
        PinActionButton.IsEnabled = false;
        PinErrorText.Visibility = Visibility.Collapsed;

        var result = _awaitingPin
            ? await _sonyControl.SubmitPinAsync(_host, PinBox.Text.Trim())
            : await _sonyControl.StartPairingAsync(_host);

        PinActionButton.IsEnabled = true;

        switch (result)
        {
            case SonyControlResult.NeedsPairing:
                _awaitingPin = true;
                PinLabel.Visibility = Visibility.Visible;
                PinBox.Visibility = Visibility.Visible;
                PinBox.Focus();
                PinActionButton.Content = "Submit";
                if (PinBox.Text.Length > 0)
                {
                    PinErrorText.Text = "Incorrect PIN, try again.";
                    PinErrorText.Visibility = Visibility.Visible;
                }
                break;
            case SonyControlResult.Done:
                PinPanel.Visibility = Visibility.Collapsed;
                ActionsPanel.Visibility = Visibility.Visible;
                StatusText.Text = "Paired. Wake/Power off/Restart should all work now.";
                break;
            case SonyControlResult.Failed:
                PinErrorText.Text = "Couldn't reach the TV. Make sure it's on and on the same network.";
                PinErrorText.Visibility = Visibility.Visible;
                break;
        }
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
}
