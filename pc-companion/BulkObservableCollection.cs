using System.Collections.ObjectModel;
using System.Collections.Specialized;

namespace PcCompanion;

/// <summary>
/// ObservableCollection with one added method, ReplaceAll, for swapping the
/// entire contents in a single CollectionChanged(Reset) notification instead
/// of a Clear (one Remove-all notification) followed by N individual Add
/// notifications. Bound ItemsControls re-run layout on every notification,
/// so replacing ~20-30 files the plain way was ~20-30 incremental layout
/// passes for a screen that only ever needed to render once - this is what
/// TvFiles (MainWindow) uses for a full listing refresh (see
/// MainWindow.ApplyTvFileList), while Add/Move/Clear are still used
/// everywhere an incremental, animated-feeling update is wanted instead.
/// </summary>
public class BulkObservableCollection<T> : ObservableCollection<T>
{
    public void ReplaceAll(IEnumerable<T> items)
    {
        Items.Clear();
        foreach (var item in items) Items.Add(item);
        OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(Count)));
        OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs("Item[]"));
        OnCollectionChanged(new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Reset));
    }
}
