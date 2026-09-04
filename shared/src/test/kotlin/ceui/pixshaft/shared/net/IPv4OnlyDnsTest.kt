package ceui.pixshaft.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class IPv4OnlyDnsTest {
    @Test
    fun keepsIpv4WhenBothPresent() {
        val v4 = InetAddress.getByName("8.8.8.8")
        val v6 = InetAddress.getByName("2001:4860:4860::8888")
        val result = keepIpv4IfPossible("app-api.pixiv.net", listOf(v6, v4))
        assertEquals(listOf(v4), result)
        assertTrue(result.all { it is Inet4Address })
    }

    @Test
    fun keepsIpv6WhenOnlyIpv6() {
        val v6 = InetAddress.getByName("2001:4860:4860::8888")
        val result = keepIpv4IfPossible("app-api.pixiv.net", listOf(v6))
        assertEquals(listOf(v6), result)
        assertTrue(result.all { it is Inet6Address })
    }

    @Test
    fun doesNotFilterUnknownHosts() {
        val v4 = InetAddress.getByName("1.1.1.1")
        val v6 = InetAddress.getByName("2606:4700:4700::1111")
        val result = keepIpv4IfPossible("example.com", listOf(v6, v4))
        assertEquals(listOf(v6, v4), result)
    }
}
