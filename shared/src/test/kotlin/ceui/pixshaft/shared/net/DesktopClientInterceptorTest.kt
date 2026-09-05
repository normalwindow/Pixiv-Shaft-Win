package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.auth.DesktopOAuth
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.session.SessionStore
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class DesktopClientInterceptorTest {
    @Test
    fun transportSeesAuthorizationAfterHeaderInterceptor() {
        val dir = Files.createTempDirectory("pixshaft-session")
        val store = SessionStore(dir.resolve("session.json"), Gson())
        store.save(
            StoredSession(
                accessToken = "tok-abc",
                refreshToken = "ref",
                expiresAtMillis = Long.MAX_VALUE,
                user = null,
            ),
        )
        val captured = AtomicReference<Request>()
        val transport = Interceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val oauth = DesktopOAuth(OkHttpClient(), Gson())
        val http = DesktopClient.apiHttp(DesktopClient.defaultHttp(), store, oauth, transport)
        http.newCall(
            Request.Builder().url("https://app-api.pixiv.net/v1/illust/detail?illust_id=1").build(),
        ).execute().close()
        val seen = captured.get()
        assertEquals("Bearer tok-abc", seen.header("authorization"))
        assertEquals(PixivClientIdentity.USER_AGENT, seen.header("user-agent"))
        assertTrue(!seen.header("x-client-hash").isNullOrBlank())
        assertTrue(!seen.header("x-client-time").isNullOrBlank())
    }

    @Test
    fun transportFirstMissesAuthorization() {
        val dir = Files.createTempDirectory("pixshaft-session")
        val store = SessionStore(dir.resolve("session.json"), Gson())
        store.save(
            StoredSession(
                accessToken = "tok-abc",
                refreshToken = "ref",
                expiresAtMillis = Long.MAX_VALUE,
                user = null,
            ),
        )
        val captured = AtomicReference<Request>()
        val transport = Interceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val oauth = DesktopOAuth(OkHttpClient(), Gson())
        val broken = DesktopClient.defaultHttp().newBuilder()
            .addInterceptor(transport)
            .addInterceptor(HeaderInterceptor(store))
            .build()
        broken.newCall(
            Request.Builder().url("https://app-api.pixiv.net/v1/illust/detail?illust_id=1").build(),
        ).execute().close()
        assertTrue(captured.get().header("authorization").isNullOrBlank())
    }
}
