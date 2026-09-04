package ceui.pixshaft.shared.net

import com.google.gson.Gson
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Desktop equivalent of Android [ceui.lisa.http.HttpDns]:
 * DoH first, then Cloudflare anycast for API/OAuth, then legacy Pixiv
 * image IPs. Never trust the OS resolver for Pixiv hosts — this machine
 * (and many others) maps `app-api.pixiv.net` to Dropbox / Facebook.
 *
 * JavaFX WebKit and plain TCP/TLS may still fail even with the right IP
 * (QUIC-only paths). Chromium is the login/API fallback for that case.
 */
object PixivDns : Dns {
    const val CF_IP_PRIMARY = "104.18.42.239"
    const val CF_IP_SECONDARY = "172.64.145.17"

    val API_HOSTS: Set<String> = setOf(
        "app-api.pixiv.net",
        "oauth.secure.pixiv.net",
        "www.pixiv.net",
        "accounts.pixiv.net",
        "comic.pixiv.net",
        "api.fanbox.cc",
    )

    val FALLBACK_API_IPS: List<String> = listOf(CF_IP_PRIMARY, CF_IP_SECONDARY)

    val FALLBACK_IMAGE_IPS: List<String> = listOf(
        "210.140.139.134",
        "210.140.139.133",
        "210.140.139.131",
    )

    val DOH_ENDPOINTS: List<String> = listOf(
        "https://1.0.0.1/",
        "https://185.222.222.222/",
    )

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]
        if (!cached.isNullOrEmpty()) return cached
        val pinned = parseIps(fallbackFor(hostname))
        if (pinned.isNotEmpty()) {
            cache[hostname] = pinned
            Thread({
                val fromDoh = runCatching { resolveViaDoh(hostname) }.getOrDefault(emptyList())
                if (fromDoh.isNotEmpty()) {
                    cache[hostname] = keepIpv4IfPossible(hostname, fromDoh)
                }
            }, "pixiv-doh-$hostname").apply {
                isDaemon = true
                start()
            }
            return pinned
        }
        val system = Dns.SYSTEM.lookup(hostname)
        if (system.isEmpty()) throw UnknownHostException(hostname)
        val filtered = keepIpv4IfPossible(hostname, system)
        cache[hostname] = filtered
        return filtered
    }

    fun fallbackFor(hostname: String): List<String> {
        val host = hostname.lowercase()
        return when {
            host.endsWith("pximg.net") -> FALLBACK_IMAGE_IPS
            host in API_HOSTS -> FALLBACK_API_IPS
            else -> emptyList()
        }
    }

    fun isPinnedHost(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host in API_HOSTS || host.endsWith("pximg.net")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        for (endpoint in DOH_ENDPOINTS) {
            val answers = queryDoh(endpoint, hostname)
            if (answers.isNotEmpty()) return answers
        }
        return emptyList()
    }

    fun parseDohAnswers(json: String): List<String> {
        val parsed = gson.fromJson(json, DohResponse::class.java) ?: return emptyList()
        return parsed.Answer.orEmpty()
            .filter { it.type == 1 && !it.data.isNullOrBlank() }
            .map { it.data!!.trim() }
    }

    private fun queryDoh(endpoint: String, hostname: String): List<InetAddress> {
        val url = endpoint.toHttpUrl().newBuilder()
            .encodedPath("/dns-query")
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", "A")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            return parseIps(parseDohAnswers(body))
        }
    }

    private fun parseIps(ips: List<String>): List<InetAddress> =
        ips.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }

    private data class DohResponse(
        val Status: Int = -1,
        val Answer: List<DohAnswer>? = null,
    )

    private data class DohAnswer(
        val type: Int = 0,
        val data: String? = null,
    )
}
