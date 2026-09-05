package ceui.pixshaft.shared.net

/**
 * JVM port of Android [ceui.lisa.http.ImageHostManager]. Rewrites `i.pximg.net`
 * / `s.pximg.net` to a mirror when the user picks one; official mode is a no-op.
 */
object ImageHosts {
    const val MODE_PIXIV = 0
    const val MODE_CAT = 1
    const val MODE_RE = 2
    const val MODE_NL = 3
    const val MODE_CUSTOM = 4

    private const val PXIMG_I = "i.pximg.net"
    private const val PXIMG_S = "s.pximg.net"

    @Volatile var mode: Int = MODE_PIXIV
        private set
    @Volatile var customHost: String = ""
        private set

    fun configure(modeOrdinal: Int, custom: String) {
        mode = modeOrdinal.coerceIn(MODE_PIXIV, MODE_CUSTOM)
        customHost = custom.trim().trimEnd('/')
    }

    fun requiresOfficialPximg(): Boolean = mode == MODE_PIXIV

    fun rewrite(url: String): String {
        if (url.isEmpty()) return url
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
        if (pathStart <= hostStart) return url
        val hostAndPort = url.substring(hostStart, pathStart)
        val host = hostAndPort.substringBefore(':')
        if (host != PXIMG_I && host != PXIMG_S) return url
        return when (mode) {
            MODE_PIXIV -> url
            MODE_CAT -> replaceHost(url, hostStart, pathStart, if (host == PXIMG_I) "i.pixiv.cat" else "s.pixiv.cat")
            MODE_RE -> replaceHost(url, hostStart, pathStart, if (host == PXIMG_I) "i.pixiv.re" else "s.pixiv.re")
            MODE_NL -> replaceHost(url, hostStart, pathStart, if (host == PXIMG_I) "i.pixiv.nl" else "s.pixiv.nl")
            MODE_CUSTOM -> {
                if (customHost.isEmpty()) url
                else customHost + url.substring(pathStart)
            }
            else -> url
        }
    }

    private fun replaceHost(url: String, hostStart: Int, pathStart: Int, host: String): String =
        url.substring(0, hostStart) + host + url.substring(pathStart)
}
