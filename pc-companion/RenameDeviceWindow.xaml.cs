using System.Windows;

namespace PcCompanion;

public partial class RenameDeviceWindow : Window
{
    public string NewName { get; private set; } = "";

    public RenameDeviceWindow(string currentName)
    {
        InitializeComponent();
        NameBox.Text = currentName;
        NameBox.Focus();
        NameBox.SelectAll();
    }

    private void OnSaveClick(object sender, RoutedEventArgs e)
    {
        NewName = NameBox.Text.Trim();
        if (NewName.Length == 0) return;
        DialogResult = true;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
