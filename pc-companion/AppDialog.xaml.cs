using System.Windows;

namespace PcCompanion;

/// <summary>
/// Dark-themed replacement for the stock MessageBox, which renders with the
/// OS's native chrome and can't be restyled - it was the one remaining
/// visibly "old Windows" element in an otherwise custom-themed app.
/// Supports both an info/OK dialog and a Yes/No confirmation, covering every
/// MessageBox.Show call site this app had.
/// </summary>
public partial class AppDialog : Window
{
    private AppDialog(string title, string message, bool showCancel, string primaryLabel, string secondaryLabel)
    {
        InitializeComponent();
        Title = title;
        TitleText.Text = title;
        MessageText.Text = message;
        PrimaryButton.Content = primaryLabel;
        if (showCancel)
        {
            SecondaryButton.Content = secondaryLabel;
            SecondaryButton.Visibility = Visibility.Visible;
        }
    }

    private void OnPrimaryClick(object sender, RoutedEventArgs e) => DialogResult = true;
    private void OnSecondaryClick(object sender, RoutedEventArgs e) => DialogResult = false;

    public static void ShowInfo(string message, string title = "TV File Bridge - Clipboard")
    {
        new AppDialog(title, message, showCancel: false, primaryLabel: "OK", secondaryLabel: "").ShowDialog();
    }

    public static bool ShowConfirm(string message, string title, string confirmLabel = "Yes", string cancelLabel = "Cancel")
    {
        var dialog = new AppDialog(title, message, showCancel: true, primaryLabel: confirmLabel, secondaryLabel: cancelLabel);
        return dialog.ShowDialog() == true;
    }
}
