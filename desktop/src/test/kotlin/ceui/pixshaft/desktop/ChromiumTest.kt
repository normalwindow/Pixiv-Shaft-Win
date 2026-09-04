package ceui.pixshaft.desktop

import ceui.pixshaft.shared.net.PixivDns
import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChromiumTest {
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
}
