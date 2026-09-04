package ceui.pixshaft.shared.net

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

internal val IPV4_ONLY_HOSTS = setOf(
    "app-api.pixiv.net",
    "oauth.secure.pixiv.net",
    "www.pixiv.net",
    "accounts.pixiv.net",
    "comic.pixiv.net",
    "api.fanbox.cc",
    "i.pximg.net",
)

fun keepIpv4IfPossible(hostname: String, resolved: List<InetAddress>): List<InetAddress> {
    if (hostname !in IPV4_ONLY_HOSTS) return resolved
    val ipv4 = resolved.filterIsInstance<Inet4Address>()
    return if (ipv4.isNotEmpty()) ipv4 else resolved
}

object IPv4OnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        keepIpv4IfPossible(hostname, Dns.SYSTEM.lookup(hostname))
}
