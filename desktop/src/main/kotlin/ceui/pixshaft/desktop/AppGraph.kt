package ceui.pixshaft.desktop

import ceui.pixshaft.shared.auth.DesktopOAuth
import ceui.pixshaft.shared.auth.OAuthException
import ceui.pixshaft.shared.auth.Pkce
import ceui.pixshaft.shared.auth.PixivOAuth
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.model.TokenResponse
import ceui.pixshaft.shared.net.DesktopClient
import ceui.pixshaft.shared.session.SessionStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

class AppGraph {
    val gson = Gson()
    val sessionStore = SessionStore(AppPaths.sessionFile, gson)
    val http = DesktopClient.defaultHttp(ChromiumHttp.interceptor).newBuilder()
        .proxy(Chromium.javaProxy())
        .build()
    val imageHttp = DesktopClient.imageHttp().newBuilder()
        .proxy(Chromium.javaProxy())
        .build()
    val oauth = DesktopOAuth(http, gson)
    val client = DesktopClient(sessionStore, oauth, gson, http)

    @Volatile private var loginReturn: LoginReturnChannel? = null

    /**
     * Offer a `pixiv://` / `shaft://` / HTTPS callback to an in-flight Chromium login.
     * Returns true when Chromium login is waiting so the caller must not exchange the code itself
     * (authorization codes are single-use).
     */
    fun offerLoginCallback(raw: String?): Boolean {
        val channel = loginReturn ?: return false
        channel.offer(raw)
        return true
    }

    fun prepareLoginUrl(): String {
        Files.createDirectories(AppPaths.root)
        val pkce = Pkce.generate()
        Files.writeString(AppPaths.pkceFile, pkce.verifier)
        return PixivOAuth.buildLoginUrl(pkce.challenge)
    }

    suspend fun loginWithChromium(onStatus: (String) -> Unit) {
        ProtocolRegistrar.registerCurrentProcess()
        val url = prepareLoginUrl()
        val channel = LoginReturnChannel()
        loginReturn = channel
        try {
            withContext(Dispatchers.IO) {
                ChromiumSession.launchLogin(url, onStatus).use { session ->
                    val uri = session.awaitCallback(
                        timeoutMs = 8 * 60_000L,
                        onStatus = onStatus,
                        sink = channel,
                    )
                    onStatus("正在交换令牌…")
                    val code = PixivOAuth.parseCallbackCode(uri)
                        ?: throw OAuthException(PixivOAuth.missingCodeMessage(uri))
                    val verifier = readVerifier()
                    session.prepareForNetwork(onStatus)
                    val token = oauth.exchangeCode(code, verifier)
                    persist(token)
                    Files.deleteIfExists(AppPaths.pkceFile)
                }
            }
        } finally {
            loginReturn = null
        }
    }

    suspend fun completeLoginFromUri(uri: String) {
        val code = PixivOAuth.parseCallbackCode(uri)
            ?: throw OAuthException(PixivOAuth.missingCodeMessage(uri))
        val verifier = readVerifier()
        val token = withContext(Dispatchers.IO) { oauth.exchangeCode(code, verifier) }
        persist(token)
        Files.deleteIfExists(AppPaths.pkceFile)
    }

    private fun readVerifier(): String {
        if (!Files.exists(AppPaths.pkceFile)) {
            throw OAuthException("找不到 PKCE verifier，请重新点「用 Chromium 登录」")
        }
        return Files.readString(AppPaths.pkceFile).trim()
    }

    suspend fun loginWithRefreshToken(refreshToken: String) {
        val token = withContext(Dispatchers.IO) { oauth.refresh(refreshToken.trim()) }
        persist(token)
    }

    fun logout() {
        sessionStore.clear()
    }

    private fun persist(token: TokenResponse) {
        sessionStore.save(
            StoredSession(
                accessToken = token.access_token.orEmpty(),
                refreshToken = token.refresh_token.orEmpty(),
                expiresAtMillis = System.currentTimeMillis() + (token.expires_in ?: 3600) * 1000L,
                user = token.user?.toUser(),
            ),
        )
    }
}
