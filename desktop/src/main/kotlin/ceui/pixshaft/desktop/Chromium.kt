package ceui.pixshaft.desktop

import ceui.pixshaft.shared.net.PixivClientIdentity
import ceui.pixshaft.shared.net.PixivDns
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.net.ServerSocket
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

    fun detectProxy(): String? {
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
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
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
            if (page.get("webSocketDebuggerUrl")?.asString == cdp.currentWsUrl) continue
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
        val ws = best.get("webSocketDebuggerUrl")?.asString ?: return
        if (ws == cdp.currentWsUrl) return
        val stage = ceui.pixshaft.shared.auth.PixivOAuth.oauthStage(best.get("url")?.asString)
        if (stage < 20) return
        onStatus("登录跳到了新页面，正在跟随…")
        runCatching { reattachTo(ws, onStatus) }
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

    private fun reattachTo(ws: String, onStatus: (String) -> Unit) {
        cdp.reconnect(ws)
        preparePage(cdp, debugPort, onStatus)
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
              const init = { method: spec.method, headers, redirect: 'follow' };
              if (spec.bodyBase64) {
                const bin = atob(spec.bodyBase64);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                init.body = bytes;
              }
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
              return JSON.stringify({
                status: res.status,
                statusText: res.statusText,
                headers: outHeaders,
                bodyBase64: btoa(binary)
              });
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
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(parsed.get("statusText")?.asString ?: "OK")
            .headers(headerBuilder.build())
            .body(bytes.toResponseBody(mediaType))
            .build()
    }

    private fun ensureFetchOrigin(request: Request) {
        val origin = "${request.url.scheme}://${request.url.host}"
        val current = runCatching {
            cdp.send(
                "Runtime.evaluate",
                JsonObject().apply {
                    addProperty("expression", "location.origin")
                    addProperty("returnByValue", true)
                },
                timeoutSec = 3,
            )
        }.getOrNull()?.getAsJsonObject("result")?.get("value")?.asString
        if (current.equals(origin, ignoreCase = true)) return
        runCatching { cdp.send("Fetch.disable", timeoutSec = 3) }
        cdp.send(
            "Page.navigate",
            JsonObject().apply { addProperty("url", "$origin/") },
            timeoutSec = 10,
        )
        repeat(15) {
            Thread.sleep(200)
            val ready = runCatching {
                cdp.send(
                    "Runtime.evaluate",
                    JsonObject().apply {
                        addProperty("expression", "location.origin")
                        addProperty("returnByValue", true)
                    },
                    timeoutSec = 3,
                )
            }.getOrNull()?.getAsJsonObject("result")?.get("value")?.asString
            if (ready.equals(origin, ignoreCase = true)) return
        }
    }

    private fun recover(onStatus: (String) -> Unit) {
        onStatus("登录页崩溃，正在重连 DevTools…")
        reattach(cdp, debugPort, onStatus)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { cdp.close() }
        runCatching {
            localHttp.dispatcher.executorService.shutdown()
            localHttp.connectionPool.evictAll()
        }
        runCatching {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    companion object {
        fun launchLogin(url: String, onStatus: (String) -> Unit): ChromiumSession {
            onStatus("正在启动 Chromium 登录窗…")
            return launch(
                userDataDir = AppPaths.root.resolve("chromium-login"),
                url = url,
                headless = false,
                onStatus = onStatus,
            )
        }

        fun launchHeadless(): ChromiumSession =
            launch(
                userDataDir = AppPaths.root.resolve("chromium-net"),
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
            Files.createDirectories(userDataDir)
            writePreferences(userDataDir)
            val port = freePort()
            val args = mutableListOf(
                browser.toString(),
                "--user-data-dir=${userDataDir.toAbsolutePath()}",
                "--remote-debugging-port=$port",
                "--remote-allow-origins=*",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-sync",
                "--disable-background-networking",
                "--disable-popup-blocking",
                "--disable-features=Translate,MediaRouter",
                "--host-resolver-rules=${Chromium.hostResolverRules()}",
                "--enable-quic",
                "--origin-to-force-quic-on=${Chromium.originToForceQuicOn()}",
                "--disable-hang-monitor",
                "--disable-renderer-backgrounding",
            )
            if (!headless) {
                args += "--window-size=480,800"
            }
            args += Chromium.proxyArgs()
            if (headless) {
                args += "--headless=new"
                args += "--disable-gpu"
            }
            args += "about:blank"
            onStatus("打开 ${browser.fileName}（端口 $port）…")
            val process = ProcessBuilder(args)
                .directory(userDataDir.toFile())
                .redirectErrorStream(true)
                .start()
            val logTail = ConcurrentLinkedDeque<String>()
            drain(process, logTail)
            try {
                val pageWs = waitForPageWebSocket(port)
                val cdp = CdpClient(pageWs)
                cdp.connect()
                preparePage(cdp, port, onStatus, loginEmulation = !headless)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    onStatus("正在打开 Pixiv 登录页…")
                    navigateAndPrepare(cdp, port, url, onStatus)
                }
                onStatus("已打开登录窗，请在弹出的 Chromium 窗口完成登录")
                return ChromiumSession(process, cdp, port, url)
            } catch (error: Throwable) {
                val extra = logTail.toList().takeLast(20).joinToString("\n")
                CrashLog.write(error)
                if (extra.isNotBlank()) CrashLog.write("chromium-out:\n$extra")
                runCatching { process.destroyForcibly() }
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
            enableTargetDiscovery(cdp)
            enableDomains(cdp)
            if (loginEmulation) {
                overrideUserAgent(cdp)
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
                        reattach(cdp, port, onStatus)
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

        private fun reattach(cdp: CdpClient, port: Int, onStatus: (String) -> Unit) {
            cdp.reconnect(waitForPageWebSocket(port, excludeWs = cdp.currentWsUrl))
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
                cdp.send(
                    "Target.setAutoAttach",
                    JsonObject().apply {
                        addProperty("autoAttach", true)
                        addProperty("waitForDebuggerOnStart", false)
                        addProperty("flatten", true)
                    },
                )
            }
        }

        private fun enableDomains(cdp: CdpClient, sessionId: String? = null) {
            sendOrRecover(cdp, "Page.enable", sessionId)
            sendOrRecover(cdp, "Runtime.enable", sessionId)
            runCatching { cdp.send("Inspector.enable", sessionId = sessionId) }
            sendOrRecover(cdp, "Network.enable", sessionId)
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

        private fun freePort(): Int =
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                socket.localPort
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

        private fun waitForPageWebSocket(port: Int, excludeWs: String? = null): String {
            val http = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            var lastError: Throwable? = null
            repeat(80) {
                try {
                    val list = httpGet(http, "http://127.0.0.1:$port/json/list")
                    val pages = JsonParser.parseString(list).asJsonArray.mapNotNull { element ->
                        val obj = element.asJsonObject
                        val type = obj.get("type")?.asString.orEmpty()
                        val ws = obj.get("webSocketDebuggerUrl")?.asString ?: return@mapNotNull null
                        if (ws == excludeWs) return@mapNotNull null
                        if (type == "page" || type == "app" || type == "webview") obj else null
                    }
                    val blank = pages.firstOrNull { obj ->
                        val url = obj.get("url")?.asString.orEmpty()
                        url == "about:blank" || url.isBlank()
                    }
                    val live = pages
                        .filter { obj ->
                            val url = obj.get("url")?.asString.orEmpty()
                            url.isNotBlank() &&
                                url != "about:blank" &&
                                !url.startsWith("chrome-error://") &&
                                !url.startsWith("edge-error://")
                        }
                        .maxByOrNull { obj ->
                            ceui.pixshaft.shared.auth.PixivOAuth.oauthStage(obj.get("url")?.asString)
                        }
                    val chosen = if (excludeWs == null) {
                        blank ?: live ?: pages.firstOrNull()
                    } else {
                        live ?: blank ?: pages.firstOrNull()
                    }
                    chosen?.get("webSocketDebuggerUrl")?.asString?.let { return it }
                    runCatching { httpGet(http, "http://127.0.0.1:$port/json/version") }
                } catch (t: Throwable) {
                    lastError = t
                }
                Thread.sleep(200)
            }
            throw IOException("无法连接 Chromium DevTools 端口 $port: ${lastError?.message ?: "超时"}")
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

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (forceChromium.get()) {
            return@Interceptor execute(request)
        }
        try {
            chain.proceed(request)
        } catch (error: IOException) {
            val host = request.url.host
            if (!PixivDns.isPinnedHost(host) || host.endsWith("pximg.net")) throw error
            execute(request)
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
            val current = session.get()
            if (current != null) {
                try {
                    return current.execute(request)
                } catch (error: Exception) {
                    CrashLog.write("Chromium session fetch failed: ${error.message}")
                }
            }
            val fresh = ChromiumSession.launchHeadless().also { session.set(it) }
            return fresh.execute(request)
        }
    }

    fun shutdown() {
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
    private val http = OkHttpClient.Builder()
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
        val old = socket
        socket = null
        runCatching { old?.close(1000, "reattach") }
        wsUrl = nextWsUrl
        connect()
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
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
            if (!sessionId.isNullOrBlank()) addProperty("sessionId", sessionId)
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
        runCatching { socket?.close(1000, "bye") }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun handle(text: String) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val method = obj.get("method")?.asString
        if (method != null) {
            val params = obj.getAsJsonObject("params") ?: JsonObject()
            listeners.forEach { runCatching { it(method, params) } }
            return
        }
        val id = obj.get("id")?.asInt ?: return
        val future = pending.remove(id) ?: return
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

    private fun failAll(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}
