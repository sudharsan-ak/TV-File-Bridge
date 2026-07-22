using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using Application = System.Windows.Application;

namespace PcCompanion;

/// <summary>
/// Bounded, observable clipboard history - newest first, trimmed to
/// SettingsStore's MaxHistoryItems on every add. Backing image files for
/// anything trimmed off the end are deleted too, so the on-disk cache
/// doesn't grow unbounded.
/// </summary>
public class ClipboardHistoryStore
{
    private readonly SettingsStore _settingsStore;

    public ObservableCollection<ClipboardHistoryItem> Items { get; } = new();

    public ClipboardHistoryStore(SettingsStore settingsStore)
    {
        _settingsStore = settingsStore;
    }

    public void Add(ClipboardHistoryItem item)
    {
        void Apply()
        {
            Items.Insert(0, item);
            var max = Math.Max(1, _settingsStore.Settings.MaxHistoryItems);
            while (Items.Count > max)
            {
                var removed = Items[^1];
                Items.RemoveAt(Items.Count - 1);
                DeleteBackingImageIfAny(removed);
            }
        }

        if (Application.Current?.Dispatcher.CheckAccess() == true) Apply();
        else Application.Current?.Dispatcher.Invoke(Apply);
    }

    public void Remove(ClipboardHistoryItem item)
    {
        Items.Remove(item);
        DeleteBackingImageIfAny(item);
    }

    public void Clear()
    {
        foreach (var item in Items) DeleteBackingImageIfAny(item);
        Items.Clear();
    }

    private static void DeleteBackingImageIfAny(ClipboardHistoryItem item)
    {
        if (item.ImageFilePath != null && File.Exists(item.ImageFilePath))
        {
            try { File.Delete(item.ImageFilePath); } catch { /* best-effort cleanup */ }
        }
    }
}
