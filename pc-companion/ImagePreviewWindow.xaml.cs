using System.Windows;
using System.Windows.Media.Imaging;

namespace PcCompanion;

public partial class ImagePreviewWindow : Window
{
    public ImagePreviewWindow(string imageFilePath)
    {
        InitializeComponent();
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.UriSource = new Uri(imageFilePath);
        bitmap.EndInit();
        PreviewImage.Source = bitmap;
    }
}
