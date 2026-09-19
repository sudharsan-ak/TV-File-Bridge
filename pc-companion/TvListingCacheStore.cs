using System.IO;
using System.Text.Json;

namespace PcCompanion;

/// <summary>
/// Persists the last-known file listing for each (TV host, folder path), so
/// revisiting a folder shows its contents (and, since thumbnails are already
/// disk-cached separately by TvAdbClient.GetThumbnailAsync, their images
/// too) instantly instead of blocking on a fresh `ls` over ADB first. A
/// background refresh (see MainWindow.RefreshTvFilesAsync) still runs after
/// showing the cached view, so the list self-corrects if anything changed on
/// the TV since the cache was written - this is a "show something now"
/// optimization, not a replacement for actually checking the TV.
///
/// Keyed by host, not by device id: the same host is the same physical TV
/// regardless of which SavedDevice entry currently points at it, and this
/// only needs to be right often enough to feel fast, not perfectly correct.
/// </summary>
public static class TvListingCacheStore
{
    private static readonly string CacheDir = Path.Combine(Path.GetTempPath(), "PcCompanionTvListingCache");

    private class CachedFile
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public bool IsDirectory { get; set; }
        public long SizeBytes { get; set; }
        public DateTime ModifiedAt { get; set; }
    }

    private static string CacheFilePath(string host, string folderPath)
    {
        Directory.CreateDirectory(CacheDir);
        var key = $"{host}:{folderPath}".GetHashCode().ToString("x8");
        return Path.Combine(CacheDir, $"{key}.json");
    }

    public static List<TvFile>? TryLoad(string host, string folderPath)
    {
        try
        {
            var path = CacheFilePath(host, folderPath);
            if (!File.Exists(path)) return null;
            var cached = JsonSerializer.Deserialize<List<CachedFile>>(File.ReadAllText(path));
            return cached?.Select(c => new TvFile
            {
                Name = c.Name,
                Path = c.Path,
                IsDirectory = c.IsDirectory,
                SizeBytes = c.SizeBytes,
                ModifiedAt = c.ModifiedAt,
            }).ToList();
        }
        catch
        {
            // Corrupt/partial cache file (e.g. app killed mid-write) - treat
            // as a cache miss rather than crashing the folder view.
            return null;
        }
    }

    public static void Save(string host, string folderPath, List<TvFile> files)
    {
        try
        {
            var cached = files.Select(f => new CachedFile
            {
                Name = f.Name,
                Path = f.Path,
                IsDirectory = f.IsDirectory,
                SizeBytes = f.SizeBytes,
                ModifiedAt = f.ModifiedAt,
            }).ToList();
            File.WriteAllText(CacheFilePath(host, folderPath), JsonSerializer.Serialize(cached));
        }
        catch
        {
            // Best-effort - a failed cache write shouldn't surface to the user, the next successful listing will just overwrite it.
        }
    }
}
