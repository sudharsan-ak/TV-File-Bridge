using System.Windows;

namespace PcCompanion;

public partial class PairingPromptWindow : Window
{
    public PairingPromptWindow(string deviceLabel)
    {
        InitializeComponent();
        DeviceLabelText.Text = deviceLabel;
    }

    private void OnAllowClick(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
    }

    private void OnDenyClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
