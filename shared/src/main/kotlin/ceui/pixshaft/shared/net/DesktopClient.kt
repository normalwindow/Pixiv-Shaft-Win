package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.auth.DesktopOAuth
import ceui.pixshaft.shared.auth.OAuthException
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.session.SessionStore
import com.google.gson.Gson
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
) {
    private val appHttp: OkHttpClient = http.newBuilder()
        .addInterceptor(HeaderInterceptor(sessionStore))
        .addInterceptor(TokenRefreshInterceptor(sessionStore, oauth))
        .build()

    val api: AppApi = Retrofit.Builder()
        .baseUrl(PixivClientIdentity.APP_API_HOST)
        .client(appHttp)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(AppApi::class.java)

    companion object {
        fun defaultHttp(extra: Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dns(PixivDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .apply { if (extra != null) addInterceptor(extra) }
            .build()

        fun imageHttp(): OkHttpClient = defaultHttp().newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", PixivClientIdentity.IMAGE_REFERER)
                    .header("User-Agent", PixivClientIdentity.USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}

private class HeaderInterceptor(
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

private class TokenRefreshInterceptor(
    private val sessionStore: SessionStore,
    private val oauth: DesktopOAuth,
) : Interceptor {
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 400 || !sessionStore.isLoggedIn) return response
        val peek = response.peekBody(4096).string()
        val tokenError = peek.contains("Error occurred at the OAuth process") ||
            peek.contains("Invalid refresh token")
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
            } catch (e: OAuthException) {
                null
            }
        } ?: return response
        response.close()
        val replay = request.newBuilder()
            .header("authorization", "Bearer $refreshed")
            .build()
        return chain.proceed(replay)
    }
}
