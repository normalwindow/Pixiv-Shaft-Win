package ceui.pixshaft.shared.auth

import ceui.pixshaft.shared.model.TokenResponse
import ceui.pixshaft.shared.net.PixivClientIdentity
import ceui.pixshaft.shared.net.RequestNonce
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkcePair(
    val verifier: String,
    val challenge: String,
)

object Pkce {
    fun generate(): PkcePair {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = encode(bytes)
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return PkcePair(verifier, challenge)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

object PixivOAuth {
    const val CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
    const val CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
    const val REDIRECT_URI = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"
    const val LOGIN_URL = "https://app-api.pixiv.net/web/v1/login"
    const val TOKEN_URL = "https://oauth.secure.pixiv.net/auth/token"
    const val CALLBACK_SCHEME = "shaft"
    const val CALLBACK_HOST = "oauth"

    fun buildLoginUrl(challenge: String): String {
        return "$LOGIN_URL?code_challenge=$challenge&code_challenge_method=S256&client=pixiv-android"
    }

    fun parseCallbackCode(uri: String): String? {
        if (isIntermediateOauth(uri)) return null
        val queries = linkedSetOf<String>()
        fun addQueryFrom(raw: String) {
            val cut = raw.substringAfter('?', missingDelimiterValue = "")
                .substringBefore('#')
            if (cut.isNotBlank()) queries += cut
        }
        addQueryFrom(uri)
        runCatching { java.net.URLDecoder.decode(uri, Charsets.UTF_8) }
            .getOrNull()
            ?.let { addQueryFrom(it) }
        for (query in queries) {
            for (part in query.split('&')) {
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', missingDelimiterValue = "")
                if (key.equals("code", ignoreCase = true) && value.isNotBlank()) {
                    return java.net.URLDecoder.decode(value, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    fun isIntermediateOauth(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.contains("post-redirect") ||
            lower.contains("/auth/pixiv/start") ||
            lower.contains("/web/v1/login")
    }

    fun parseReturnTo(uri: String): String? {
        val encoded = uri.substringAfter("return_to=", missingDelimiterValue = "")
            .substringBefore('&')
            .substringBefore('#')
        if (encoded.isBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(encoded, Charsets.UTF_8) }
            .getOrDefault(encoded)
        return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun isCallbackUri(uri: String): Boolean {
        if (isIntermediateOauth(uri)) return false
        if (parseCallbackCode(uri).isNullOrBlank()) return false
        val lower = uri.lowercase().trim()
        return lower.startsWith("$CALLBACK_SCHEME:") ||
            lower.startsWith("pixiv:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("android-app:") ||
            lower.contains("/web/v1/users/auth/pixiv/callback")
    }

    fun extractCallback(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (isCallbackUri(trimmed)) return trimmed
        val found = CALLBACK_IN_TEXT.find(trimmed)?.value
            ?.trimEnd(')', ']', '"', '\'', ',', '.')
            ?: return null
        return found.takeIf { isCallbackUri(it) }
    }

    fun redact(uri: String): String =
        uri.replace(Regex("(?i)code=[^&\\s#]+"), "code=***")

    fun oauthStage(url: String?): Int {
        val u = url.orEmpty().lowercase()
        return when {
            extractCallback(u) != null -> 100
            u.startsWith("pixiv:") || u.startsWith("shaft:") || u.startsWith("intent:") -> 90
            u.contains("/auth/pixiv/callback") -> 80
            u.contains("/auth/pixiv/start") || u.contains("post-redirect") -> 50
            u.contains("accounts.pixiv.net") -> 30
            u.contains("app-api.pixiv.net") -> 20
            u.contains("www.pixiv.net") -> 10
            else -> 0
        }
    }

    fun looksLikeOauthRejection(text: String?): Boolean {
        val t = text.orEmpty()
        return t.contains("不正确的请求") ||
            t.contains("不正なリクエスト") ||
            t.contains("Invalid request", ignoreCase = true) ||
            t.contains("Incorrect request", ignoreCase = true) ||
            t.contains("您所指定的端点不存在") ||
            t.contains("指定されたエンドポイントは存在しません") ||
            t.contains("endpoint does not exist", ignoreCase = true)
    }

    private val CALLBACK_IN_TEXT = Regex(
        """(?:pixiv|shaft|intent|android-app):(?://)?\S+|https://app-api\.pixiv\.net/web/v1/users/auth/pixiv/callback\S+""",
        RegexOption.IGNORE_CASE,
    )

    fun missingCodeMessage(uri: String): String {
        val lower = uri.lowercase()
        return if (lower.contains("/auth/pixiv/start") || lower.contains("post-redirect")) {
            "这是登录中间页，没有授权码。请用「用 Chromium 登录」（Android UA），或粘贴带 code= 的 pixiv:// / shaft:// 回调，或 refresh_token。"
        } else {
            "回调里没有 code。请粘贴 pixiv:// 或 shaft:// 完整地址，或 refresh_token。"
        }
    }
}

class DesktopOAuth(
    private val http: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    fun exchangeCode(code: String, verifier: String): TokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuth.CLIENT_ID)
            .add("client_secret", PixivOAuth.CLIENT_SECRET)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", PixivOAuth.REDIRECT_URI)
            .add("include_policy", "true")
            .add("get_secure_url", "true")
            .build()
        return execute(body)
    }

    fun refresh(refreshToken: String): TokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuth.CLIENT_ID)
            .add("client_secret", PixivOAuth.CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("include_policy", "true")
            .add("get_secure_url", "true")
            .build()
        return execute(body)
    }

    private fun execute(body: FormBody): TokenResponse {
        val nonce = RequestNonce.build()
        val request = Request.Builder()
            .url(PixivOAuth.TOKEN_URL)
            .post(body)
            .header("User-Agent", PixivClientIdentity.USER_AGENT)
            .header("App-OS", "ios")
            .header("App-OS-Version", PixivClientIdentity.APP_OS_VERSION)
            .header("App-Version", PixivClientIdentity.APP_VERSION)
            .header("X-Client-Time", nonce.xClientTime)
            .header("X-Client-Hash", nonce.xClientHash)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OAuthException("HTTP ${response.code}: ${raw.take(400)}")
            }
            val parsed = gson.fromJson(raw, TokenResponse::class.java)
            if (parsed.access_token.isNullOrBlank() || parsed.refresh_token.isNullOrBlank()) {
                throw OAuthException("Token response missing tokens: ${raw.take(400)}")
            }
            return parsed
        }
    }
}

class OAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
