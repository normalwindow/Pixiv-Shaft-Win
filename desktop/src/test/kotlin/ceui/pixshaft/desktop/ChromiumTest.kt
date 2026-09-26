package ceui.pixshaft.desktop

import ceui.pixshaft.shared.net.PixivDns
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ChromiumTest {
    @Test
    fun tokenPostDetection() {
        val formRequest = Request.Builder()
            .url("https://oauth.secure.pixiv.net/auth/token")
            .post(FormBody.Builder().add("grant_type", "refresh_token").build())
            .build()
        assertTrue(Chromium.isTokenPost(formRequest))
        val getAppApi = Request.Builder()
            .url("https://app-api.pixiv.net/v1/illust/detail?illust_id=1")
            .get()
            .build()
        assertFalse(Chromium.isTokenPost(getAppApi))
        val getOauth = Request.Builder()
            .url("https://oauth.secure.pixiv.net/auth/token")
            .get()
            .build()
        assertFalse(Chromium.isTokenPost(getOauth))
        val bodyless = Request.Builder()
            .url("https://oauth.secure.pixiv.net/auth/token")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        assertFalse(Chromium.isTokenPost(bodyless))
    }

    @Test
    fun formPostSpecEncodesFields() {
        val form = FormBody.Builder()
            .add("client_id", "MOBrBDS8blbauoSck0ZfDbtuzpyT")
            .add("grant_type", "refresh_token")
            .build()
        val spec = Chromium.formPostSpec("https://oauth.secure.pixiv.net/auth/token", form)
        assertEquals("https://oauth.secure.pixiv.net/auth/token", spec.get("url").asString)
        val fields = spec.getAsJsonArray("fields")
        assertEquals(2, fields.size())
        assertEquals("grant_type", fields[1].asJsonObject.get("name").asString)
        assertEquals("refresh_token", fields[1].asJsonObject.get("value").asString)
    }

    @Test
    fun parsesTokenPageStateAndCaptcha() {
        val (href, ready, text) = Chromium.parseTokenPageState(
            """{"href":"https://oauth.secure.pixiv.net/auth/token","ready":"complete","text":"{\"access_token\":\"a\"}"}""",
        )
        assertTrue(href.endsWith("/auth/token"))
        assertEquals("complete", ready)
        assertTrue(text.contains("access_token"))
        assertTrue(Chromium.parseTokenPageState(null).first.isEmpty())
        assertTrue(Chromium.parseTokenPageState("not json").second.isEmpty())
        assertTrue(Chromium.looksLikeCaptcha("为了确认您是正规用户，请进行CAPTCHA验证。"))
        assertFalse(Chromium.looksLikeCaptcha("""{"access_token":"a"}"""))
    }

    @Test
    fun hostResolverPinsCloudflare() {
        val rules = Chromium.hostResolverRules()
        assertTrue(rules.contains("MAP app-api.pixiv.net ${PixivDns.CF_IP_PRIMARY}"))
        assertTrue(rules.contains("MAP oauth.secure.pixiv.net ${PixivDns.CF_IP_PRIMARY}"))
        assertTrue(rules.contains("MAP accounts.pixiv.net ${PixivDns.CF_IP_PRIMARY}"))
        assertTrue(rules.contains("MAP i.pximg.net ${PixivDns.FALLBACK_IMAGE_IPS.first()}"))
    }

    @Test
    fun originToForceQuicListsApiHostsOnly() {
        val origins = Chromium.originToForceQuicOn()
        assertTrue(origins.contains("app-api.pixiv.net:443"))
        assertTrue(origins.contains("oauth.secure.pixiv.net:443"))
        assertTrue(origins.contains("accounts.pixiv.net:443"))
        assertFalse(origins.contains("i.pximg.net"))
        assertFalse(origins.contains(" "))
    }

    @Test
    fun headlessLaunchUsesNewHeadlessAndAutoDebugPort() {
        val edge = java.nio.file.Path.of("D:\\Sware\\Edge\\Edge\\Application\\msedge.exe")
        val args = Chromium.browserLaunchArgs(
            browser = edge,
            userDataDir = java.nio.file.Path.of("C:\\tmp\\chromium-net"),
            cacheDir = java.nio.file.Path.of("C:\\tmp\\chromium-cache"),
            crashDir = java.nio.file.Path.of("C:\\tmp\\chromium-crash"),
            debugPort = 0,
            headless = true,
        )
        assertTrue(args.contains("--headless=new"))
        assertFalse(args.any { it == "--headless" })
        assertFalse(args.contains("--disable-gpu"))
        assertTrue(args.contains("--remote-debugging-port=0"))
        assertTrue(args.contains("--remote-debugging-address=127.0.0.1"))
        assertTrue(args.contains("--edge-skip-compat-layer-relaunch"))
        assertTrue(args.contains("--no-proxy-server"))
        assertTrue(args.contains("--disable-component-update"))
        assertTrue(args.contains("--metrics-recording-only"))
        assertTrue(args.contains("--disk-cache-size=67108864"))
        assertTrue(args.any { it.contains("EdgeWallet") })
        assertTrue(args.any { it.startsWith("--origin-to-force-quic-on=") })
        val chrome = Chromium.browserLaunchArgs(
            browser = java.nio.file.Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
            userDataDir = java.nio.file.Path.of("C:\\tmp\\chromium-net"),
            cacheDir = java.nio.file.Path.of("C:\\tmp\\chromium-cache"),
            crashDir = java.nio.file.Path.of("C:\\tmp\\chromium-crash"),
            debugPort = 0,
            headless = false,
        )
        assertFalse(chrome.contains("--headless=new"))
        assertFalse(chrome.contains("--edge-skip-compat-layer-relaunch"))
        assertTrue(chrome.contains("--window-size=480,800"))
    }

    @Test
    fun originWarmupStaysOnHostAndSkipsHomepage() {
        assertTrue(Chromium.originWarmupUrl("https://app-api.pixiv.net") == "https://app-api.pixiv.net/robots.txt")
        assertTrue(Chromium.originWarmupUrl("https://www.pixiv.net/") == "https://www.pixiv.net/robots.txt")
        assertTrue(Chromium.protocolOf("h3") == okhttp3.Protocol.QUIC)
        assertTrue(Chromium.protocolOf("h2") == okhttp3.Protocol.HTTP_2)
        assertTrue(Chromium.protocolOf("http/1.1") == okhttp3.Protocol.HTTP_1_1)
    }

    @Test
    fun parsesDevToolsActivePortFile() {
        assertTrue(Chromium.parseDevToolsActivePort("14936\n/devtools/browser/abc") == 14936)
        assertTrue(Chromium.parseDevToolsActivePort("0\n/devtools/browser/abc") == null)
        assertTrue(Chromium.parseDevToolsActivePort("") == null)
        assertTrue(
            Chromium.parseDevToolsListeningPort(
                "DevTools listening on ws://127.0.0.1:14936/devtools/browser/43ad8e6f",
            ) == 14936,
        )
        assertTrue(Chromium.parseDevToolsListeningPort("nope") == null)
        assertEquals(
            "ws://127.0.0.1:14936/devtools/browser/abc",
            Chromium.parseBrowserWebSocket(14936, "14936\n/devtools/browser/abc"),
        )
        assertEquals(
            "ws://127.0.0.1:14936/devtools/browser/43ad8e6f",
            Chromium.parseDevToolsListeningWebSocket(
                "DevTools listening on ws://127.0.0.1:14936/devtools/browser/43ad8e6f",
            ),
        )
        assertTrue(Chromium.cdpUsesBrowserSession("Target.attachToTarget"))
        assertTrue(Chromium.cdpUsesBrowserSession("Browser.getVersion"))
        assertTrue(!Chromium.cdpUsesBrowserSession("Runtime.enable"))
        val listJson = """
            [
              {"id":"ext","type":"background_page","url":"chrome-extension://x/y"},
              {"id":"blank","type":"page","url":"about:blank"}
            ]
        """.trimIndent()
        assertEquals("blank", Chromium.pickPageTargetId(listJson))
        val zombie = com.google.gson.JsonParser.parseString(
            """{"targetId":"z","type":"page","url":"","pid":0}""",
        ).asJsonObject
        val createdBlank = com.google.gson.JsonParser.parseString(
            """{"targetId":"c","type":"page","url":"about:blank","pid":0}""",
        ).asJsonObject
        val live = com.google.gson.JsonParser.parseString(
            """{"targetId":"l","type":"page","url":"about:blank","pid":20376}""",
        ).asJsonObject
        assertTrue(!Chromium.isLivePageTarget(zombie))
        assertTrue(Chromium.isLivePageTarget(createdBlank))
        assertTrue(Chromium.isLivePageTarget(live))
        val auto = Chromium.autoAttachParams()
        assertTrue(auto.get("flatten").asBoolean)
        assertEquals("page", auto.getAsJsonArray("filter")[0].asJsonObject.get("type").asString)
        assertTrue(Chromium.autoAttachParams(pageOnly = false).get("filter") == null)
    }

    @Test
    fun parsesAppPathRegOutput() {
        val output = """
            HKEY_CURRENT_USER\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe
                (Default)    REG_SZ    D:\Sware\Edge\Edge\Application\msedge.exe
        """.trimIndent()
        val path = Chromium.parseRegDefault(output)
        assertTrue(path.toString().replace('/', '\\').endsWith("msedge.exe"))
    }

    @Test
    fun mergesProtocolHandlerWithoutWipingProfile() {
        val existing = """{"profile":{"name":"PixShaft"},"protocol_handler":{"excluded_schemes":{"mailto":true}}}"""
        val merged = Chromium.mergeProtocolHandlerPrefs(existing)
        val root = JsonParser.parseString(merged).asJsonObject
        assertTrue(root.getAsJsonObject("profile").get("name").asString == "PixShaft")
        val protocol = root.getAsJsonObject("protocol_handler")
        assertFalse(protocol.getAsJsonObject("excluded_schemes").get("pixiv").asBoolean)
        assertFalse(protocol.getAsJsonObject("excluded_schemes").get("shaft").asBoolean)
        assertTrue(protocol.getAsJsonObject("excluded_schemes").get("mailto").asBoolean)
        val pairs = protocol.getAsJsonObject("allowed_origin_protocol_pairs")
        assertTrue(pairs.getAsJsonObject("https://app-api.pixiv.net").get("pixiv").asBoolean)
        assertTrue(pairs.getAsJsonObject("https://www.pixiv.net").get("pixiv").asBoolean)
    }

    @Test
    fun parsesWindowsProxyReg() {
        val enabled = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings
                ProxyEnable    REG_DWORD    0x1
                ProxyServer    REG_SZ    127.0.0.1:7890
        """.trimIndent()
        assertTrue(Chromium.parseWindowsProxyReg(enabled) == "http://127.0.0.1:7890")
        val disabled = """
            ProxyEnable    REG_DWORD    0x0
            ProxyServer    REG_SZ    127.0.0.1:7890
        """.trimIndent()
        assertTrue(Chromium.parseWindowsProxyReg(disabled) == null)
        assertTrue(Chromium.normalizeProxyServer("http=127.0.0.1:7890;https=127.0.0.1:7890") == "http://127.0.0.1:7890")
        assertTrue(Chromium.normalizeProxyServer("socks5://127.0.0.1:7891") == "socks5://127.0.0.1:7891")
    }

    @Test
    fun mobileHookReportsCallbackAndSpoofsUa() {
        val script = Chromium.mobileHookScript()
        assertTrue(script.contains("pixshaftCallback"))
        assertTrue(script.contains("userAgentData"))
        assertTrue(script.contains("PIXSHAFT_CALLBACK"))
        assertTrue(script.contains("Android"))
    }

    @Test
    fun loopbackClientNeverUsesSystemProxy() {
        val client = Chromium.loopbackClient()
        assertTrue(client.proxy == java.net.Proxy.NO_PROXY)
    }

    @Test
    fun parsesJavaProxy() {
        val http = Chromium.parseJavaProxy("127.0.0.1:7890")
        assertTrue(http != null)
        assertTrue(http!!.type() == java.net.Proxy.Type.HTTP)
        val socks = Chromium.parseJavaProxy("socks5://127.0.0.1:7891")
        assertTrue(socks != null)
        assertTrue(socks!!.type() == java.net.Proxy.Type.SOCKS)
        assertTrue(Chromium.parseJavaProxy(null) == null)
    }

    @Test
    fun loginReturnKeepsFirstCallback() {
        val channel = LoginReturnChannel()
        assertTrue(channel.offer("pixiv://account/login?code=one"))
        assertTrue(!channel.offer("pixiv://account/login?code=two"))
        assertTrue(channel.uri?.contains("code=one") == true)
        assertTrue(!channel.offer("https://example.com"))
    }

    @Test
    fun webAuthHostFindsExplicitProperty() {
        val previous = System.getProperty("pixshaft.webauth")
        val exe = File.createTempFile("PixShaftWebAuth", ".exe")
        try {
            System.setProperty("pixshaft.webauth", exe.absolutePath)
            assertTrue(WebAuthHost.findExe()?.toAbsolutePath().toString().equals(exe.toPath().toAbsolutePath().toString(), true))
        } finally {
            if (previous == null) System.clearProperty("pixshaft.webauth") else System.setProperty("pixshaft.webauth", previous)
            exe.delete()
        }
    }

    @Test
    fun webAuthResultParsesTokenLine() {
        val result = WebAuthHost.parseResult("TOKEN\t{\"access_token\":\"a\",\"refresh_token\":\"b\"}")
        assertTrue(result.tokenJson?.contains("access_token") == true)
        val token = WebAuthHost.parseToken(result.tokenJson!!)
        assertTrue(token.access_token == "a")
        assertTrue(token.refresh_token == "b")
    }

    @Test
    fun loginReturnCancelUnblocksWaiter() {
        val channel = LoginReturnChannel()
        assertFalse(channel.isCancelled)
        channel.cancel()
        assertTrue(channel.isCancelled)
        assertTrue(channel.isComplete)
        assertTrue(!channel.offer("pixiv://account/login?code=late"))
        assertTrue(channel.uri == null)
        assertTrue(channel.await(1))
    }

    @Test
    fun describesTimeoutWithoutNullMessage() {
        val nested = java.util.concurrent.ExecutionException(
            java.util.concurrent.TimeoutException(),
        )
        val text = Chromium.describeCdpFailure("Runtime.evaluate", nested, 20)
        assertTrue(text.contains("超时"))
        assertTrue(!text.contains("null"))
        assertTrue(text.contains("Runtime.evaluate"))
        val enable = Chromium.describeCdpFailure("Page.enable", nested, 8)
        assertTrue(enable.contains("超时"))
        assertTrue(!enable.contains("打开 PixShaft"))
    }

    @Test
    fun detectsTargetCrashedFromNestedIoException() {
        val nested = IOException(
            """CDP Network.enable 超时/失败: java.io.IOException: {"code":-32000,"message":"Target crashed"}""",
            IOException("""{"code":-32000,"message":"Target crashed"}"""),
        )
        assertTrue(Chromium.isCdpTargetDead(nested))
        assertFalse(Chromium.isCdpTargetDead(IOException("CDP WebSocket 连接超时")))
    }

    @Test
    fun pruneProfileJunkDropsEdgeComponentsAndKeepsCookies() {
        val root = java.nio.file.Files.createTempDirectory("pixshaft-profile-")
        try {
            val cookies = root.resolve("Default").resolve("Cookies")
            java.nio.file.Files.createDirectories(cookies.parent)
            java.nio.file.Files.writeString(cookies, "keep")
            java.nio.file.Files.createDirectories(root.resolve("component_crx_cache"))
            java.nio.file.Files.createDirectories(root.resolve("ProvenanceData"))
            java.nio.file.Files.createDirectories(root.resolve("Edge Wallet"))
            java.nio.file.Files.writeString(root.resolve("BrowserMetrics-spare.pma"), "x")
            Chromium.pruneProfileJunk(root)
            assertTrue(java.nio.file.Files.isRegularFile(cookies))
            assertTrue(!java.nio.file.Files.exists(root.resolve("component_crx_cache")))
            assertTrue(!java.nio.file.Files.exists(root.resolve("ProvenanceData")))
            assertTrue(!java.nio.file.Files.exists(root.resolve("Edge Wallet")))
            assertTrue(!java.nio.file.Files.exists(root.resolve("BrowserMetrics-spare.pma")))
        } finally {
            AppPaths.deleteQuietly(root)
        }
    }

    @Test
    fun chromiumHelperProfilesLiveUnderCacheRoot() {
        val cache = AppPaths.cacheRoot()
        assertTrue(AppPaths.chromiumNetDir().startsWith(cache))
        assertTrue(AppPaths.chromiumLoginDir().startsWith(cache))
        assertEquals("chromium-net", AppPaths.chromiumNetDir().fileName.toString())
        assertEquals("chromium-login", AppPaths.chromiumLoginDir().fileName.toString())
    }

    @Test
    fun newProfileDirIsUniqueChild() {
        val parent = java.nio.file.Files.createTempDirectory("pixshaft-net-")
        try {
            val a = Chromium.newProfileDir(parent)
            val b = Chromium.newProfileDir(parent)
            assertTrue(a.startsWith(parent))
            assertTrue(b.startsWith(parent))
            assertTrue(a.fileName.toString().startsWith("s-"))
            assertTrue(a != b)
            assertTrue(java.nio.file.Files.isDirectory(a))
        } finally {
            AppPaths.deleteQuietly(parent)
        }
    }

    @Test
    fun deadWithoutDevToolsWaitsForGraceThenGivesUp() {
        assertTrue(!Chromium.deadWithoutDevTools(processAlive = true, boundPort = 0, iteration = 99))
        assertTrue(!Chromium.deadWithoutDevTools(processAlive = false, boundPort = 9222, iteration = 99))
        assertTrue(!Chromium.deadWithoutDevTools(processAlive = false, boundPort = 0, iteration = 3))
        assertTrue(Chromium.deadWithoutDevTools(processAlive = false, boundPort = 0, iteration = 8))
    }
}
