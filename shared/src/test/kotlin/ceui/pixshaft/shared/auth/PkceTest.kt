package ceui.pixshaft.shared.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PkceTest {
    @Test
    fun challengeIsSha256OfVerifier() {
        val pair = Pkce.generate()
        assertEquals(43, pair.verifier.length)
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray()))
        assertEquals(expected, pair.challenge)
        val other = Pkce.generate()
        assertNotEquals(pair.verifier, other.verifier)
    }

    @Test
    fun parseCallbackCode() {
        assertEquals("abc", PixivOAuth.parseCallbackCode("shaft://oauth?code=abc&via=login"))
        assertEquals("xyz", PixivOAuth.parseCallbackCode("pixiv://account/login?code=xyz"))
        assertEquals("xyz", PixivOAuth.parseCallbackCode("pixiv:account/login?code=xyz"))
        assertTrue(PixivOAuth.parseCallbackCode("shaft://oauth").isNullOrEmpty())
        assertTrue(
            PixivOAuth.isCallbackUri(
                "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback?code=abc&via=login",
            ),
        )
        assertTrue(PixivOAuth.isCallbackUri("pixiv://account/login?code=xyz"))
        assertTrue(PixivOAuth.isCallbackUri("pixiv:account/login?code=xyz"))
        assertTrue(
            PixivOAuth.isCallbackUri("intent://account/login?code=abc#Intent;scheme=pixiv;end"),
        )
        assertEquals(
            "pixiv://account/login?code=abc",
            PixivOAuth.extractCallback(
                "java.net.MalformedURLException: unknown protocol: pixiv://account/login?code=abc",
            ),
        )
        assertEquals(
            "intent://account/login?code=abc#Intent;scheme=pixiv;end",
            PixivOAuth.extractCallback("intent://account/login?code=abc#Intent;scheme=pixiv;end"),
        )
        assertEquals(
            "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback?code=***&via=login",
            PixivOAuth.redact("https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback?code=secret&via=login"),
        )
        assertTrue(PixivOAuth.oauthStage("https://accounts.pixiv.net/login") < PixivOAuth.oauthStage("pixiv://account/login?code=a"))
        assertTrue(PixivOAuth.looksLikeOauthRejection("不正确的请求"))
        assertTrue(PixivOAuth.looksLikeOauthRejection("您所指定的端点不存在"))
        assertTrue(!PixivOAuth.isCallbackUri("https://app-api.pixiv.net/web/v1/users/auth/pixiv/start?via=login"))
        assertTrue(
            PixivOAuth.missingCodeMessage(
                "https://accounts.pixiv.net/post-redirect?return_to=https://app-api.pixiv.net/web/v1/users/auth/pixiv/start",
            ).contains("Chromium"),
        )
        val postRedirect =
            "https://accounts.pixiv.net/post-redirect?return_to=https%3A%2F%2Fapp-api.pixiv.net%2Fweb%2Fv1%2Fusers%2Fauth%2Fpixiv%2Fstart%3Fcode_challenge%3DjEyden4Z_w1r_t4gEyagEA39U8OlS67AOzC_R6Kv-Vk%26code_challenge_method%3DS256%26client%3Dpixiv-android%26via%3Dlogin"
        assertTrue(PixivOAuth.parseCallbackCode(postRedirect).isNullOrEmpty())
        assertTrue(PixivOAuth.extractCallback(postRedirect) == null)
        assertTrue(PixivOAuth.isIntermediateOauth(postRedirect))
        assertTrue(
            PixivOAuth.parseReturnTo(postRedirect)!!
                .startsWith("https://app-api.pixiv.net/web/v1/users/auth/pixiv/start"),
        )
        assertTrue(
            PixivOAuth.parseCallbackCode(
                "https://app-api.pixiv.net/web/v1/users/auth/pixiv/start?code_challenge=abc&code_challenge_method=S256",
            ).isNullOrEmpty(),
        )
    }
}
