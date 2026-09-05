package ceui.pixshaft.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class NetCidrTest {
    @Test
    fun cloudflareAnycastHitsPixivCidr() {
        assertTrue(NetCidr.isIpInCidr("104.18.42.239", "104.16.0.0/13"))
        assertTrue(NetCidr.ipv4InOfficialCidrs("104.18.42.239", NetCidr.PIXIV_CIDRS))
        assertTrue(NetCidr.ipv4InOfficialCidrs("172.64.145.17", NetCidr.PIXIV_CIDRS))
    }

    @Test
    fun dropboxFacebookPoisonMissesPixivCidr() {
        assertFalse(NetCidr.ipv4InOfficialCidrs("162.125.1.8", NetCidr.PIXIV_CIDRS))
        assertFalse(NetCidr.ipv4InOfficialCidrs("31.13.64.35", NetCidr.PIXIV_CIDRS))
    }

    @Test
    fun pximgOfficialHits() {
        assertTrue(NetCidr.ipv4InOfficialCidrs("210.140.139.134", NetCidr.PXIMG_CIDRS))
        assertFalse(NetCidr.ipv4InOfficialCidrs("104.18.42.239", NetCidr.PXIMG_CIDRS))
    }

    @Test
    fun clashFakeIpRange() {
        assertTrue(NetCidr.isFakeIp("198.18.0.1"))
        assertTrue(NetCidr.isFakeIp("198.19.255.255"))
        assertFalse(NetCidr.isFakeIp("104.18.42.239"))
    }

    @Test
    fun rfc6052Nat64ExtractsEmbeddedIpv4() {
        val vectors = listOf(
            "2001:db8:c000:221::",
            "2001:db8:1c0:2:21::",
            "2001:db8:122:c000:2:2100::",
            "2001:db8:122:3c0:0:221::",
            "2001:db8:122:344:c0:2:2100::",
            "2001:db8:122:344::192.0.2.33",
        )
        for (addr in vectors) {
            val v6 = InetAddress.getByName(addr) as Inet6Address
            assertTrue(
                "$addr should embed 192.0.2.33",
                "192.0.2.33" in NetCidr.nat64EmbeddedIpv4Candidates(v6),
            )
        }
    }

    @Test
    fun overallNetworkDownBeatsPollution() {
        assertEquals(
            NetCidr.Overall.NETWORK_DOWN,
            NetCidr.overall(
                appApiFailed = true,
                polluted = true,
                anyFailed = true,
                anyDegraded = false,
                extremeLatency = false,
                highLatency = false,
            ),
        )
        assertEquals(
            NetCidr.Overall.POLLUTED,
            NetCidr.overall(
                appApiFailed = false,
                polluted = true,
                anyFailed = false,
                anyDegraded = false,
                extremeLatency = false,
                highLatency = false,
            ),
        )
        assertEquals(
            NetCidr.Overall.CLEAN,
            NetCidr.overall(
                appApiFailed = false,
                polluted = false,
                anyFailed = false,
                anyDegraded = false,
                extremeLatency = false,
                highLatency = false,
            ),
        )
    }

    @Test
    fun pixivDomainHelper() {
        assertTrue(NetCidr.isPixivDomain("app-api.pixiv.net"))
        assertTrue(NetCidr.isPixivDomain("www.pixiv.net"))
        assertFalse(NetCidr.isPixivDomain("pixshaft.com"))
    }
}
