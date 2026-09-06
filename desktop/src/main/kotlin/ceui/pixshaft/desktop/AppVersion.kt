package ceui.pixshaft.desktop

/** Desktop package identity — keep in sync with `desktop/build.gradle` `packageVersion`. */
object AppVersion {
    const val NAME = "0.0.4"
    const val DISPLAY = "0.0.4"
    const val CODE = 4
    /**
     * Advertised on the chat WS handshake as unsigned `&v=`.
     * The server version-gates `room:"global"` delivery; v=1 is treated as a frozen
     * old client and will not receive public-room broadcasts. Mirror the Android
     * `versionCode` so desktop is treated as a current client.
     */
    const val CHAT_CLIENT_VERSION = 42500

    const val GITHUB_OWNER = "normalwindow"
    const val GITHUB_REPO = "Pixiv-Shaft-Win"
    const val GITHUB_SLUG = "$GITHUB_OWNER/$GITHUB_REPO"
    const val GITHUB_URL = "https://github.com/$GITHUB_SLUG"
    const val RELEASES_URL = "https://github.com/$GITHUB_SLUG/releases"
    const val RELEASES_API = "https://api.github.com/repos/$GITHUB_SLUG/releases/latest"
    const val ISSUES_URL = "https://github.com/$GITHUB_SLUG/issues"
    const val UPSTREAM_URL = "https://github.com/CeuiLiSA/Pixiv-Shaft"
    const val WEBSITE = "https://pixshaft.com"
    const val LICENSE = "GPL-2.0"
    const val LICENSE_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"

    fun parseTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * Compare dotted versions (`1.2.3` / `1.2.3-beta`). Returns:
     * negative if [current] < [latest], 0 if equal, positive if current is newer.
     */
    fun compare(current: String, latest: String): Int {
        val a = parseTag(current).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = parseTag(latest).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}
