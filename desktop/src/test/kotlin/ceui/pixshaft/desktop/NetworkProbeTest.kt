package ceui.pixshaft.desktop

import ceui.pixshaft.desktop.net.NetworkProbe
import ceui.pixshaft.shared.net.NetCidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeTest {
    @Test
    fun chromiumInterceptorOnlyCoversApiHosts() {
        assertTrue(ChromiumHttp.shouldIntercept("app-api.pixiv.net"))
        assertTrue(ChromiumHttp.shouldIntercept("oauth.secure.pixiv.net"))
        assertTrue(ChromiumHttp.shouldIntercept("www.pixiv.net"))
        assertTrue(ChromiumHttp.shouldIntercept("api.fanbox.cc"))
        assertFalse(ChromiumHttp.shouldIntercept("i.pximg.net"))
        assertFalse(ChromiumHttp.shouldIntercept("pixshaft.com"))
    }

    @Test
    fun networkSettingsDetectDirectAndDns() {
        val base = DesktopSettings()
        assertFalse(AppGraph.networkSettingsChanged(base, base.copy(themeMode = 2)))
        assertTrue(AppGraph.networkSettingsChanged(base, base.copy(directConnect = false)))
        assertTrue(AppGraph.networkSettingsChanged(base, base.copy(useSecureDns = false)))
        assertTrue(AppGraph.networkSettingsChanged(base, base.copy(imageHostMode = 1)))
    }

    @Test
    fun dnsAndProxyFollowDirectConnect() {
        val on = DesktopSettings(directConnect = true, useSecureDns = false)
        val off = DesktopSettings(directConnect = false, useSecureDns = false)
        assertTrue(AppGraph.dnsFor(on) === ceui.pixshaft.shared.net.PixivDns)
        assertTrue(AppGraph.dnsFor(off) === ceui.pixshaft.shared.net.IPv4OnlyDns)
        assertEquals(java.net.Proxy.NO_PROXY, AppGraph.proxyFor(on))
        assertTrue(AppGraph.useDirectPximg(on))
        assertFalse(AppGraph.useDirectPximg(on.copy(imageHostMode = 1)))
        assertFalse(AppGraph.useDirectPximg(off))
    }

    @Test
    fun imageMagicAndLabels() {
        assertEquals("JPEG", NetworkProbe.imageMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0, 0, 0, 0)))
        assertEquals("PNG", NetworkProbe.imageMagic(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0, 0, 0, 0, 0, 0)))
        assertEquals(null, NetworkProbe.imageMagic(byteArrayOf(1, 2, 3)))
        assertEquals("网络通畅", NetworkProbe.overallLabel(NetCidr.Overall.CLEAN))
        assertEquals("网络不可用", NetworkProbe.overallLabel(NetCidr.Overall.NETWORK_DOWN))
        assertEquals("1KB", NetworkProbe.formatBytes(1024))
    }
}
