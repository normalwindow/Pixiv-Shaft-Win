package ceui.pixshaft.desktop

import ceui.pixshaft.shared.auth.DesktopOAuth
import ceui.pixshaft.shared.auth.OAuthException
import ceui.pixshaft.shared.auth.Pkce
import ceui.pixshaft.shared.auth.PixivOAuth
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.model.TokenResponse
import ceui.pixshaft.shared.net.DesktopClient
import ceui.pixshaft.shared.net.IPv4OnlyDns
import ceui.pixshaft.shared.net.PixivDns
import ceui.pixshaft.shared.session.SessionStore
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AppGraph {
    val gson = Gson()
    val sessionStore = SessionStore(AppPaths.sessionFile, gson)
    val settings = SettingsStore(gson).also {
        Chromium.directConnect = it.current.directConnect
        ceui.pixshaft.shared.net.ImageHosts.configure(it.current.imageHostMode, it.current.customImageHost)
    }

    @Volatile var http: OkHttpClient = buildBaseHttp(settings.current)
        private set
    @Volatile var imageHttp: OkHttpClient = buildImageHttp(settings.current)
        private set
    @Volatile var oauth: DesktopOAuth = DesktopOAuth(buildOAuthHttp(settings.current), gson)
        private set
    @Volatile var client: DesktopClient = buildClient(settings.current)
        private set

    val history = HistoryStore(gson)
    val accounts = AccountStore(gson)
    val queue = DownloadQueue(this, gson)
    val searchHistory = SearchHistoryStore(gson)
    val feedStore = FeedStore()

    @Volatile private var loginReturn: LoginReturnChannel? = null
    @Volatile private var loginSession: ChromiumSession? = null

    private val renewalGeneration = AtomicInteger(0)

    init {
        settings.onChange { prev, next ->
            client.fanboxCookie = next.fanboxCookie
            if (networkSettingsChanged(prev, next)) {
                rebuildNetwork(next)
            }
        }
        scheduleTokenRenewal()
    }

    fun rebuildNetwork(s: DesktopSettings = settings.current) {
        Chromium.directConnect = s.directConnect
        ceui.pixshaft.shared.net.ImageHosts.configure(s.imageHostMode, s.customImageHost)
        ChromiumHttp.recycleSession()
        http = buildBaseHttp(s)
        imageHttp = buildImageHttp(s)
        oauth = DesktopOAuth(buildOAuthHttp(s), gson)
        client = buildClient(s)
        if (sessionStore.isLoggedIn && s.directConnect) ChromiumHttp.warmAsync()
    }

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

    fun cancelLogin() {
        loginReturn?.cancel()
        runCatching { loginSession?.close() }
        loginSession = null
        WebAuthHost.cancel()
        Chromium.abortLoginBrowsers()
    }

    fun prepareLoginUrl(): String {
        Files.createDirectories(AppPaths.root)
        val pkce = Pkce.generate()
        Files.writeString(AppPaths.pkceFile, pkce.verifier)
        return PixivOAuth.buildLoginUrl(pkce.challenge)
    }

    suspend fun loginWithChromium(onStatus: (String) -> Unit) {
        ProtocolRegistrar.registerCurrentProcess()
        Files.createDirectories(AppPaths.root)
        val pkce = Pkce.generate()
        Files.writeString(AppPaths.pkceFile, pkce.verifier)
        val url = PixivOAuth.buildLoginUrl(pkce.challenge)
        val channel = LoginReturnChannel()
        loginReturn = channel
        try {
            withContext(Dispatchers.IO) {
                val token = if (WebAuthHost.available()) {
                    onStatus("使用 WebView2 登录（与 Pixeval 相同）…")
                    val result = WebAuthHost.authenticate(url, pkce.verifier, onStatus)
                    if (!result.tokenJson.isNullOrBlank()) {
                        onStatus("正在保存登录…")
                        WebAuthHost.parseToken(result.tokenJson)
                    } else {
                        onStatus("正在交换令牌…")
                        val uri = result.callbackUri.orEmpty()
                        val code = PixivOAuth.parseCallbackCode(uri)
                            ?: throw OAuthException(result.error ?: PixivOAuth.missingCodeMessage(uri))
                        tokenOAuth().exchangeCode(code, pkce.verifier)
                    }
                } else {
                    val uri = ChromiumSession.launchLogin(url, onStatus).use { session ->
                        loginSession = session
                        try {
                            session.awaitCallback(
                                timeoutMs = 8 * 60_000L,
                                onStatus = onStatus,
                                sink = channel,
                            )
                        } catch (error: InterruptedException) {
                            throw CancellationException(error.message)
                        }
                    }
                    if (channel.isCancelled) {
                        throw CancellationException("已取消登录")
                    }
                    onStatus("正在交换令牌…")
                    val code = PixivOAuth.parseCallbackCode(uri)
                        ?: throw OAuthException(PixivOAuth.missingCodeMessage(uri))
                    tokenOAuth().exchangeCode(code, pkce.verifier)
                }
                persist(token)
                Files.deleteIfExists(AppPaths.pkceFile)
            }
        } finally {
            loginSession = null
            loginReturn = null
        }
    }

    private fun tokenOAuth(): DesktopOAuth {
        val s = settings.current
        val client = DesktopClient.defaultHttp(dns = dnsFor(s), proxy = proxyFor(s)).newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(ChromiumHttp.interceptor)
            .build()
        return DesktopOAuth(client, gson)
    }

    suspend fun completeLoginFromUri(uri: String) {
        val code = PixivOAuth.parseCallbackCode(uri)
            ?: throw OAuthException(PixivOAuth.missingCodeMessage(uri))
        val verifier = readVerifier()
        val token = withContext(Dispatchers.IO) { tokenOAuth().exchangeCode(code, verifier) }
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
        val token = withContext(Dispatchers.IO) { tokenOAuth().refresh(refreshToken.trim()) }
        persist(token)
    }

    fun logout() {
        sessionStore.clear()
    }

    private fun persist(token: TokenResponse) {
        val session = StoredSession(
            accessToken = token.access_token.orEmpty(),
            refreshToken = token.refresh_token.orEmpty(),
            expiresAtMillis = System.currentTimeMillis() + (token.expires_in ?: 3600) * 1000L,
            user = token.user?.toUser(),
        )
        sessionStore.save(session)
        accounts.upsert(session)
        client.fanboxCookie = settings.current.fanboxCookie
        ChromiumHttp.warmAsync()
        scheduleTokenRenewal()
    }

    /**
     * 直连续命：在 access token 过期前 [RENEW_BEFORE_MS] 后台刷新，浏览永远打在
     * 有效令牌上，不再触发 400/刷新链路。刷新请求 oauth.secure.pixiv.net/auth/token
     * 在直连下由 ChromiumSession.executeFormPost 顶层表单提交，代理模式走 OkHttp。
     */
    private fun scheduleTokenRenewal() {
        if (!sessionStore.isLoggedIn) return
        val gen = renewalGeneration.incrementAndGet()
        thread(name = "token-renew", isDaemon = true) {
            while (renewalGeneration.get() == gen) {
                val session = sessionStore.session ?: return@thread
                val remaining = session.expiresAtMillis - System.currentTimeMillis() - RENEW_BEFORE_MS
                if (remaining > 0) {
                    runCatching { Thread.sleep(minOf(remaining, 60_000L)) }
                    continue
                }
                val renewed = runCatching { renewSession() }
                    .onFailure { CrashLog.write("token renew failed: ${it.message}") }
                    .isSuccess
                if (renewalGeneration.get() != gen) return@thread
                if (!renewed) runCatching { Thread.sleep(RENEW_RETRY_MS) }
            }
        }
    }

    private fun renewSession() {
        val session = sessionStore.session ?: return
        val token = oauth.refresh(session.refreshToken)
        val renewed = StoredSession(
            accessToken = token.access_token.orEmpty(),
            refreshToken = token.refresh_token ?: session.refreshToken,
            expiresAtMillis = System.currentTimeMillis() + (token.expires_in ?: 3600) * 1000L,
            user = token.user?.toUser() ?: session.user,
        )
        sessionStore.save(renewed)
        accounts.upsert(renewed)
    }

    private fun buildClient(s: DesktopSettings): DesktopClient =
        DesktopClient(
            sessionStore,
            oauth,
            gson,
            http,
            ChromiumHttp.interceptor,
        ).also { it.fanboxCookie = s.fanboxCookie }

    companion object {
        private const val RENEW_BEFORE_MS = 10 * 60_000L
        private const val RENEW_RETRY_MS = 5 * 60_000L

        fun dnsFor(s: DesktopSettings): Dns =
            if (s.directConnect || s.useSecureDns) PixivDns else IPv4OnlyDns

        fun proxyFor(s: DesktopSettings): Proxy =
            if (s.directConnect) Proxy.NO_PROXY else Chromium.javaProxy()

        fun useDirectPximg(s: DesktopSettings): Boolean =
            s.directConnect && s.imageHostMode == 0

        fun networkSettingsChanged(prev: DesktopSettings, next: DesktopSettings): Boolean =
            prev.directConnect != next.directConnect ||
                prev.useSecureDns != next.useSecureDns ||
                prev.imageHostMode != next.imageHostMode ||
                prev.customImageHost != next.customImageHost

        fun imageHostLabel(mode: Int): String = when (mode) {
            1 -> "pixiv.cat"
            2 -> "pixiv.re"
            3 -> "pixiv.nl"
            4 -> "自定义反代"
            else -> "Pixiv 官方"
        }
    }

    private fun buildBaseHttp(s: DesktopSettings): OkHttpClient =
        DesktopClient.defaultHttp(dns = dnsFor(s), proxy = proxyFor(s))

    private fun buildOAuthHttp(s: DesktopSettings): OkHttpClient =
        buildBaseHttp(s).newBuilder()
            .addInterceptor(ChromiumHttp.interceptor)
            .build()

    private fun buildImageHttp(s: DesktopSettings): OkHttpClient =
        DesktopClient.imageHttp(directPximg = useDirectPximg(s)).newBuilder()
            .proxy(proxyFor(s))
            .dns(dnsFor(s))
            .build()
}
