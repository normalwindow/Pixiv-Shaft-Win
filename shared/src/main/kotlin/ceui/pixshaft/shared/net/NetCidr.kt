package ceui.pixshaft.shared.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * DNS 污染 / fake-ip / NAT64 判定，从 Android [ceui.pixiv.ui.debug.NetworkTestViewModel]
 * 抽出，给桌面网络测试页与单元测试共用。
 */
object NetCidr {
    const val APP_API_HOST = "app-api.pixiv.net"

    val PIXIV_CIDRS: List<String> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    val PXIMG_CIDRS: List<String> = listOf(
        "210.140.92.0/24", "210.140.131.0/24", "210.140.139.0/24", "210.140.140.0/24",
        "210.140.141.0/24", "210.140.142.0/24", "210.140.143.0/24", "210.140.144.0/24",
        "210.140.145.0/24", "210.140.146.0/24", "210.140.147.0/24", "210.140.148.0/24",
        "210.140.149.0/24", "210.140.150.0/24",
    )

    val FAKE_IP_CIDRS: List<String> = listOf(
        "198.18.0.0/15",
        "192.0.2.0/24",
        "198.51.100.0/24",
        "203.0.113.0/24",
    )

    private val NAT64_V4_OFFSETS = listOf(
        intArrayOf(4, 5, 6, 7),
        intArrayOf(5, 6, 7, 9),
        intArrayOf(6, 7, 9, 10),
        intArrayOf(7, 9, 10, 11),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(12, 13, 14, 15),
    )

    fun isIpInCidr(ip: String, cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        val ipInt = ipToInt(ip) ?: return false
        val netInt = ipToInt(parts[0]) ?: return false
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (ipInt and mask) == (netInt and mask)
    }

    fun ipToInt(ip: String): Int? {
        val octets = ip.split(".")
        if (octets.size != 4) return null
        var result = 0
        for (part in octets) {
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result = (result shl 8) or v
        }
        return result
    }

    fun isFakeIp(ip: String): Boolean = FAKE_IP_CIDRS.any { isIpInCidr(ip, it) }

    fun isPixivDomain(host: String): Boolean =
        host.equals(APP_API_HOST, ignoreCase = true) ||
            host.equals("www.pixiv.net", ignoreCase = true)

    fun nat64EmbeddedIpv4Candidates(addr: Inet6Address): List<String> {
        val b = addr.address
        if (b.size != 16) return emptyList()
        return NAT64_V4_OFFSETS.map { idx ->
            idx.joinToString(".") { (b[it].toInt() and 0xFF).toString() }
        }
    }

    fun isNat64Synthesized(addr: Inet6Address, cidrs: List<String>?): Boolean {
        if (cidrs == null) return false
        return nat64EmbeddedIpv4Candidates(addr).any { v4 -> cidrs.any { isIpInCidr(v4, it) } }
    }

    fun isPublicIpv6(addr: InetAddress, cidrs: List<String>?): Boolean {
        if (addr !is Inet6Address) return false
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress ||
            addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return false
        }
        if (isNat64Synthesized(addr, cidrs)) return false
        val host = addr.hostAddress?.lowercase() ?: return false
        return !host.startsWith("fc") && !host.startsWith("fd") &&
            !host.startsWith("64:ff9b:") && !host.startsWith("2001:db8:") &&
            !host.startsWith("::ffff:")
    }

    fun ipv4InOfficialCidrs(ip: String, cidrs: List<String>): Boolean =
        cidrs.any { isIpInCidr(ip, it) }

    enum class Overall {
        CLEAN, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED, NETWORK_DOWN,
    }

    fun overall(
        appApiFailed: Boolean,
        polluted: Boolean,
        anyFailed: Boolean,
        anyDegraded: Boolean,
        extremeLatency: Boolean,
        highLatency: Boolean,
    ): Overall = when {
        appApiFailed -> Overall.NETWORK_DOWN
        polluted -> Overall.POLLUTED
        anyFailed || anyDegraded -> Overall.DEGRADED
        extremeLatency -> Overall.EXTREME_LATENCY
        highLatency -> Overall.HIGH_LATENCY
        else -> Overall.CLEAN
    }

    const val HIGH_LATENCY_MS = 500
    const val EXTREME_LATENCY_MS = 1000
    const val IMAGE_TTFB_HIGH_MS = 1000
    const val IMAGE_TTFB_EXTREME_MS = 2000
    const val SLOW_THROUGHPUT_KBS = 50L
    const val MIN_SPEED_SAMPLE_BYTES = 20 * 1024
}
