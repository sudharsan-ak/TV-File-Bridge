using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PcCompanion;

public record DiscoveredDevice(string Host);

/// <summary>
/// C# port of the phone app's TvDiscovery.kt - same algorithm: sweep the
/// local /24 subnet with a short-timeout TCP connect probe on ADB's port
/// (5555), all 254 hosts in parallel so the scan finishes in a couple of
/// seconds rather than minutes. A plain TCP-connect probe, not a full ADB
/// handshake - good enough signal that something ADB-like is listening; the
/// real auth/connect happens when the user picks a result and hits Connect.
/// </summary>
public static class TvDiscovery
{
    private const int AdbPort = 5555;
    private const int PerHostTimeoutMs = 250;

    public static async Task<List<DiscoveredDevice>> ScanAsync()
    {
        var subnetPrefix = LocalSubnetPrefix();
        if (subnetPrefix == null) return new List<DiscoveredDevice>();

        var probes = Enumerable.Range(1, 254).Select(async lastOctet =>
        {
            var host = $"{subnetPrefix}.{lastOctet}";
            return await IsPortOpenAsync(host, AdbPort) ? host : null;
        });

        var results = await Task.WhenAll(probes);
        return results.Where(h => h != null).Select(h => new DiscoveredDevice(h!)).ToList();
    }

    private static async Task<bool> IsPortOpenAsync(string host, int port)
    {
        try
        {
            using var client = new TcpClient();
            var connectTask = client.ConnectAsync(host, port);
            var timeoutTask = Task.Delay(PerHostTimeoutMs);
            var completed = await Task.WhenAny(connectTask, timeoutTask);
            return completed == connectTask && client.Connected;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>First 3 octets of this PC's own LAN-facing IPv4 address (Wi-Fi or Ethernet, whichever is actually up), or null if none found.</summary>
    private static string? LocalSubnetPrefix()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

            foreach (var addr in nic.GetIPProperties().UnicastAddresses)
            {
                if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var bytes = addr.Address.GetAddressBytes();
                return $"{bytes[0]}.{bytes[1]}.{bytes[2]}";
            }
        }
        return null;
    }
}
