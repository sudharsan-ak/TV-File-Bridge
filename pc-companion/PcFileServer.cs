using System.IO;

namespace PcCompanion;

public class PcFile
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    public bool IsDirectory { get; set; }
    public long SizeBytes { get; set; }
    public long? ModifiedAt { get; set; } // epoch millis
    public long? TotalBytes { get; set; } // drive rows only: total capacity
}

/// <summary>
/// Filesystem-backed counterpart to TvAdbClient, serving the phone's requests
/// to browse this PC's own drives - no ADB involved, just System.IO. An empty
/// path means "list drives" (the phone's "This PC" level); any other path is
/// used as a literal absolute Windows path, same full-access trust model ADB
/// already gives the TV Files feature (no containment/root restriction).
/// </summary>
public static class PcFileServer
{
    public static List<PcFile> ListDrives()
    {
        return DriveInfo.GetDrives()
            .Where(d => d.DriveType == DriveType.Fixed && d.IsReady)
            .Select(d => new PcFile
            {
                Name = string.IsNullOrWhiteSpace(d.VolumeLabel) ? d.Name.TrimEnd('\\') : $"{d.VolumeLabel} ({d.Name.TrimEnd('\\')})",
                Path = d.RootDirectory.FullName,
                IsDirectory = true,
                SizeBytes = d.AvailableFreeSpace,
                TotalBytes = d.TotalSize,
            })
            .OrderBy(d => d.Path, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public static List<PcFile> ListDir(string path)
    {
        var dir = new DirectoryInfo(path);
        var entries = new List<PcFile>();

        // Individual entries can throw (junctions, permission-denied system
        // folders like "System Volume Information") - skip those rather than
        // failing the whole listing, same tolerance FileRepository's ls
        // parsing gets for free by only seeing what the shell successfully printed.
        foreach (var sub in SafeEnumerate(dir.EnumerateDirectories))
        {
            try
            {
                entries.Add(new PcFile { Name = sub.Name, Path = sub.FullName, IsDirectory = true, SizeBytes = 0, ModifiedAt = ToEpochMillis(sub.LastWriteTimeUtc) });
            }
            catch { /* inaccessible entry, skip */ }
        }
        foreach (var file in SafeEnumerate(dir.EnumerateFiles))
        {
            try
            {
                entries.Add(new PcFile { Name = file.Name, Path = file.FullName, IsDirectory = false, SizeBytes = file.Length, ModifiedAt = ToEpochMillis(file.LastWriteTimeUtc) });
            }
            catch { /* inaccessible entry, skip */ }
        }

        return entries
            .OrderByDescending(e => e.IsDirectory)
            .ThenBy(e => e.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static IEnumerable<T> SafeEnumerate<T>(Func<IEnumerable<T>> enumerate)
    {
        try { return enumerate(); }
        catch { return Enumerable.Empty<T>(); }
    }

    private static long ToEpochMillis(DateTime utc) => new DateTimeOffset(utc, TimeSpan.Zero).ToUnixTimeMilliseconds();

    public static string Rename(string path, string newName)
    {
        var isDir = Directory.Exists(path);
        var isFile = File.Exists(path);
        if (!isDir && !isFile) throw new FileNotFoundException("Not found", path);

        var parent = System.IO.Path.GetDirectoryName(path) ?? throw new InvalidOperationException("No parent directory");
        var newPath = System.IO.Path.Combine(parent, newName);

        if (isDir) Directory.Move(path, newPath);
        else File.Move(path, newPath);
        return newPath;
    }

    public static void Delete(string path)
    {
        if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        else if (File.Exists(path)) File.Delete(path);
        else throw new FileNotFoundException("Not found", path);
    }
}
