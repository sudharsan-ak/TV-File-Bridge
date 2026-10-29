using System.Net.Http;
using System.Text;

namespace PcCompanion;

public enum SonyControlResult { Done, NeedsPairing, Failed }

/// <summary>
/// Power on/off/restart through the Bravia's own IP control API, reached
/// over the TV's real LAN host (unlike Haven - a launcher running ON the TV
/// itself, which reaches the same API over 127.0.0.1). C# port of the phone
/// app's SonyIrccWaker/SonyAuthStore: same actRegister/PIN handshake, same
/// cookie-per-host persistence, same system/IRCC endpoints - a completely
/// separate registration though, each client (this PC, the phone app) pairs
/// with the TV independently and gets its own entry under the TV's
/// Settings > Remote device settings.
///
/// Wake uses the IRCC-IP SOAP endpoint (works even fully asleep, when the TV
/// can't show a PIN prompt at all); power-off and restart use the newer JSON
/// system service (setPowerStatus/requestReboot) - both need the TV already
/// on and reachable, same session/cookie covers all three.
/// </summary>
public class SonyControl
{
    private const string IrccPowerCode = "AAAAAQAAAAEAAAAVAw==";
    private const string RegisterBody = """{"method":"actRegister","id":1,"version":"1.0","params":[{"clientid":"pccompanion:1","nickname":"PC Companion"},[{"clientid":"pccompanion:1","value":"yes","nickname":"PC Companion","function":"WOL"}]]}""";

    private readonly SettingsStore _settingsStore;
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(6) };

    public SonyControl(SettingsStore settingsStore) => _settingsStore = settingsStore;

    public bool IsPaired(string host) => _settingsStore.Settings.SonyAuthCookies.ContainsKey(host);

    /// <summary>Step 1: call with no PIN. Returns NeedsPairing if the TV is now showing a PIN.</summary>
    public Task<SonyControlResult> StartPairingAsync(string host) => RegisterAsync(host, pin: null);

    /// <summary>Step 2: call with the PIN shown on the TV screen to complete pairing and store the session cookie.</summary>
    public Task<SonyControlResult> SubmitPinAsync(string host, string pin) => RegisterAsync(host, pin);

    public Task<SonyControlResult> WakeAsync(string host) => SendIrccAsync(host, IrccPowerCode);

    public Task<SonyControlResult> PowerOffAsync(string host) =>
        SendSystemCommandAsync(host, """{"method":"setPowerStatus","id":1,"params":[{"status":false}],"version":"1.0"}""");

    public Task<SonyControlResult> RestartAsync(string host) =>
        SendSystemCommandAsync(host, """{"method":"requestReboot","id":1,"params":[],"version":"1.0"}""");

    private async Task<SonyControlResult> RegisterAsync(string host, string? pin)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, $"http://{host}/sony/accessControl")
            {
                Content = new StringContent(RegisterBody, Encoding.UTF8, "application/json"),
            };
            if (pin != null)
            {
                // Sony takes the on-screen PIN as the password of HTTP Basic auth, with no username.
                var credentials = Convert.ToBase64String(Encoding.UTF8.GetBytes($":{pin}"));
                request.Headers.Add("Authorization", $"Basic {credentials}");
            }

            using var response = await Http.SendAsync(request);
            if (response.StatusCode == System.Net.HttpStatusCode.OK)
            {
                var cookie = response.Headers.TryGetValues("Set-Cookie", out var values)
                    ? values.FirstOrDefault()?.Split(';')[0]
                    : null;
                if (cookie == null) return SonyControlResult.Failed;
                _settingsStore.Settings.SonyAuthCookies[host] = cookie;
                _settingsStore.Save();
                return SonyControlResult.Done;
            }
            if (response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)
            {
                return SonyControlResult.NeedsPairing;
            }
            return SonyControlResult.Failed;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[SonyControl] register({host}) failed: {ex.Message}");
            return SonyControlResult.Failed;
        }
    }

    private async Task<SonyControlResult> SendIrccAsync(string host, string irccCode)
    {
        var soapBody = $"""
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
            <IRCCCode>{irccCode}</IRCCCode>
            </u:X_SendIRCC>
            </s:Body>
            </s:Envelope>
            """;
        return await PostAsync(host, "IRCC", soapBody, "text/xml", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
    }

    private async Task<SonyControlResult> SendSystemCommandAsync(string host, string jsonBody) =>
        await PostAsync(host, "system", jsonBody, "application/json", soapAction: null);

    private async Task<SonyControlResult> PostAsync(string host, string service, string body, string contentType, string? soapAction)
    {
        try
        {
            _settingsStore.Settings.SonyAuthCookies.TryGetValue(host, out var cookie);

            using var request = new HttpRequestMessage(HttpMethod.Post, $"http://{host}/sony/{service}")
            {
                Content = new StringContent(body, Encoding.UTF8, contentType),
            };
            if (soapAction != null) request.Headers.Add("SOAPACTION", soapAction);
            if (cookie != null) request.Headers.Add("Cookie", cookie);

            using var response = await Http.SendAsync(request);
            if (response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)
            {
                return SonyControlResult.NeedsPairing;
            }
            if (!response.IsSuccessStatusCode) return SonyControlResult.Failed;

            // Sony reports some refusals as HTTP 200 with an "error" field in the body.
            var responseText = await response.Content.ReadAsStringAsync();
            if (responseText.Contains("\"error\"")) return SonyControlResult.Failed;

            return SonyControlResult.Done;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[SonyControl] {service} command to {host} failed: {ex.Message}");
            return SonyControlResult.Failed;
        }
    }
}
