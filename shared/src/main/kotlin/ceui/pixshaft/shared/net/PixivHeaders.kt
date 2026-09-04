package ceui.pixshaft.shared.net

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class RequestNonce(
    val xClientTime: String,
    val xClientHash: String,
) {
    companion object {
        private const val HASH_SECRET =
            "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"

        fun build(now: Date = Date()): RequestNonce {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            format.timeZone = TimeZone.getDefault()
            val time = format.format(now)
            return RequestNonce(time, md5(time + HASH_SECRET))
        }

        fun md5(plainText: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(plainText.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

object PixivClientIdentity {
    const val APP_VERSION = "8.6.10"
    const val APP_OS_VERSION = "26.5"
    const val DEVICE_MODEL = "iPhone16,2"
    const val USER_AGENT = "PixivIOSApp/$APP_VERSION (iOS $APP_OS_VERSION; $DEVICE_MODEL)"
    const val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
    const val IMAGE_REFERER = "https://app-api.pixiv.net/"
    const val APP_API_HOST = "https://app-api.pixiv.net/"
    const val OAUTH_BASE = "https://oauth.secure.pixiv.net/"
}
