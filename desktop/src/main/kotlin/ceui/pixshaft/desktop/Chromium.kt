package ceui.pixshaft.desktop

import ceui.pixshaft.shared.net.PixivClientIdentity
import ceui.pixshaft.shared.net.PixivDns
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Chromium (Chrome / Edge) is the only engine on this machine that can
 * reach Pixiv: system DNS is poisoned, TCP/TLS to Cloudflare is reset,
 * JavaFX WebKit hangs on "加载中". Chrome uses QUIC and host-resolver-rules.
 *
 * Isolated `--user-data-dir` + Android Chrome UA + Client Hints override
 * so `/auth/pixiv/start` does not see a desktop Windows browser.
 *
 * Attach strategy: start on about:blank, enable CDP on a live renderer,
 * then Page.navigate. Do not pass the login URL or --disable-web-security
 * on the command line — those crash Edge's renderer (CDP -32000).
 * --origin-to-force-quic-on is required: TCP/TLS to Cloudflare is RST
 * on this machine (ERR_CONNECTION_RESET). Safe once the blank page is live.
 */
object Chromium {
    val ANDROID_UA: String = PixivClientIdentity.ANDROID_CHROME_UA

    fun hostResolverRules(): String {
        val maps = buildList {
            for (host in PixivDns.API_HOSTS) {
                add("MAP $host ${PixivDns.CF_IP_PRIMARY}")
            }
            add("MAP i.pximg.net ${PixivDns.FALLBACK_IMAGE_IPS.first()}")
            add("MAP s.pximg.net ${PixivDns.FALLBACK_IMAGE_IPS.first()}")
        }
        return maps.joinToString(", ")
    }

    fun originToForceQuicOn(): String =
        PixivDns.API_HOSTS.joinToString(",") { "$it:443" }

    /** Edge dumps component CRXs, ML models, Wallet, telemetry into --user-data-dir. */
    internal val PROFILE_JUNK_NAMES = listOf(
        "component_crx_cache",
        "ProvenanceData",
        "ProvenanceDataTensors",
        "WidevineCdm",
        "Edge Wallet",
        "Edge Shopping",
        "Edge Sidebar",
        "Edge Entity Extraction",
        "EdgeLanguageDetectionModel",
        "Speech Recognition",
        "Subresource Filter",
        "SmartScreen",
        "hyphen-data",
        "ZxcvbnData",
        "GrShaderCache",
        "ShaderCache",
        "GraphiteDawnCache",
        "BrowserMetrics",
        "BrowserMetrics-spare.pma",
        "Crashpad",
        "Safe Browsing",
        "Well Known Domains",
        "Autofill",
        "PKIMetadata",
        "MEIPreload",
        "Crowd Deny",
        "FileTypePolicies",
        "OptimizationHints",
        "OnDeviceHeadSuggestModel",
        "SSLErrorAssistant",
        "Subresource Filter",
    )

    /**
     * Old builds kept full Edge profiles under %APPDATA%/PixShaft/chromium-*.
     * Tokens live in session.json; those dirs are safe to delete.
     * Helper sessions now live under cache/chromium-net/s-... so a leftover
     * SingletonLock cannot kill the next DevTools launch.
     */
    fun purgeLegacyProfiles() {
        AppPaths.deleteQuietly(AppPaths.root.resolve("chromium-net"))
        AppPaths.deleteQuietly(AppPaths.root.resolve("chromium-login"))
        sweepStaleProfiles(AppPaths.chromiumNetDir())
        sweepStaleProfiles(AppPaths.chromiumLoginDir())
    }

    internal fun newProfileDir(parent: Path): Path {
        val dir = parent.resolve("s-" + System.nanoTime().toString(36))
        Files.createDirectories(dir)
        return dir
    }

    internal fun sweepStaleProfiles(parent: Path) {
        if (!Files.isDirectory(parent)) return
        runCatching {
            Files.list(parent).use { stream ->
                stream.forEach { child ->
                    val name = child.fileName.toString()
                    if (name.startsWith("s-") || PROFILE_JUNK_NAMES.any { it.equals(name, ignoreCase = true) } ||
                        name.startsWith("BrowserMetrics", ignoreCase = true) ||
                        name.equals("Default", ignoreCase = true) ||
                        name.equals("DevToolsActivePort", ignoreCase = true) ||
                        name.equals("SingletonLock", ignoreCase = true) ||
                        name.equals("SingletonCookie", ignoreCase = true) ||
                        name.equals("SingletonSocket", ignoreCase = true)
                    ) {
                        AppPaths.deleteQuietly(child)
                    }
                }
            }
        }
    }

    internal fun prepareEphemeralProfile(dir: Path) {
        runCatching { Files.createDirectories(dir) }
        pruneProfileJunk(dir)
    }

    /** Edge's msedge.exe stub can exit before DevToolsActivePort exists. Wait a beat. */
    internal fun deadWithoutDevTools(
        processAlive: Boolean,
        boundPort: Int,
        iteration: Int,
        graceIterations: Int = 8,
    ): Boolean = !processAlive && boundPort <= 0 && iteration >= graceIterations

    internal fun pruneProfileJunk(dir: Path) {
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { stream ->
                stream.forEach { child ->
                    val name = child.fileName.toString()
                    val junk = PROFILE_JUNK_NAMES.any { it.equals(name, ignoreCase = true) } ||
                        name.startsWith("BrowserMetrics", ignoreCase = true)
                    if (junk) AppPaths.deleteQuietly(child)
                }
            }
        }
    }

    /**
     * Headless fetch and login share this argv. Edge 150's old `--headless`
     * (without `=new`) aborts on an existing profile with
     * "Multiple targets are not supported in headless mode" and never binds
     * DevTools — that is what made 直连 look like a hang at HTTPS 采样中.
     * `--remote-debugging-port=0` lets Chromium pick a port and write
     * `DevToolsActivePort`, avoiding Hyper-V excluded ranges / TOCTOU from
     * `ServerSocket(0)`.
     */
    internal fun browserLaunchArgs(
        browser: Path,
        userDataDir: Path,
        cacheDir: Path,
        crashDir: Path,
        debugPort: Int,
        headless: Boolean,
    ): List<String> {
        val args = mutableListOf(
            browser.toString(),
            "--user-data-dir=${userDataDir.toAbsolutePath()}",
            "--disk-cache-dir=${cacheDir.toAbsolutePath()}",
            "--crash-dumps-dir=${crashDir.toAbsolutePath()}",
            "--remote-debugging-port=$debugPort",
            "--remote-debugging-address=127.0.0.1",
            "--remote-allow-origins=*",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-sync",
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-component-extensions-with-background-pages",
            "--disable-default-apps",
            "--disable-client-side-phishing-detection",
            "--disable-domain-reliability",
            "--disable-breakpad",
            "--disable-crash-reporter",
            "--metrics-recording-only",
            "--disk-cache-size=67108864",
            "--disable-popup-blocking",
            "--disable-session-crashed-bubble",
            "--hide-crash-restore-bubble",
            "--disable-features=Translate,MediaRouter,msImplicitSignin,InterestFeedContentSuggestions,AutofillServerCommunication,CertificateTransparencyComponentUpdater,OptimizationHints,CalculateNativeWinOcclusion,HeavyAdPrivacyMitigations,EdgeShopping,EdgeWallet,msEdgeSidebar,msEdgeCollections,msEdgeWorkspaces,SmartScreen,msEdgeDiscover",
            "--host-resolver-rules=${hostResolverRules()}",
            "--enable-quic",
            "--origin-to-force-quic-on=${originToForceQuicOn()}",
            "--disable-hang-monitor",
            "--disable-renderer-backgrounding",
        )
        if (browser.fileName.toString().contains("msedge", ignoreCase = true)) {
            args += "--edge-skip-compat-layer-relaunch"
        }
        args += proxyArgs()
        if (headless) {
            args += "--headless=new"
            // --disable-gpu hangs Edge 150's renderer on some machines so
            // Page.enable never replies. New headless does not need it.
            args += "--window-position=-32000,-32000"
        } else {
            args += "--window-size=480,800"
        }
        args += "about:blank"
        return args
    }

    internal fun parseDevToolsActivePort(text: String): Int? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return first.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /** Edge 150 page-target sockets accept the handshake but never reply. Use the browser socket. */
    internal fun parseBrowserWebSocket(port: Int, activePortText: String): String? {
        if (port !in 1..65535) return null
        val path = activePortText.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("/devtools/") } ?: return null
        return "ws://127.0.0.1:$port$path"
    }

    internal fun parseBrowserWebSocketFromVersion(versionJson: String): String? =
        runCatching {
            JsonParser.parseString(versionJson).asJsonObject
                .get("webSocketDebuggerUrl")?.asString
                ?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
        }.getOrNull()

    internal fun cdpUsesBrowserSession(method: String): Boolean =
        method.startsWith("Target.") || method.startsWith("Browser.")

    internal fun pickPageTargetId(listJson: String, excludeId: String? = null, preferBlank: Boolean = true): String? {
        val parsed = runCatching { JsonParser.parseString(listJson) }.getOrNull() ?: return null
        if (!parsed.isJsonArray) return null
        val pages = parsed.asJsonArray.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            val type = obj.get("type")?.asString.orEmpty()
            val url = obj.get("url")?.asString.orEmpty()
            val id = obj.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (id == excludeId) return@mapNotNull null
            if (type != "page" && type != "app" && type != "webview") return@mapNotNull null
            if (url.startsWith("chrome-extension://") ||
                url.startsWith("edge-extension://") ||
                url.startsWith("chrome://") ||
                url.startsWith("edge://")
            ) return@mapNotNull null
            obj
        }
        val blank = pages.firstOrNull { obj ->
            val url = obj.get("url")?.asString.orEmpty()
            url == "about:blank" || url.isBlank()
        }
        val live = pages.firstOrNull { obj ->
            val url = obj.get("url")?.asString.orEmpty()
            url.isNotBlank() && url != "about:blank"
        }
        val chosen = if (preferBlank) blank ?: live ?: pages.firstOrNull()
        else live ?: blank ?: pages.firstOrNull()
        return chosen?.get("id")?.asString
    }

    /**
     * Edge 150's command-line placeholder is type=page, url empty, pid=0 —
     * Runtime.enable on it never replies. A Target.createTarget about:blank
     * also starts at pid=0, but that session is a real renderer (QUIC fetch
     * works). Only the empty-url placeholder is dead.
     */
    internal fun isLivePageTarget(info: JsonObject): Boolean {
        val type = info.get("type")?.asString.orEmpty()
        if (type != "page" && type != "app" && type != "webview") return false
        val url = info.get("url")?.asString.orEmpty()
        if (url.startsWith("chrome-extension://") ||
            url.startsWith("edge-extension://") ||
            url.startsWith("chrome://") ||
            url.startsWith("edge://")
        ) return false
        val pidZero = info.has("pid") &&
            info.get("pid").isJsonPrimitive &&
            info.get("pid").asLong == 0L
        if (pidZero && url.isBlank()) return false
        return true
    }

    /** Page-only auto-attach: Edge 150 still loads built-in extension targets. */
    internal fun autoAttachParams(
        waitForDebuggerOnStart: Boolean = false,
        pageOnly: Boolean = true,
    ): JsonObject = JsonObject().apply {
        addProperty("autoAttach", true)
        addProperty("waitForDebuggerOnStart", waitForDebuggerOnStart)
        addProperty("flatten", true)
        if (pageOnly) {
            add(
                "filter",
                JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "page") })
                },
            )
        }
    }

    private val devToolsListening = Regex(
        """DevTools listening on ws://(?:127\.0\.0\.1|localhost|\[::1\]):(\d+)/""",
        RegexOption.IGNORE_CASE,
    )

    internal fun parseDevToolsListeningPort(log: String): Int? =
        devToolsListening.find(log)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    internal fun parseDevToolsListeningWebSocket(log: String): String? {
        val match = Regex(
            """DevTools listening on (ws://(?:127\.0\.0\.1|localhost|\[::1\]):\d+/devtools/\S+)""",
            RegexOption.IGNORE_CASE,
        ).find(log) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.trimEnd(',', ';')
    }

    /** Tiny same-origin document so fetch() is CORS-safe without loading the real homepage. */
    internal fun originWarmupUrl(origin: String): String = origin.trimEnd('/') + "/robots.txt"

    internal fun protocolOf(name: String?): Protocol = when {
        name.isNullOrBlank() -> Protocol.HTTP_1_1
        name.startsWith("h3", ignoreCase = true) ||
            name.contains("quic", ignoreCase = true) ||
            name.contains("http/3", ignoreCase = true) -> Protocol.QUIC
        name.startsWith("h2", ignoreCase = true) ||
            name.contains("http/2", ignoreCase = true) -> Protocol.HTTP_2
        else -> Protocol.HTTP_1_1
    }

    /**
     * POST oauth.secure.pixiv.net/auth/token cannot go out as a fetch(): the
     * browser stack gets a WAF 403 there no matter which headers are stripped.
     * A **top-level form POST navigation** (the same shape pixiv's own login
     * post-redirect uses) passes, but only once the headless desktop UA is
     * overridden — a HeadlessEdge UA earns a CAPTCHA interstitial instead of
     * the OAuth JSON.
     */
    internal fun isTokenPost(request: Request): Boolean =
        request.method.equals("POST", ignoreCase = true) &&
            request.url.host.equals("oauth.secure.pixiv.net", ignoreCase = true) &&
            request.url.encodedPath == "/auth/token" &&
            request.body is FormBody

    /** Form fields rendered to the `[{name,value}]` spec consumed by executeFormPost. */
    internal fun formPostSpec(url: String, form: FormBody): JsonObject = JsonObject().apply {
        addProperty("url", url)
        add("fields", JsonArray().apply {
            for (i in 0 until form.size) {
                add(
                    JsonObject().apply {
                        addProperty("name", form.name(i))
                        addProperty("value", form.value(i))
                    },
                )
            }
        })
    }

    /** The /auth/token page state polled by executeFormPost: href, readyState, body text. */
    internal fun parseTokenPageState(json: String?): Triple<String, String, String> {
        val obj = json?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        return Triple(
            obj?.get("href")?.asString.orEmpty(),
            obj?.get("ready")?.asString.orEmpty(),
            obj?.get("text")?.asString.orEmpty(),
        )
    }

    /** True when pixiv answered with its 人机验证 interstitial instead of OAuth JSON. */
    internal fun looksLikeCaptcha(body: String): Boolean =
        body.contains("CAPTCHA", ignoreCase = true) || body.contains("须先进行验证")

    fun findBrowser(): Path? {
        System.getProperty("pixshaft.chromium")?.let { propertyPath(it) }?.let { return it }
        System.getenv("PIXSHAFT_CHROMIUM")?.let { propertyPath(it) }?.let { return it }
        // This PC's working QUIC browser is the standalone Edge; App Paths chrome.exe
        // may point at a broken/stale install that loses the renderer under CDP.
        propertyPath("D:\\Sware\\Edge\\Edge\\Application\\msedge.exe")?.let { return it }
        queryAppPath("msedge.exe")?.let { return it }
        queryAppPath("chrome.exe")?.let { return it }
        val local = System.getenv("LOCALAPPDATA").orEmpty()
        val pf = System.getenv("ProgramFiles").orEmpty()
        val pf86 = System.getenv("ProgramFiles(x86)").orEmpty()
        val candidates = listOf(
            Path.of(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
            Path.of(local, "Microsoft", "Edge", "Application", "msedge.exe"),
            Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"),
            Path.of(local, "Google", "Chrome", "Application", "chrome.exe"),
            Path.of(pf, "Google", "Chrome", "Application", "chrome.exe"),
            Path.of(pf86, "Google", "Chrome", "Application", "chrome.exe"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    internal fun mergeProtocolHandlerPrefs(existingJson: String?): String {
        val root = existingJson?.let {
            runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
        } ?: JsonObject()
        val protocol = root.getAsJsonObject("protocol_handler") ?: JsonObject().also {
            root.add("protocol_handler", it)
        }
        val excluded = protocol.getAsJsonObject("excluded_schemes") ?: JsonObject().also {
            protocol.add("excluded_schemes", it)
        }
        excluded.addProperty("pixiv", false)
        excluded.addProperty("shaft", false)
        val pairs = protocol.getAsJsonObject("allowed_origin_protocol_pairs") ?: JsonObject().also {
            protocol.add("allowed_origin_protocol_pairs", it)
        }
        for (origin in listOf(
            "https://app-api.pixiv.net",
            "https://accounts.pixiv.net",
            "https://www.pixiv.net",
            "https://oauth.secure.pixiv.net",
        )) {
            val obj = pairs.getAsJsonObject(origin) ?: JsonObject().also { pairs.add(origin, it) }
            obj.addProperty("pixiv", true)
            obj.addProperty("shaft", true)
        }
        return Gson().toJson(root)
    }

    internal fun normalizeProxyServer(raw: String): String? {
        val trimmed = raw.trim().trim('"')
        if (trimmed.isBlank() || trimmed.equals("direct", ignoreCase = true)) return null
        if (trimmed.contains('=')) {
            val parts = trimmed.split(';').map { it.trim() }.filter { it.isNotBlank() }
            val https = parts.firstOrNull { it.startsWith("https=", ignoreCase = true) }?.substringAfter('=')
            val http = parts.firstOrNull { it.startsWith("http=", ignoreCase = true) }?.substringAfter('=')
            val socks = parts.firstOrNull { it.startsWith("socks=", ignoreCase = true) }?.substringAfter('=')
            val chosen = https ?: http ?: socks ?: return null
            return normalizeProxyServer(chosen)
        }
        return when {
            trimmed.startsWith("socks5h://", ignoreCase = true) ->
                "socks5://" + trimmed.substringAfter("://")
            trimmed.startsWith("socks5://", ignoreCase = true) ||
                trimmed.startsWith("socks://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    internal fun parseWindowsProxyReg(output: String): String? {
        val enable = Regex("""ProxyEnable\s+REG_DWORD\s+0x1\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(output)
        if (!enable) return null
        val server = Regex("""ProxyServer\s+REG_SZ\s+(.+)""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
        return normalizeProxyServer(server)
    }

    @Volatile
    var directConnect: Boolean = true

    fun detectProxy(): String? {
        if (directConnect) return null
        if (System.getenv("PIXSHAFT_NO_PROXY") == "1") return null
        System.getenv("PIXSHAFT_PROXY")?.let { normalizeProxyServer(it) }?.let { return it }
        val envKeys = listOf(
            "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY",
            "https_proxy", "http_proxy", "all_proxy",
        )
        for (key in envKeys) {
            val raw = System.getenv(key)?.trim().orEmpty()
            if (raw.isNotBlank()) normalizeProxyServer(raw)?.let { return it }
        }
        return queryWindowsProxy()
    }

    fun proxyArgs(): List<String> {
        val proxy = detectProxy()
        return if (proxy.isNullOrBlank()) {
            listOf("--no-proxy-server")
        } else {
            listOf("--proxy-server=$proxy")
        }
    }

    fun javaProxy(): java.net.Proxy = parseJavaProxy(detectProxy()) ?: java.net.Proxy.NO_PROXY

    /** DevTools is loopback. Never send it through Clash / system proxy. */
    fun loopbackClient(connectSec: Long = 1, readSec: Long = 2): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .build()

    private val livePids = ConcurrentHashMap.newKeySet<Long>()

    fun registerProcess(process: Process) {
        livePids.add(process.pid())
    }

    fun killProcessTree(process: Process) {
        val pid = runCatching { process.pid() }.getOrNull()
        if (pid != null) livePids.remove(pid)
        killPidTree(pid)
        runCatching { process.destroyForcibly() }
    }

    fun abortLoginBrowsers() {
        killByCommandLineMarkers("chromium-login", "pixshaft-login")
    }

    fun nukeAll() {
        val descendants = runCatching {
            ProcessHandle.current().descendants().map { it.pid() }.toList()
        }.getOrDefault(emptyList())
        val targets = (livePids + descendants).toSet()
        targets.forEach { killPidTree(it) }
        livePids.clear()
        // ProcessHandle.commandLine() is often empty on Windows; CIM sees the real argv.
        killOrphanChromium()
        waitUntilGone(targets, 1_500)
    }

    private fun killPidTree(pid: Long?) {
        if (pid == null || pid == ProcessHandle.current().pid()) return
        runCatching {
            ProcessHandle.of(pid).ifPresent { handle ->
                handle.descendants().forEach { child -> runCatching { child.destroyForcibly() } }
                handle.destroyForcibly()
            }
        }
        if (!isWindows()) return
        runCatching {
            val proc = ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    private fun killByCommandLineMarkers(vararg markers: String) {
        killOrphanChromium(*markers)
    }

    /**
     * Kill leftover Chromium helpers without spawning hidden PowerShell.
     * Hidden powershell.exe on close looks like malware and trips Defender.
     * Tracked PIDs + JVM descendants already cover processes we launched;
     * this only matches ProcessHandle command / commandLine when Windows exposes them.
     */
    private fun killOrphanChromium(vararg extraMarkers: String) {
        val markers = if (extraMarkers.isEmpty()) {
            arrayOf("chromium-login", "chromium-net", "pixshaft-login")
        } else extraMarkers
        runCatching {
            ProcessHandle.allProcesses().forEach { handle ->
                if (handle.pid() == ProcessHandle.current().pid()) return@forEach
                val blob = handle.info().commandLine().orElse("") + " " + handle.info().command().orElse("")
                if (markers.any { blob.contains(it) }) killPidTree(handle.pid())
            }
        }
    }

    private fun waitUntilGone(pids: Collection<Long>, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = pids.any { pid ->
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }
            if (!alive) return
            Thread.sleep(40)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

    internal fun parseJavaProxy(raw: String?): java.net.Proxy? {
        val normalized = raw?.let { normalizeProxyServer(it) } ?: return null
        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (normalized.startsWith("socks")) 1080 else 80
        val type = if (normalized.startsWith("socks", ignoreCase = true)) {
            java.net.Proxy.Type.SOCKS
        } else {
            java.net.Proxy.Type.HTTP
        }
        return java.net.Proxy(type, java.net.InetSocketAddress(host, port))
    }

    internal fun mobileHookScript(): String {
        val ua = Gson().toJson(ANDROID_UA)
        return """
            (function() {
              const ua = $ua;
              const report = (u) => {
                try { if (typeof pixshaftCallback === 'function') pixshaftCallback(String(u)); } catch (e) {}
                try { console.debug('PIXSHAFT_CALLBACK:' + u); } catch (e) {}
              };
              const looks = (u) => {
                if (typeof u !== 'string') return false;
                if (u.indexOf('pixiv:') === 0 || u.indexOf('shaft:') === 0 ||
                    u.indexOf('intent:') === 0 || u.indexOf('android-app:') === 0) return true;
                if (u.indexOf('/auth/pixiv/callback') >= 0) return true;
                if (u.indexOf('post-redirect') >= 0 || u.indexOf('/auth/pixiv/start') >= 0) return false;
                return /(?:^|[?&#])code=/.test(u) && u.indexOf('code_challenge') < 0;
              };
              const hook = (u) => { if (looks(u)) report(u); return u; };
              try {
                const desc = Object.getOwnPropertyDescriptor(Location.prototype, 'href');
                if (desc && desc.set) {
                  Object.defineProperty(Location.prototype, 'href', {
                    configurable: true, enumerable: true, get: desc.get,
                    set: function(v) { hook(v); return desc.set.call(this, v); }
                  });
                }
              } catch (e) {}
              try {
                const assign = Location.prototype.assign;
                Location.prototype.assign = function(v) { hook(v); return assign.call(this, v); };
              } catch (e) {}
              try {
                const replace = Location.prototype.replace;
                Location.prototype.replace = function(v) { hook(v); return replace.call(this, v); };
              } catch (e) {}
              try {
                const open = window.open;
                window.open = function(v) { hook(v); return open.apply(this, arguments); };
              } catch (e) {}
              document.addEventListener('click', function(e) {
                try {
                  const a = e.target && e.target.closest ? e.target.closest('a') : null;
                  if (a && a.href) hook(a.href);
                } catch (err) {}
              }, true);
              const navProto = Navigator.prototype;
              try { Object.defineProperty(navProto, 'userAgent', { get: () => ua, configurable: true }); } catch (e) {}
              try { Object.defineProperty(navProto, 'platform', { get: () => 'Linux armv8l', configurable: true }); } catch (e) {}
              try { Object.defineProperty(navProto, 'maxTouchPoints', { get: () => 5, configurable: true }); } catch (e) {}
              try { Object.defineProperty(navProto, 'vendor', { get: () => 'Google Inc.', configurable: true }); } catch (e) {}
              const uad = {
                brands: [
                  {brand: 'Chromium', version: '131'},
                  {brand: 'Google Chrome', version: '131'},
                  {brand: 'Not_A Brand', version: '24'}
                ],
                mobile: true,
                platform: 'Android',
                getHighEntropyValues: async function() {
                  return {
                    brands: this.brands, mobile: true, platform: 'Android',
                    platformVersion: '14.0.0', architecture: '', model: 'Pixel 8',
                    uaFullVersion: '131.0.6778.135', fullVersionList: this.brands,
                    bitness: '64', wow64: false
                  };
                },
                toJSON: function() { return {brands: this.brands, mobile: true, platform: 'Android'}; }
              };
              try { Object.defineProperty(navProto, 'userAgentData', { get: () => uad, configurable: true }); } catch (e) {}
              try { hook(String(window.location.href)); } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun queryWindowsProxy(): String? {
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return null
        return runCatching {
            val process = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            parseWindowsProxyReg(output)
        }.getOrNull()
    }

    internal fun describeCdpFailure(method: String, error: Throwable, timeoutSec: Long): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val timedOut = chain.any { it is java.util.concurrent.TimeoutException }
        if (timedOut) {
            return if (method.startsWith("Runtime.evaluate") || method.contains("fetch", ignoreCase = true)) {
                "CDP $method 超时（${timeoutSec}s）。登录窗可能卡在系统对话框上，请点允许或关掉该提示后重试。"
            } else {
                "CDP $method 超时（${timeoutSec}s）。登录页可能正在跳转或渲染进程无响应。"
            }
        }
        val msg = chain.mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
        return "CDP $method 失败: ${msg ?: error.javaClass.simpleName}"
    }

    internal fun isCdpTargetDead(error: Throwable): Boolean {
        val msg = buildString {
            var current: Throwable? = error
            while (current != null) {
                append(current.message.orEmpty())
                append('\n')
                current = current.cause
            }
        }
        return listOf(
            "Target crashed",
            "Session with given id not found",
            "Inspected target navigated or closed",
            "\"code\":-32000",
            "\"code\": -32000",
        ).any { needle -> msg.contains(needle, ignoreCase = true) }
    }

    private fun propertyPath(raw: String): Path? {
        val path = runCatching { Path.of(raw.trim()) }.getOrNull() ?: return null
        return path.takeIf { Files.isRegularFile(it) }
    }

    internal fun queryAppPath(exeName: String): Path? {
        val keys = listOf(
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName",
        )
        for (key in keys) {
            val process = ProcessBuilder("reg", "query", key, "/ve")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            val path = parseRegDefault(output) ?: continue
            if (Files.isRegularFile(path)) return path
        }
        return null
    }

    internal fun parseRegDefault(output: String): Path? {
        val line = output.lineSequence().firstOrNull { it.contains("REG_SZ") } ?: return null
        val value = line.substringAfter("REG_SZ").trim().trim('"')
        if (value.isBlank()) return null
        return runCatching { Path.of(value) }.getOrNull()
    }
}

class ChromiumSession private constructor(
    private val process: Process,
    private val cdp: CdpClient,
    private val debugPort: Int,
    private val loginUrl: String?,
    private val userDataDir: Path,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val localHttp = Chromium.loopbackClient()
    private val recentRequestUrls = ConcurrentHashMap<String, String>()
    private val pendingSessions = ConcurrentLinkedDeque<String>()
    private val sawAccounts = AtomicBoolean(false)
    private val retriedOauth = AtomicBoolean(false)
    private val pendingDialogs = AtomicBoolean(false)
    private val followedReturnTo = AtomicBoolean(false)
    private val postRedirectSeenAt = AtomicReference(0L)
    private var lastClipboardPoll = 0L
    private var lastPageFollow = 0L
    private var lastBodyPoll = 0L

    fun awaitCallback(
        timeoutMs: Long,
        onStatus: (String) -> Unit = {},
        sink: LoginReturnChannel = LoginReturnChannel(),
    ): String {
        val crashed = AtomicBoolean(false)
        fun consider(raw: String?) {
            noteUrl(raw)
            sink.offer(raw)
        }
        val paused = ConcurrentLinkedDeque<Pair<String, String?>>()
        val listener: (String, JsonObject) -> Unit = { method, params ->
            when (method) {
                "Network.requestWillBeSent" -> {
                    val url = params.getAsJsonObject("request")?.get("url")?.asString
                    params.get("requestId")?.asString?.let { id ->
                        if (url != null) recentRequestUrls[id] = url
                    }
                    consider(url)
                    consider(params.get("documentURL")?.asString)
                    consider(params.getAsJsonObject("redirectResponse")?.get("url")?.asString)
                }
                "Network.responseReceived" ->
                    consider(params.getAsJsonObject("response")?.get("url")?.asString)
                "Network.loadingFailed" ->
                    consider(recentRequestUrls.remove(params.get("requestId")?.asString))
                "Page.frameNavigated" ->
                    consider(params.getAsJsonObject("frame")?.get("url")?.asString)
                "Page.frameRequestedNavigation" ->
                    consider(params.get("url")?.asString)
                "Page.windowOpen" ->
                    consider(params.get("url")?.asString)
                "Page.navigatedWithinDocument" ->
                    consider(params.get("url")?.asString)
                "Runtime.bindingCalled" -> {
                    if (params.get("name")?.asString == "pixshaftCallback") {
                        consider(params.get("payload")?.asString)
                    }
                }
                "Runtime.consoleAPICalled" -> {
                    params.getAsJsonArray("args")?.forEach { arg ->
                        val value = arg.asJsonObject.get("value")?.asString ?: return@forEach
                        if (value.startsWith("PIXSHAFT_CALLBACK:")) {
                            consider(value.removePrefix("PIXSHAFT_CALLBACK:"))
                        } else {
                            consider(value)
                        }
                    }
                }
                "Target.targetCreated", "Target.targetInfoChanged" ->
                    consider(params.getAsJsonObject("targetInfo")?.get("url")?.asString)
                "Target.attachedToTarget" -> {
                    val info = params.getAsJsonObject("targetInfo")
                    consider(info?.get("url")?.asString)
                    val type = info?.get("type")?.asString.orEmpty()
                    val sessionId = params.get("sessionId")?.asString
                    if (sessionId != null && type in setOf("page", "iframe", "app", "webview")) {
                        pendingSessions.add(sessionId)
                    }
                }
                "Inspector.targetCrashed", "Inspector.detached", "Target.targetCrashed" ->
                    crashed.set(true)
                "Page.javascriptDialogOpening" ->
                    pendingDialogs.set(true)
                "Fetch.requestPaused" -> {
                    val url = params.getAsJsonObject("request")?.get("url")?.asString
                    consider(url)
                    val requestId = params.get("requestId")?.asString
                    if (requestId != null) paused.add(requestId to url)
                }
            }
        }
        cdp.addListener(listener)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (!sink.isComplete && System.currentTimeMillis() < deadline) {
                drainPaused(paused, failAll = sink.isComplete)
                drainAttachedSessions()
                drainDialogs()
                if (crashed.compareAndSet(true, false)) {
                    runCatching { recover(onStatus) }
                }
                pollJsonList(sink)
                assistPostRedirect(onStatus)
                closeExtraBlankPages()
                followNewestPage(onStatus)
                pollClipboard(sink)
                maybeRetryOauth(onStatus)
                if (sink.await(150)) break
                runCatching {
                    val history = cdp.send("Page.getNavigationHistory", timeoutSec = 3)
                    history.getAsJsonArray("entries")?.forEach { entry ->
                        consider(entry.asJsonObject.get("url")?.asString)
                    }
                }.onFailure { error ->
                    if (Chromium.isCdpTargetDead(error)) {
                        runCatching { recover(onStatus) }
                    }
                }
                onStatus("等待 Pixiv 登录完成…请在弹出的窗口里登录；若系统询问打开 PixShaft，请允许")
            }
        } finally {
            drainPaused(paused, failAll = true)
            cdp.removeListener(listener)
        }
        if (sink.isCancelled) {
            throw InterruptedException("已取消登录")
        }
        val uri = sink.uri
            ?: throw IOException("登录超时：Chromium 窗口里没有拿到带 code= 的回调。可粘贴 refresh_token。")
        runCatching { prepareForNetwork(onStatus) }
        return uri
    }

    fun prepareForNetwork(onStatus: (String) -> Unit = {}) {
        onStatus("已拿到授权码，正在交换令牌…")
        drainDialogs()
        runCatching { cdp.send("Fetch.disable", timeoutSec = 3) }
        // Do not navigate to about:blank here. post-redirect /auth/pixiv/start has no
        // code yet; blanking the window aborts the OAuth chain and leaves a white pane.
    }

    private fun noteUrl(raw: String?) {
        val url = raw.orEmpty().lowercase()
        if (url.contains("accounts.pixiv.net")) sawAccounts.set(true)
    }

    private fun drainPaused(
        paused: ConcurrentLinkedDeque<Pair<String, String?>>,
        failAll: Boolean,
    ) {
        while (true) {
            val item = paused.poll() ?: break
            val url = item.second.orEmpty()
            val custom = url.startsWith("pixiv:", ignoreCase = true) ||
                url.startsWith("shaft:", ignoreCase = true) ||
                url.startsWith("intent:", ignoreCase = true)
            val params = JsonObject().apply { addProperty("requestId", item.first) }
            runCatching {
                // Abort only custom-scheme navigations (Chrome cannot render them).
                // Let the HTTPS callback page load so /json/list and JS hooks still see code=.
                if (failAll || custom) {
                    params.addProperty("errorReason", "Aborted")
                    cdp.send("Fetch.failRequest", params, timeoutSec = 3)
                } else {
                    cdp.send("Fetch.continueRequest", params, timeoutSec = 3)
                }
            }
        }
    }

    private fun drainAttachedSessions() {
        while (true) {
            val sessionId = pendingSessions.poll() ?: break
            runCatching { prepareAttached(cdp, sessionId) }
        }
    }

    private fun drainDialogs() {
        if (!pendingDialogs.getAndSet(false)) return
        runCatching {
            cdp.send(
                "Page.handleJavaScriptDialog",
                JsonObject().apply { addProperty("accept", true) },
                timeoutSec = 2,
            )
        }
    }

    private fun pollJsonList(sink: LoginReturnChannel) {
        runCatching {
            for (page in listPages()) {
                val url = page.get("url")?.asString
                noteUrl(url)
                sink.offer(url)
            }
        }
    }

    private fun assistPostRedirect(onStatus: (String) -> Unit) {
        val pages = runCatching { listPages() }.getOrDefault(emptyList())
        val urls = pages.map { it.get("url")?.asString.orEmpty() }
        if (urls.any { ceui.pixshaft.shared.auth.PixivOAuth.extractCallback(it) != null }) return
        if (urls.any { it.contains("/auth/pixiv/callback", ignoreCase = true) }) return
        val onPost = urls.any { it.contains("post-redirect", ignoreCase = true) }
        if (!onPost) {
            postRedirectSeenAt.set(0L)
            return
        }
        val firstSeen = postRedirectSeenAt.updateAndGet { current ->
            if (current == 0L) System.currentTimeMillis() else current
        }
        // Let accounts.pixiv.net POST the return_to itself. A top-level GET of
        // /auth/pixiv/start returns Pixiv's "您所指定的端点不存在".
        if (System.currentTimeMillis() - firstSeen < 2500) return
        if (!followedReturnTo.compareAndSet(false, true)) return
        onStatus("登录中间页停留过久，正在提交跳转表单…")
        runCatching {
            cdp.send(
                "Runtime.evaluate",
                JsonObject().apply {
                    addProperty(
                        "expression",
                        """
                        (function() {
                          const forms = Array.from(document.querySelectorAll('form'));
                          for (const f of forms) { try { f.submit(); return 'form'; } catch (e) {} }
                          return 'none';
                        })()
                        """.trimIndent(),
                    )
                    addProperty("returnByValue", true)
                },
                timeoutSec = 5,
            )
        }.onFailure {
            followedReturnTo.set(false)
        }
    }

    private fun closeExtraBlankPages() {
        val pages = runCatching { listPages() }.getOrDefault(emptyList())
        val livePixiv = pages.any { page ->
            val url = page.get("url")?.asString.orEmpty()
            url.contains("pixiv.net", ignoreCase = true) || url.startsWith("pixiv:")
        }
        if (!livePixiv) return
        for (page in pages) {
            val url = page.get("url")?.asString.orEmpty()
            if (url != "about:blank" && url.isNotBlank()) continue
            if (page.get("id")?.asString == cdp.attachedTargetId) continue
            val id = page.get("id")?.asString ?: continue
            runCatching { httpGet(localHttp, "http://127.0.0.1:$debugPort/json/close/$id") }
        }
    }

    private fun followNewestPage(onStatus: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastPageFollow < 800) return
        lastPageFollow = now
        val pages = runCatching { listPages() }.getOrDefault(emptyList())
            .filter { page ->
                val url = page.get("url")?.asString.orEmpty()
                url.isNotBlank() && url != "about:blank" && !url.startsWith("chrome-error://")
            }
        if (pages.isEmpty()) return
        val best = pages.maxByOrNull { ceui.pixshaft.shared.auth.PixivOAuth.oauthStage(it.get("url")?.asString) }
            ?: return
        val targetId = best.get("id")?.asString ?: return
        if (targetId == cdp.attachedTargetId) return
        val stage = ceui.pixshaft.shared.auth.PixivOAuth.oauthStage(best.get("url")?.asString)
        if (stage < 20) return
        onStatus("登录跳到了新页面，正在跟随…")
        runCatching { reattachTo(targetId, onStatus) }
    }

    private fun pollClipboard(sink: LoginReturnChannel) {
        val now = System.currentTimeMillis()
        if (now - lastClipboardPoll < 1000) return
        lastClipboardPoll = now
        val text = runCatching {
            val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val flavor = java.awt.datatransfer.DataFlavor.stringFlavor
            if (!cb.isDataFlavorAvailable(flavor)) return
            (cb.getData(flavor) as? String)?.take(8192)
        }.getOrNull()
        sink.offer(text)
    }

    private fun maybeRetryOauth(onStatus: (String) -> Unit) {
        if (retriedOauth.get() || loginUrl.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastBodyPoll < 2500) return
        lastBodyPoll = now
        val pages = runCatching { listPages() }.getOrDefault(emptyList())
        val urls = pages.map { it.get("url")?.asString.orEmpty() }
        if (urls.any { ceui.pixshaft.shared.auth.PixivOAuth.extractCallback(it) != null }) return
        val body = runCatching { pageText() }.getOrNull()
        val rejected = ceui.pixshaft.shared.auth.PixivOAuth.looksLikeOauthRejection(body)
        val onWebsiteHome = sawAccounts.get() && urls.any { isPixivWebsiteHome(it) }
        if (!rejected && !onWebsiteHome) return
        if (!retriedOauth.compareAndSet(false, true)) return
        onStatus("网页已登录但没有返回授权码，正在用 Android 身份重试 OAuth…")
        runCatching {
            overrideUserAgent(cdp)
            overrideDevice(cdp)
            installHooks(cdp)
            cdp.send(
                "Page.navigate",
                JsonObject().apply { addProperty("url", loginUrl) },
            )
        }.onFailure { error ->
            if (Chromium.isCdpTargetDead(error)) runCatching { recover(onStatus) }
        }
    }

    private fun isPixivWebsiteHome(url: String): Boolean {
        val u = url.lowercase()
        if (!u.contains("www.pixiv.net")) return false
        if (u.contains("/login") || u.contains("accounts.") || u.contains("/oauth")) return false
        return true
    }

    private fun pageText(): String? {
        val params = JsonObject().apply {
            addProperty("expression", "document.body ? (document.body.innerText || '') : ''")
            addProperty("returnByValue", true)
        }
        val result = cdp.send("Runtime.evaluate", params, timeoutSec = 3)
        return result.getAsJsonObject("result")?.get("value")?.asString
    }

    private fun listPages(): List<JsonObject> {
        val list = httpGet(localHttp, "http://127.0.0.1:$debugPort/json/list")
        val parsed = JsonParser.parseString(list)
        if (!parsed.isJsonArray) return emptyList()
        return parsed.asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
        }
    }

    private fun reattachTo(targetId: String, onStatus: (String) -> Unit) {
        cdp.attachPage(targetId)
        preparePage(cdp, debugPort, onStatus, loginEmulation = loginUrl != null)
    }

    fun execute(request: Request): Response {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                if (attempt > 0) prepareForNetwork {}
                return evaluateFetch(request)
            } catch (error: Throwable) {
                last = error
                if (attempt == 0) runCatching { recover {} }
            }
        }
        throw last ?: IOException("Chromium fetch 失败")
    }

    /**
     * Top-level form POST navigation, used for POST oauth.secure.pixiv.net/auth/token.
     * Reads the OAuth JSON back out of the document that navigation lands on. The
     * caller (ChromiumHttp) serializes against execute(), so the shared page is safe.
     */
    fun executeFormPost(request: Request): Response {
        val form = request.body as? FormBody
            ?: throw IOException("auth/token 需要 FormBody: ${request.url.encodedPath}")
        overrideUserAgent(cdp)
        // Web-session cookies only widen the bot-fingerprint surface; the OAuth
        // token endpoint takes none of them.
        runCatching { cdp.send("Network.clearBrowserCookies", timeoutSec = 3) }
        val spec = Chromium.formPostSpec(request.url.toString(), form)
        val expression = """
            (async (spec) => {
              const form = document.createElement('form');
              form.method = 'POST';
              form.action = spec.url;
              form.autocomplete = 'off';
              form.style.display = 'none';
              for (const kv of spec.fields) {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = kv.name;
                input.value = kv.value;
                input.autocomplete = 'off';
                form.appendChild(input);
              }
              document.body.appendChild(form);
              form.submit();
              return 'submitted';
            })(${Gson().toJson(spec)})
        """.trimIndent()
        val submit = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
        }
        var responseStatus = 0
        val listener: (String, JsonObject) -> Unit = { method, params ->
            if (method == "Network.responseReceived") {
                val response = params.getAsJsonObject("response")
                val url = response?.get("url")?.asString.orEmpty()
                if (url.startsWith(request.url.toString(), ignoreCase = true)) {
                    responseStatus = response?.get("status")?.asInt ?: 0
                }
            }
        }
        cdp.addListener(listener)
        try {
            val result = cdp.send("Runtime.evaluate", submit, timeoutSec = 15)
            if (result.getAsJsonObject("exceptionDetails") != null) {
                throw IOException("auth/token 表单提交失败")
            }
            val target = request.url.toString()
            val deadline = System.currentTimeMillis() + 15_000
            var href = ""
            var body = ""
            var protocol: String? = null
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(300)
                val state = runCatching {
                    val probe = JsonObject().apply {
                        addProperty(
                            "expression",
                            "JSON.stringify({href:location.href,ready:document.readyState," +
                                "text:(document.body?document.body.innerText:'')," +
                                "proto:(performance.getEntriesByType('navigation')[0]||{}).nextHopProtocol||''})",
                        )
                        addProperty("returnByValue", true)
                    }
                    val value = cdp.send("Runtime.evaluate", probe, timeoutSec = 8)
                        .getAsJsonObject("result")?.get("value")?.asString
                    Chromium.parseTokenPageState(value)
                }.getOrNull() ?: continue
                href = state.first
                if (href.startsWith(target, ignoreCase = true) && state.second == "complete") {
                    body = state.third
                    protocol = runCatching {
                        val entry = cdp.send(
                            "Runtime.evaluate",
                            JsonObject().apply {
                                addProperty(
                                    "expression",
                                    "(performance.getEntriesByType('navigation')[0]||{}).nextHopProtocol||''",
                                )
                                addProperty("returnByValue", true)
                            },
                            timeoutSec = 5,
                        ).getAsJsonObject("result")?.get("value")?.asString
                        entry
                    }.getOrNull()
                    break
                }
            }
            if (!href.startsWith(target, ignoreCase = true)) {
                throw IOException("auth/token 表单跳转未完成（当前 $href）")
            }
            if (Chromium.looksLikeCaptcha(body)) {
                throw IOException("Pixiv 要求人机验证（${body.take(60)}），请稍后再试或开启代理")
            }
            val status = when {
                responseStatus > 0 -> responseStatus
                body.trimStart().startsWith("{") -> if (body.contains("\"access_token\"")) 200 else 400
                else -> throw IOException("auth/token 返回异常响应: ${body.take(120)}")
            }
            return Response.Builder()
                .request(request)
                .protocol(Chromium.protocolOf(protocol))
                .code(status)
                .message(if (status in 200..299) "OK" else "Error")
                .header("content-type", "application/json")
                .body(
                    body.toByteArray().toResponseBody("application/json".toMediaTypeOrNull()),
                )
                .build()
        } finally {
            cdp.removeListener(listener)
        }
    }

    private fun evaluateFetch(request: Request): Response {
        ensureFetchOrigin(request)
        val spec = JsonObject().apply {
            addProperty("url", request.url.toString())
            addProperty("method", request.method)
            val headers = JsonObject()
            request.headers.forEach { (name, value) -> headers.addProperty(name, value) }
            add("headers", headers)
            val body = request.body
            if (body != null) {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                addProperty("bodyBase64", Base64.getEncoder().encodeToString(buffer.readByteArray()))
                body.contentType()?.let { addProperty("contentType", it.toString()) }
            }
        }
        val expression = """
            (async (spec) => {
              const headers = spec.headers || {};
              if (spec.contentType && !headers['Content-Type'] && !headers['content-type']) {
                headers['Content-Type'] = spec.contentType;
              }
              const ac = new AbortController();
              const timer = setTimeout(() => ac.abort('timeout'), 12000);
              const init = {
                method: spec.method,
                headers,
                redirect: 'follow',
                credentials: 'omit',
                signal: ac.signal
              };
              if (spec.bodyBase64) {
                const bin = atob(spec.bodyBase64);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                init.body = bytes;
              }
              try {
                const res = await fetch(spec.url, init);
                const buf = await res.arrayBuffer();
                const bytes = new Uint8Array(buf);
                let binary = '';
                const chunk = 0x8000;
                for (let i = 0; i < bytes.length; i += chunk) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                }
                const outHeaders = {};
                res.headers.forEach((v, k) => { outHeaders[k] = v; });
                const perf = performance.getEntriesByName(spec.url).pop()
                  || performance.getEntriesByType('resource').pop();
                return JSON.stringify({
                  status: res.status,
                  statusText: res.statusText,
                  headers: outHeaders,
                  protocol: (perf && perf.nextHopProtocol) || '',
                  bodyBase64: btoa(binary)
                });
              } finally {
                clearTimeout(timer);
              }
            })(${Gson().toJson(spec)})
        """.trimIndent()
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
        }
        val result = cdp.send("Runtime.evaluate", params, timeoutSec = 20)
        val details = result.getAsJsonObject("exceptionDetails")
        if (details != null) {
            val description = details.getAsJsonObject("exception")?.get("description")?.asString
                ?: details.get("text")?.asString
                ?: details.toString()
            throw IOException("Chromium fetch 失败: $description")
        }
        val value = result.getAsJsonObject("result")?.get("value")?.asString
            ?: throw IOException("Chromium fetch 无返回")
        val parsed = JsonParser.parseString(value).asJsonObject
        val status = parsed.get("status")?.asInt ?: 0
        val headerBuilder = Headers.Builder()
        parsed.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
            runCatching { headerBuilder.add(k, v.asString) }
        }
        val bytes = Base64.getDecoder().decode(parsed.get("bodyBase64")?.asString ?: "")
        val mediaType = headerBuilder["content-type"]?.toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Chromium.protocolOf(parsed.get("protocol")?.asString))
            .code(status)
            .message(parsed.get("statusText")?.asString ?: "OK")
            .headers(headerBuilder.build())
            .body(bytes.toResponseBody(mediaType))
            .build()
    }

    private fun currentOrigin(): String? {
        return runCatching {
            cdp.send(
                "Runtime.evaluate",
                JsonObject().apply {
                    addProperty("expression", "location.origin")
                    addProperty("returnByValue", true)
                },
                timeoutSec = 2,
            )
        }.getOrNull()?.getAsJsonObject("result")?.get("value")?.asString
    }

    private fun ensureFetchOrigin(request: Request) {
        val origin = "${request.url.scheme}://${request.url.host}"
        if (currentOrigin().equals(origin, ignoreCase = true)) return
        runCatching { cdp.send("Fetch.disable", timeoutSec = 2) }
        val committed = CountDownLatch(1)
        val listener: (String, JsonObject) -> Unit = { method, params ->
            val url = when (method) {
                "Page.frameNavigated" -> params.getAsJsonObject("frame")?.get("url")?.asString
                "Page.lifecycleEvent" -> {
                    if (params.get("name")?.asString == "commit" ||
                        params.get("name")?.asString == "DOMContentLoaded"
                    ) {
                        origin
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (url != null && url.startsWith(origin, ignoreCase = true)) {
                committed.countDown()
            }
        }
        cdp.addListener(listener)
        try {
            cdp.send(
                "Page.navigate",
                JsonObject().apply { addProperty("url", Chromium.originWarmupUrl(origin)) },
                timeoutSec = 8,
            )
            committed.await(6, TimeUnit.SECONDS)
            repeat(20) {
                if (currentOrigin().equals(origin, ignoreCase = true)) return
                Thread.sleep(100)
            }
        } finally {
            cdp.removeListener(listener)
            runCatching { cdp.send("Page.stopLoading", timeoutSec = 2) }
        }
    }

    private fun recover(onStatus: (String) -> Unit) {
        onStatus("登录页崩溃，正在重连 DevTools…")
        reattach(cdp, process, debugPort, onStatus)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { cdp.close() }
        runCatching {
            localHttp.dispatcher.executorService.shutdownNow()
            localHttp.connectionPool.evictAll()
        }
        Chromium.killProcessTree(process)
        // Best-effort: Edge often keeps files locked for a beat. Next launch also wipes.
        runCatching { AppPaths.clearDirectory(userDataDir) }
    }

    companion object {
        fun launchLogin(url: String, onStatus: (String) -> Unit): ChromiumSession {
            onStatus("正在启动 Chromium 登录窗…")
            return launch(
                userDataDir = Chromium.newProfileDir(AppPaths.chromiumLoginDir()),
                url = url,
                headless = false,
                onStatus = onStatus,
            )
        }

        fun launchHeadless(): ChromiumSession =
            launch(
                userDataDir = Chromium.newProfileDir(AppPaths.chromiumNetDir()),
                url = "about:blank",
                headless = true,
                onStatus = {},
            )

        internal fun launch(
            userDataDir: Path,
            url: String?,
            headless: Boolean,
            onStatus: (String) -> Unit,
        ): ChromiumSession {
            val browser = Chromium.findBrowser()
                ?: throw IOException(
                    "本机没有 Chrome / Edge，无法打开 Pixiv 登录。请安装 Microsoft Edge 或 Google Chrome，或粘贴 refresh_token。",
                )
            Chromium.prepareEphemeralProfile(userDataDir)
            val cacheDir = AppPaths.cacheRoot().resolve("chromium")
            val crashDir = AppPaths.cacheRoot().resolve("chromium-crash")
            Files.createDirectories(cacheDir)
            Files.createDirectories(crashDir)
            writePreferences(userDataDir)
            val args = Chromium.browserLaunchArgs(
                browser = browser,
                userDataDir = userDataDir,
                cacheDir = cacheDir,
                crashDir = crashDir,
                debugPort = 0,
                headless = headless,
            )
            onStatus("打开 ${browser.fileName}…")
            val process = ProcessBuilder(args)
                .directory(userDataDir.toFile())
                .redirectErrorStream(true)
                .start()
            Chromium.registerProcess(process)
            val logTail = ConcurrentLinkedDeque<String>()
            drain(process, logTail)
            try {
                val (port, browserWs, _) = waitForPageWebSocket(process, userDataDir, 0, logTail)
                val cdp = CdpClient(browserWs)
                cdp.connect()
                cdp.createAndAttachBlank()
                preparePage(cdp, port, onStatus, loginEmulation = !headless)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    onStatus("正在打开 Pixiv 登录页…")
                    navigateAndPrepare(cdp, process, userDataDir, port, url, onStatus)
                }
                onStatus("已打开登录窗，请在弹出的 Chromium 窗口完成登录")
                return ChromiumSession(process, cdp, port, url, userDataDir)
            } catch (error: Throwable) {
                val extra = logTail.toList().takeLast(20).joinToString("\n")
                CrashLog.write(error)
                if (extra.isNotBlank()) CrashLog.write("chromium-out:\n$extra")
                runCatching { process.destroyForcibly() }
                runCatching { AppPaths.deleteQuietly(userDataDir) }
                if (extra.isBlank()) throw error
                throw IOException("${error.message}\nchromium-out:\n$extra", error)
            }
        }

        private fun writePreferences(userDataDir: Path) {
            val prefsDir = userDataDir.resolve("Default")
            Files.createDirectories(prefsDir)
            val prefs = prefsDir.resolve("Preferences")
            val existing = if (Files.isRegularFile(prefs)) {
                runCatching { Files.readString(prefs) }.getOrNull()
            } else {
                null
            }
            Files.writeString(prefs, Chromium.mergeProtocolHandlerPrefs(existing))
        }

        private fun preparePage(
            cdp: CdpClient,
            port: Int,
            onStatus: (String) -> Unit,
            loginEmulation: Boolean = true,
        ) {
            // Flatten auto-attach belongs on the visible login window.
            // On the headless page websocket it swallows Page.enable replies
            // (no sessionId), which shows up as a 20s CDP timeout.
            if (loginEmulation) enableTargetDiscovery(cdp)
            enableDomains(cdp)
            // The net session needs this too: a HeadlessEdg UA turns every
            // oauth.secure POST into pixiv's CAPTCHA interstitial.
            overrideUserAgent(cdp)
            if (loginEmulation) {
                overrideDevice(cdp)
                installHooks(cdp)
                enableFetch(cdp)
            }
            onStatus("DevTools 已连接（端口 $port）")
        }

        private fun prepareAttached(cdp: CdpClient, sessionId: String) {
            enableDomains(cdp, sessionId)
            overrideUserAgent(cdp, sessionId)
            overrideDevice(cdp, sessionId)
            installHooks(cdp, sessionId)
            enableFetch(cdp, sessionId)
        }

        private fun navigateAndPrepare(
            cdp: CdpClient,
            process: Process,
            userDataDir: Path,
            port: Int,
            url: String,
            onStatus: (String) -> Unit,
        ) {
            var lastError: Throwable? = null
            repeat(4) { attempt ->
                try {
                    if (attempt > 0) {
                        onStatus("登录页目标崩溃，正在重连 DevTools（${attempt + 1}/4）…")
                        Thread.sleep(400L * attempt)
                        reattach(cdp, process, port, onStatus, userDataDir)
                    }
                    cdp.send(
                        "Page.navigate",
                        JsonObject().apply { addProperty("url", url) },
                    )
                    enableDomains(cdp)
                    overrideUserAgent(cdp)
                    overrideDevice(cdp)
                    installHooks(cdp)
                    enableFetch(cdp)
                    return
                } catch (error: Throwable) {
                    lastError = error
                    val timedOut = error.message.orEmpty().contains("超时")
                    if (!Chromium.isCdpTargetDead(error) && !timedOut) throw error
                    CrashLog.write(error)
                }
            }
            throw lastError ?: IOException("无法导航到登录页")
        }

        private fun reattach(
            cdp: CdpClient,
            process: Process,
            port: Int,
            onStatus: (String) -> Unit,
            userDataDir: Path? = null,
        ) {
            val http = Chromium.loopbackClient()
            val list = httpGet(http, "http://127.0.0.1:$port/json/list")
            val targetId = Chromium.pickPageTargetId(list, excludeId = cdp.attachedTargetId, preferBlank = false)
                ?: Chromium.pickPageTargetId(list, preferBlank = true)
                ?: throw IOException("没有可附加的页面")
            cdp.attachPage(targetId)
            enableDomains(cdp)
            overrideUserAgent(cdp)
            overrideDevice(cdp)
            installHooks(cdp)
            enableFetch(cdp)
            onStatus("DevTools 已重连（端口 $port）")
        }

        private fun enableTargetDiscovery(cdp: CdpClient) {
            runCatching {
                cdp.send("Target.setDiscoverTargets", JsonObject().apply { addProperty("discover", true) })
            }
            runCatching {
                cdp.send("Target.setAutoAttach", Chromium.autoAttachParams())
            }
        }

        private fun enableDomains(cdp: CdpClient, sessionId: String? = null) {
            sendOrRecover(cdp, "Runtime.enable", sessionId)
            sendOrRecover(cdp, "Page.enable", sessionId)
            runCatching { cdp.send("Inspector.enable", sessionId = sessionId) }
            sendOrRecover(cdp, "Network.enable", sessionId)
            runCatching {
                cdp.send(
                    "Page.setLifecycleEventsEnabled",
                    JsonObject().apply { addProperty("enabled", true) },
                    sessionId,
                    timeoutSec = 3,
                )
            }
        }

        private fun sendOrRecover(cdp: CdpClient, method: String, sessionId: String? = null) {
            try {
                cdp.send(method, sessionId = sessionId, timeoutSec = 20)
            } catch (error: Throwable) {
                if (!Chromium.isCdpTargetDead(error) && !Chromium.describeCdpFailure(method, error, 20).contains("超时")) {
                    throw error
                }
                Thread.sleep(400)
                cdp.send(method, sessionId = sessionId, timeoutSec = 20)
            }
        }

        private fun androidUserAgentMetadata(): JsonObject = JsonObject().apply {
            add(
                "brands",
                com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("brand", "Chromium")
                        addProperty("version", "131")
                    })
                    add(JsonObject().apply {
                        addProperty("brand", "Google Chrome")
                        addProperty("version", "131")
                    })
                    add(JsonObject().apply {
                        addProperty("brand", "Not_A Brand")
                        addProperty("version", "24")
                    })
                },
            )
            addProperty("fullVersion", "131.0.6778.135")
            addProperty("platform", "Android")
            addProperty("platformVersion", "14.0.0")
            addProperty("architecture", "")
            addProperty("model", "Pixel 8")
            addProperty("mobile", true)
            addProperty("bitness", "64")
            addProperty("wow64", false)
        }

        private fun overrideUserAgent(cdp: CdpClient, sessionId: String? = null) {
            val params = JsonObject().apply {
                addProperty("userAgent", Chromium.ANDROID_UA)
                addProperty("acceptLanguage", "zh-CN,zh;q=0.9,en-US;q=0.8")
                addProperty("platform", "Linux armv8l")
                add("userAgentMetadata", androidUserAgentMetadata())
            }
            runCatching { cdp.send("Emulation.setUserAgentOverride", params, sessionId) }
            runCatching { cdp.send("Network.setUserAgentOverride", params, sessionId) }
            runCatching {
                cdp.send(
                    "Network.setExtraHTTPHeaders",
                    JsonObject().apply {
                        add(
                            "headers",
                            JsonObject().apply {
                                addProperty("sec-ch-ua-mobile", "?1")
                                addProperty("sec-ch-ua-platform", "\"Android\"")
                            },
                        )
                    },
                    sessionId,
                )
            }
        }

        private fun overrideDevice(cdp: CdpClient, sessionId: String? = null) {
            runCatching {
                cdp.send(
                    "Emulation.setDeviceMetricsOverride",
                    JsonObject().apply {
                        addProperty("width", 412)
                        addProperty("height", 915)
                        addProperty("deviceScaleFactor", 2.625)
                        addProperty("mobile", true)
                        addProperty("screenWidth", 412)
                        addProperty("screenHeight", 915)
                    },
                    sessionId,
                )
            }
            runCatching {
                cdp.send(
                    "Emulation.setTouchEmulationEnabled",
                    JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("maxTouchPoints", 5)
                    },
                    sessionId,
                )
            }
        }

        private fun installHooks(cdp: CdpClient, sessionId: String? = null) {
            runCatching {
                cdp.send(
                    "Runtime.addBinding",
                    JsonObject().apply { addProperty("name", "pixshaftCallback") },
                    sessionId,
                )
            }
            val script = Chromium.mobileHookScript()
            runCatching {
                cdp.send(
                    "Page.addScriptToEvaluateOnNewDocument",
                    JsonObject().apply { addProperty("source", script) },
                    sessionId,
                )
            }
            runCatching {
                cdp.send(
                    "Runtime.evaluate",
                    JsonObject().apply {
                        addProperty("expression", script)
                        addProperty("returnByValue", true)
                    },
                    sessionId,
                )
            }
        }

        private fun enableFetch(cdp: CdpClient, sessionId: String? = null) {
            fun patterns(vararg urls: String) = com.google.gson.JsonArray().apply {
                urls.forEach { url ->
                    add(JsonObject().apply { addProperty("urlPattern", url) })
                }
            }
            fun enable(list: com.google.gson.JsonArray): Boolean {
                return runCatching {
                    cdp.send(
                        "Fetch.enable",
                        JsonObject().apply { add("patterns", list) },
                        sessionId,
                    )
                }.onFailure { CrashLog.write("Fetch.enable failed: ${it.message}") }.isSuccess
            }
            val https = "*://app-api.pixiv.net/web/v1/users/auth/pixiv/callback*"
            if (!enable(patterns("pixiv://*", "shaft://*", "intent://*", https))) {
                enable(patterns(https))
            }
        }

        private fun drain(process: Process, logTail: ConcurrentLinkedDeque<String>) {
            thread(name = "chromium-out", isDaemon = true) {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logTail.addLast(line)
                            while (logTail.size > 80) logTail.pollFirst()
                        }
                    }
                }
            }
        }

        private fun waitForPageWebSocket(
            process: Process,
            userDataDir: Path?,
            requestedPort: Int,
            logTail: ConcurrentLinkedDeque<String>,
            excludeTargetId: String? = null,
        ): Triple<Int, String, String> {
            val http = Chromium.loopbackClient()
            var lastError: Throwable? = null
            var boundPort = requestedPort.takeIf { it > 0 } ?: 0
            var browserWs = ""
            repeat(80) { iteration ->
                val alive = process.isAlive
                // Re-probe every iteration: a stale DevToolsActivePort from a
                // previous crashed instance must not latch onto this loop, the
                // fresh browser rewrites the file once its socket is live.
                val logBlob = logTail.joinToString("\n")
                boundPort = Chromium.parseDevToolsListeningPort(logBlob) ?: 0
                browserWs = Chromium.parseDevToolsListeningWebSocket(logBlob) ?: browserWs
                if (userDataDir != null) {
                    val file = userDataDir.resolve("DevToolsActivePort")
                    if (Files.isRegularFile(file)) {
                        val text = runCatching { Files.readString(file) }.getOrNull().orEmpty()
                        if (boundPort <= 0) {
                            boundPort = Chromium.parseDevToolsActivePort(text) ?: 0
                        }
                        if (boundPort > 0) {
                            browserWs = Chromium.parseBrowserWebSocket(boundPort, text) ?: browserWs
                        }
                    }
                }
                if (Chromium.deadWithoutDevTools(alive, boundPort, iteration)) {
                    val extra = logTail.toList().takeLast(12).joinToString("\n")
                    val code = runCatching { process.exitValue() }.getOrNull()
                    val detail = when {
                        extra.isNotBlank() -> ":\n" + extra
                        lastError?.message != null -> ": " + lastError.message
                        else -> ""
                    }
                    throw IOException(
                        "Chromium 进程已退出，DevTools 未就绪" +
                            (code?.let { "（exit " + it + "）" } ?: "") +
                            detail,
                    )
                }
                if (boundPort > 0) {
                    try {
                        val list = httpGet(http, "http://127.0.0.1:$boundPort/json/list")
                        val pageId = Chromium.pickPageTargetId(
                            list,
                            excludeId = excludeTargetId,
                            preferBlank = excludeTargetId == null,
                        )
                        if (browserWs.isBlank()) {
                            val version = httpGet(http, "http://127.0.0.1:$boundPort/json/version")
                            browserWs = Chromium.parseBrowserWebSocketFromVersion(version).orEmpty()
                        }
                        if (pageId != null && browserWs.isNotBlank()) {
                            return Triple(boundPort, browserWs, pageId)
                        }
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                Thread.sleep(200)
            }
            throw IOException(
                "无法连接 Chromium DevTools 端口 ${boundPort.takeIf { it > 0 } ?: requestedPort}: " +
                    (lastError?.message ?: "超时"),
            )
        }

        private fun httpGet(http: OkHttpClient, url: String): String {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} $url")
                return response.body?.string().orEmpty()
            }
        }
    }
}

object ChromiumHttp {
    private val lock = Any()
    private val session = AtomicReference<ChromiumSession?>(null)
    private val forceChromium = ThreadLocal.withInitial { false }

    fun shouldIntercept(host: String): Boolean = host.lowercase() in PixivDns.API_HOSTS

    /**
     * Application interceptor. Must be installed **after** identity / token
     * interceptors: this short-circuits API hosts onto a headless Chromium
     * fetch (QUIC) and never calls later interceptors.
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (forceChromium.get()) {
            return@Interceptor execute(request)
        }
        val host = request.url.host.lowercase()
        val api = shouldIntercept(host)
        if (Chromium.directConnect && api) {
            return@Interceptor try {
                execute(request)
            } catch (error: IOException) {
                try {
                    chain.proceed(request)
                } catch (_: IOException) {
                    throw error
                }
            }
        }
        try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (!api) throw error
            execute(request)
        }
    }

    fun warmAsync() {
        thread(name = "chromium-warm", isDaemon = true) {
            runCatching {
                execute(
                    Request.Builder()
                        .url(Chromium.originWarmupUrl("https://app-api.pixiv.net"))
                        .get()
                        .build(),
                ).close()
            }.onFailure { CrashLog.write("chromium warm failed: ${it.message}") }
        }
    }

    private fun ensure(): ChromiumSession {
        synchronized(lock) {
            session.get()?.let { return it }
            val fresh = ChromiumSession.launchHeadless()
            session.set(fresh)
            return fresh
        }
    }

    fun <T> withSession(existing: ChromiumSession, block: () -> T): T {
        val previous = session.getAndSet(existing)
        val previousForce = forceChromium.get()
        forceChromium.set(true)
        try {
            return block()
        } finally {
            forceChromium.set(previousForce)
            session.compareAndSet(existing, previous)
        }
    }

    fun execute(request: Request): Response {
        synchronized(lock) {
            val op: (ChromiumSession) -> Response =
                if (Chromium.isTokenPost(request)) {
                    { it.executeFormPost(request) }
                } else {
                    { it.execute(request) }
                }
            val current = session.get()
            if (current != null) {
                try {
                    return op(current)
                } catch (error: Exception) {
                    CrashLog.write("Chromium session fetch failed: ${error.message}")
                    runCatching { current.close() }
                    session.compareAndSet(current, null)
                }
            }
            return op(ensure())
        }
    }

    fun shutdown() {
        recycleSession()
        Chromium.nukeAll()
    }

    /** Drop the headless fetch browser so the next request relaunches with current proxy / QUIC flags. */
    fun recycleSession() {
        synchronized(lock) {
            val current = session.getAndSet(null)
            if (current != null) runCatching { current.close() }
        }
    }
}

internal class CdpClient(private var wsUrl: String) {
    private val gson = Gson()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<(String, JsonObject) -> Unit>()
    private val nextId = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private val failure = AtomicReference<Throwable?>(null)
    private var socket: WebSocket? = null
    @Volatile var sessionId: String? = null
    @Volatile var attachedTargetId: String? = null
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val currentWsUrl: String
        get() = wsUrl

    fun connect() {
        failure.set(null)
        val latch = CountDownLatch(1)
        val gen = generation.get()
        socket = http.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation.get() == gen) handle(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (generation.get() == gen) handle(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (generation.get() != gen) return
                    failure.set(t)
                    latch.countDown()
                    failAll(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (generation.get() != gen) return
                    failAll(IOException("CDP closed: $reason"))
                }
            },
        )
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IOException("CDP WebSocket 连接超时")
        }
        failure.get()?.let { throw IOException("CDP 连接失败: ${it.message}", it) }
    }

    fun reconnect(nextWsUrl: String) {
        generation.incrementAndGet()
        failAll(IOException("CDP reattach"))
        sessionId = null
        attachedTargetId = null
        val old = socket
        socket = null
        runCatching { old?.close(1000, "reattach") }
        wsUrl = nextWsUrl
        connect()
    }

    fun attachPage(targetId: String, timeoutSec: Long = 10) {
        val result = send(
            "Target.attachToTarget",
            JsonObject().apply {
                addProperty("targetId", targetId)
                addProperty("flatten", true)
            },
            timeoutSec = timeoutSec,
        )
        val sid = result.get("sessionId")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IOException("Target.attachToTarget 没有 sessionId")
        sessionId = sid
        attachedTargetId = targetId
    }

    /**
     * json/list's first about:blank is a pid=0 placeholder. Runtime.enable on it
     * never replies. Create a real renderer and attach by targetId — Edge 150
     * reports pid=0 on that created page too, so waiting for a live pid hangs.
     */
    fun createAndAttachBlank(timeoutSec: Long = 15) {
        try {
            send("Target.setAutoAttach", Chromium.autoAttachParams(), timeoutSec = 8)
        } catch (_: Exception) {
            send(
                "Target.setAutoAttach",
                Chromium.autoAttachParams(pageOnly = false),
                timeoutSec = 8,
            )
        }
        val created = send(
            "Target.createTarget",
            JsonObject().apply { addProperty("url", "about:blank") },
            timeoutSec = 10,
        )
        val tid = created.get("targetId")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IOException("没有可用的 Chromium 渲染进程（Target.createTarget 没有 targetId）")
        try {
            attachPage(tid, timeoutSec = timeoutSec)
        } catch (error: Exception) {
            throw IOException("没有可用的 Chromium 渲染进程（Edge 初始 about:blank pid=0）", error)
        }
    }

    fun addListener(listener: (String, JsonObject) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String, JsonObject) -> Unit) {
        listeners -= listener
    }

    fun send(
        method: String,
        params: JsonObject? = null,
        sessionId: String? = null,
        timeoutSec: Long = 8,
    ): JsonObject {
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val sid = when {
            !sessionId.isNullOrBlank() -> sessionId
            Chromium.cdpUsesBrowserSession(method) -> null
            else -> this.sessionId
        }
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
            if (!sid.isNullOrBlank()) addProperty("sessionId", sid)
        }
        val ws = socket ?: throw IOException("CDP 未连接")
        if (!ws.send(gson.toJson(payload))) {
            pending.remove(id)
            throw IOException("CDP 发送失败: $method")
        }
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (error: Exception) {
            pending.remove(id)
            throw IOException(Chromium.describeCdpFailure(method, error, timeoutSec), error)
        }
    }

    fun close() {
        failAll(IOException("CDP closed"))
        runCatching { socket?.cancel() }
        runCatching { http.dispatcher.executorService.shutdownNow() }
        runCatching { http.connectionPool.evictAll() }
    }

    private fun handle(text: String) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val idEl = obj.get("id")
        if (idEl != null && !idEl.isJsonNull) {
            val id = runCatching { idEl.asInt }.getOrNull()
            val future = id?.let { pending.remove(it) }
            if (future != null) {
                val error = obj.get("error")
                if (error != null && !error.isJsonNull) {
                    val message = if (error.isJsonObject) {
                        error.asJsonObject.get("message")?.asString?.takeIf { it.isNotBlank() }
                            ?: error.toString()
                    } else {
                        error.asString
                    }
                    future.completeExceptionally(IOException(message))
                } else {
                    val result = obj.getAsJsonObject("result") ?: JsonObject()
                    future.complete(result)
                }
            }
        }
        val method = obj.get("method")?.asString
        if (method != null) {
            val params = obj.getAsJsonObject("params") ?: JsonObject()
            listeners.forEach { runCatching { it(method, params) } }
        }
    }

    private fun failAll(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}
