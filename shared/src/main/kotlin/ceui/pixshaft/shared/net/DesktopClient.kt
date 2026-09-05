package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.auth.DesktopOAuth
import ceui.pixshaft.shared.auth.OAuthException
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.session.SessionStore
import com.google.gson.Gson
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

class DesktopClient(
    private val sessionStore: SessionStore,
    private val oauth: DesktopOAuth,
    gson: Gson = Gson(),
    http: OkHttpClient = defaultHttp(),
    /**
     * Direct-connect transport (Chromium QUIC on desktop). Must run **after**
     * header / token interceptors: application interceptors that short-circuit
     * never see later interceptors, so putting this first strips Authorization.
     */
    transport: Interceptor? = null,
) {
    private val appHttp: OkHttpClient = apiHttp(http, sessionStore, oauth, transport)

    val api: AppApi = Retrofit.Builder()
        .baseUrl(PixivClientIdentity.APP_API_HOST)
        .client(appHttp)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(AppApi::class.java)

    val comicApi: ComicApi = Retrofit.Builder()
        .baseUrl("https://comic.pixiv.net/")
        .client(appHttp)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ComicApi::class.java)

    @Volatile var fanboxCookie: String = ""

    val fanboxApi: FanboxApi = Retrofit.Builder()
        .baseUrl("https://api.fanbox.cc/")
        .client(
            http.newBuilder()
                .addInterceptor { chain ->
                    val builder = chain.request().newBuilder()
                        .header("Origin", "https://www.fanbox.cc")
                        .header("Referer", "https://www.fanbox.cc/")
                        .header("User-Agent", PixivClientIdentity.USER_AGENT)
                    val cookie = fanboxCookie
                    if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                    chain.proceed(builder.build())
                }
                .apply { if (transport != null) addInterceptor(transport) }
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(FanboxApi::class.java)

    val chatApi: ShaftChatApi = Retrofit.Builder()
        .baseUrl(CHAT_BASE)
        .client(http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ShaftChatApi::class.java)

    companion object {
        const val CHAT_BASE = "http://36.138.103.18:30009/"

        fun defaultHttp(
            dns: Dns = PixivDns,
            proxy: java.net.Proxy = java.net.Proxy.NO_PROXY,
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dns(dns)
            .proxy(proxy)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        /**
         * App / comic Retrofit client: identity headers, then token refresh, then
         * the optional transport (Chromium QUIC). Order is load-bearing.
         */
        fun apiHttp(
            base: OkHttpClient,
            sessionStore: SessionStore,
            oauth: DesktopOAuth,
            transport: Interceptor? = null,
        ): OkHttpClient = base.newBuilder()
            .addInterceptor(HeaderInterceptor(sessionStore))
            .addInterceptor(TokenRefreshInterceptor(sessionStore, oauth))
            .apply { if (transport != null) addInterceptor(transport) }
            .build()

        fun imageHttp(directPximg: Boolean = true): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .dns(PixivDns)
                .protocols(
                    if (directPximg) listOf(Protocol.HTTP_1_1)
                    else listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
                )
                .addInterceptor { chain ->
                    val raw = chain.request()
                    val rewritten = ImageHosts.rewrite(raw.url.toString())
                    val request = raw.newBuilder()
                        .url(rewritten)
                        .header("Referer", PixivClientIdentity.IMAGE_REFERER)
                        .header("User-Agent", PixivClientIdentity.USER_AGENT)
                        .build()
                    chain.proceed(request)
                }
            if (directPximg) {
                builder.sslSocketFactory(DirectTls.noSniFactory, DirectTls.trustAll)
                builder.hostnameVerifier(DirectTls.hostnameVerifier)
            }
            return builder.build()
        }
    }
}

internal class HeaderInterceptor(
    private val sessionStore: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val nonce = RequestNonce.build()
        val builder = chain.request().newBuilder()
            .header("accept-language", Locale.getDefault().toLanguageTag())
            .header("app-accept-language", Locale.getDefault().language.ifBlank { "en" })
            .header("app-os", "ios")
            .header("app-os-version", PixivClientIdentity.APP_OS_VERSION)
            .header("app-version", PixivClientIdentity.APP_VERSION)
            .header("user-agent", PixivClientIdentity.USER_AGENT)
            .header("x-client-time", nonce.xClientTime)
            .header("x-client-hash", nonce.xClientHash)
        val bearer = sessionStore.bearerOrEmpty()
        if (bearer.isNotEmpty()) {
            builder.header("authorization", bearer)
        }
        return chain.proceed(builder.build())
    }
}

internal class TokenRefreshInterceptor(
    private val sessionStore: SessionStore,
    private val oauth: DesktopOAuth,
) : Interceptor {
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!sessionStore.isLoggedIn) return response
        if (response.code != 400 && response.code != 401) return response
        val peek = runCatching { response.peekBody(4096).string() }.getOrDefault("")
        val tokenError = peek.contains("Error occurred at the OAuth process") ||
            peek.contains("Invalid access token") ||
            peek.contains("Invalid refresh token") ||
            response.code == 401
        if (!tokenError) return response
        val refreshed = synchronized(lock) {
            try {
                val token = oauth.refresh(sessionStore.refreshToken)
                val current = sessionStore.session
                sessionStore.save(
                    StoredSession(
                        accessToken = token.access_token.orEmpty(),
                        refreshToken = token.refresh_token ?: sessionStore.refreshToken,
                        expiresAtMillis = System.currentTimeMillis() +
                            (token.expires_in ?: 3600) * 1000L,
                        user = token.user?.toUser() ?: current?.user,
                    ),
                )
                token.access_token
            } catch (error: Exception) {
                response.close()
                // Session is definitively unusable: the API already rejected the
                // access token and renewal failed. Surface that instead of the
                // misleading 400 "请求被拒绝" from the original call.
                throw OAuthException("登录已过期，且无法自动刷新令牌。请开启代理后重试，或重新登录。", error)
            }
        }
        response.close()
        val replay = request.newBuilder()
            .header("authorization", "Bearer $refreshed")
            .build()
        return chain.proceed(replay)
    }
}
