using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace PcCompanion;

public class ClipboardItemTypeToVisibilityConverter : IValueConverter
{
    public ClipboardItemType ShowFor { get; set; }

    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
    {
        return value is ClipboardItemType type && type == ShowFor ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is true ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

/// <summary>Visible when the bound int count is &gt; 0 (or == 0 with ConverterParameter="Invert") - used for the Install APK phone picker's "has results" vs "empty" panels.</summary>
public class CountToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
    {
        var count = value is int i ? i : 0;
        var hasItems = count > 0;
        if (string.Equals(parameter as string, "Invert", StringComparison.OrdinalIgnoreCase)) hasItems = !hasItems;
        return hasItems ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

/// <summary>
/// Fill for the per-card multi-select checkbox circle - solid teal once
/// selected, transparent otherwise. Previously this was a fixed XAML brush
/// (a 35%-alpha black wash in the grid view, plain SurfaceBrush in the list
/// view) with the checkmark drawn in that same SurfaceBrush color, so the
/// checkmark only ever stood out by accident depending on what was showing
/// through underneath (e.g. a photo thumbnail) rather than reliably being
/// visible on every selected card.
/// </summary>
public class BoolToSelectedCircleBackgroundConverter : IValueConverter
{
    private static readonly System.Windows.Media.SolidColorBrush SelectedBrush =
        new((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#37C6B0"));
    private static readonly System.Windows.Media.SolidColorBrush UnselectedBrush =
        new((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#590B1615"));

    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is true ? SelectedBrush : UnselectedBrush;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is true ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class PrimaryButtonLabelConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is true ? "Unset primary" : "Set primary";

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class DirectionLabelConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is ClipboardItemDirection.Sent ? "to " : "from ";

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class TransferStatusToVisibilityConverter : IValueConverter
{
    public PcTransferStatus ShowFor { get; set; }

    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
        => value is PcTransferStatus status && status == ShowFor ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public class ImagePathToBitmapConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string path || string.IsNullOrEmpty(path) || !File.Exists(path)) return null;
        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(path);
            bitmap.EndInit();
            return bitmap;
        }
        catch
        {
            return null;
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
