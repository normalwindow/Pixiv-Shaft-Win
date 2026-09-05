package ceui.pixshaft.desktop.ui

import ceui.pixshaft.shared.auth.PixivOAuth

object DeepLinks {
    private val artwork = Regex("""(?:artworks/|illust[_-]?id=)(\d+)""", RegexOption.IGNORE_CASE)
    private val user = Regex("""(?:/users/|users/|user[_-]?id=)(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?): Dest? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        if (PixivOAuth.extractCallback(text) != null) return null
        artwork.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return Dest.Artwork(it) }
        user.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return Dest.User(it) }
        return null
    }
}
