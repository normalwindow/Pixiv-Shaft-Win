using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace PixShaftWebAuth;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        var options = Options.Parse(args);
        if (options is null)
        {
            return 2;
        }

        Application.EnableVisualStyles();
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new AuthForm(options));
        return options.ExitCode;
    }
}

internal sealed class Options
{
    public required string StartUrl { get; init; }
    public required string ResultPath { get; init; }
    public required string UserDataDir { get; init; }
    public string UserAgent { get; init; } = DefaultUa;
    public string Verifier { get; init; } = "";
    public int ExitCode { get; set; } = 2;

    public const string DefaultUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    public static Options? Parse(string[] args)
    {
        string? start = null;
        string? result = null;
        string? userData = null;
        string? ua = null;
        string? verifier = null;
        for (var i = 0; i < args.Length; i++)
        {
            var key = args[i];
            var value = i + 1 < args.Length ? args[++i] : "";
            switch (key)
            {
                case "--start": start = value; break;
                case "--result": result = value; break;
                case "--user-data": userData = value; break;
                case "--ua": ua = value; break;
                case "--verifier": verifier = value; break;
            }
        }
        if (string.IsNullOrWhiteSpace(start) || string.IsNullOrWhiteSpace(result))
        {
            return null;
        }
        return new Options
        {
            StartUrl = start,
            ResultPath = result,
            UserDataDir = string.IsNullOrWhiteSpace(userData)
                ? Path.Combine(Path.GetTempPath(), "PixShaftWebAuth-" + Guid.NewGuid().ToString("N"))
                : userData,
            UserAgent = string.IsNullOrWhiteSpace(ua) ? DefaultUa : ua,
            Verifier = verifier ?? "",
        };
    }
}

internal sealed class AuthForm : Form
{
    private readonly Options _options;
    private readonly WebView2 _webView = new() { Dock = DockStyle.Fill };
    private bool _completed;

    public AuthForm(Options options)
    {
        _options = options;
        Text = "Pixiv 登录";
        Width = 480;
        Height = 800;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(_webView);
        Load += async (_, _) => await StartAsync();
        FormClosed += (_, _) =>
        {
            if (!_completed)
            {
                _options.ExitCode = 2;
            }
        };
    }

    private async Task StartAsync()
    {
        try
        {
            Directory.CreateDirectory(_options.UserDataDir);
            var env = await CoreWebView2Environment.CreateAsync(null, _options.UserDataDir);
            await _webView.EnsureCoreWebView2Async(env);
            var core = _webView.CoreWebView2;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.UserAgent = _options.UserAgent;
            core.NavigationStarting += (_, e) => Consider(e.Uri, () => e.Cancel = true);
            core.NewWindowRequested += (_, e) =>
            {
                if (IsCallback(e.Uri))
                {
                    e.Handled = true;
                    Complete(e.Uri);
                }
            };
            core.SourceChanged += (_, _) => Consider(core.Source, null);
            core.Navigate(_options.StartUrl);
        }
        catch (Exception ex)
        {
            File.WriteAllText(_options.ResultPath, "ERROR\t" + ex.Message);
            _options.ExitCode = 1;
            Close();
        }
    }

    private void Consider(string? uri, Action? cancel)
    {
        if (!IsCallback(uri))
        {
            return;
        }
        cancel?.Invoke();
        Complete(uri!);
    }

    private static bool IsCallback(string? uri)
    {
        if (string.IsNullOrWhiteSpace(uri))
        {
            return false;
        }
        var lower = uri.Trim().ToLowerInvariant();
        if (lower.Contains("/auth/pixiv/start") || lower.Contains("post-redirect") || lower.Contains("/web/v1/login"))
        {
            return false;
        }
        if (!lower.Contains("code="))
        {
            return false;
        }
        return lower.StartsWith("pixiv:") ||
               lower.StartsWith("shaft:") ||
               lower.StartsWith("intent:") ||
               lower.Contains("/web/v1/users/auth/pixiv/callback");
    }

    private void Complete(string uri)
    {
        if (_completed)
        {
            return;
        }
        _completed = true;
        Text = "正在交换令牌…";
        _ = FinishAsync(uri);
    }

    private async Task FinishAsync(string uri)
    {
        try
        {
            var code = PixivToken.ParseCode(uri)
                ?? throw new InvalidOperationException("回调没有 code");
            if (string.IsNullOrWhiteSpace(_options.Verifier))
            {
                throw new InvalidOperationException("缺少 PKCE verifier");
            }
            var json = await PixivToken.ExchangeAsync(code, _options.Verifier);
            File.WriteAllText(_options.ResultPath, "TOKEN\t" + json);
            _options.ExitCode = 0;
        }
        catch (Exception ex)
        {
            File.WriteAllText(_options.ResultPath, "CALLBACK\t" + uri + "\nERROR\t" + ex.Message);
            _options.ExitCode = 0;
        }
        if (IsHandleCreated)
        {
            BeginInvoke(Close);
        }
        else
        {
            Close();
        }
    }
}

internal static class PixivToken
{
    private const string ClientId = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    private const string ClientSecret = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    private const string Redirect = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback";
    private const string TokenUrl = "https://oauth.secure.pixiv.net/auth/token";
    private const string IosUa = "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)";
    private const string HashSecret = "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c";

    public static string? ParseCode(string uri)
    {
        var query = uri.Contains('?') ? uri[(uri.IndexOf('?') + 1)..] : "";
        query = query.Split('#')[0];
        foreach (var part in query.Split('&'))
        {
            var key = part.Split('=')[0];
            var value = part.Contains('=') ? part[(part.IndexOf('=') + 1)..] : "";
            if (key.Equals("code", StringComparison.OrdinalIgnoreCase) && value.Length > 0)
            {
                return Uri.UnescapeDataString(value);
            }
        }
        return null;
    }

    public static async Task<string> ExchangeAsync(string code, string verifier)
    {
        using var handler = new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(8),
            ConnectCallback = async (context, token) =>
            {
                var host = context.DnsEndPoint.Host;
                var port = context.DnsEndPoint.Port;
                IEnumerable<IPAddress> ips =
                    host.Equals("oauth.secure.pixiv.net", StringComparison.OrdinalIgnoreCase) ||
                    host.Equals("app-api.pixiv.net", StringComparison.OrdinalIgnoreCase)
                        ? new[] { IPAddress.Parse("104.18.42.239"), IPAddress.Parse("172.64.145.17") }
                        : (await Dns.GetHostAddressesAsync(host, token))
                            .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork);
                Exception? last = null;
                foreach (var ip in ips)
                {
                    var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                    try
                    {
                        await socket.ConnectAsync(new IPEndPoint(ip, port), token);
                        return new NetworkStream(socket, ownsSocket: true);
                    }
                    catch (Exception ex)
                    {
                        socket.Dispose();
                        last = ex;
                    }
                }
                throw last ?? new IOException("无法连接 " + host);
            },
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(20) };
        var time = DateTimeOffset.Now.ToString("yyyy-MM-dd'T'HH:mm:sszzz");
        var hash = Convert.ToHexString(MD5.HashData(Encoding.ASCII.GetBytes(time + HashSecret))).ToLowerInvariant();
        using var req = new HttpRequestMessage(HttpMethod.Post, TokenUrl)
        {
            Version = HttpVersion.Version20,
            VersionPolicy = HttpVersionPolicy.RequestVersionOrLower,
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = ClientId,
                ["client_secret"] = ClientSecret,
                ["grant_type"] = "authorization_code",
                ["code"] = code,
                ["code_verifier"] = verifier,
                ["redirect_uri"] = Redirect,
                ["include_policy"] = "true",
                ["get_secure_url"] = "true",
            }),
        };
        req.Headers.TryAddWithoutValidation("User-Agent", IosUa);
        req.Headers.TryAddWithoutValidation("App-OS", "ios");
        req.Headers.TryAddWithoutValidation("App-OS-Version", "26.5");
        req.Headers.TryAddWithoutValidation("App-Version", "8.6.10");
        req.Headers.TryAddWithoutValidation("X-Client-Time", time);
        req.Headers.TryAddWithoutValidation("X-Client-Hash", hash);
        using var res = await http.SendAsync(req);
        var raw = await res.Content.ReadAsStringAsync();
        if (!res.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"HTTP {(int)res.StatusCode}: {Trim(raw)}");
        }
        if (!raw.Contains("access_token", StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Token response missing tokens: " + Trim(raw));
        }
        return raw;
    }

    private static string Trim(string raw) => raw.Length <= 400 ? raw : raw[..400];
}
