package ceui.pixshaft.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivDnsTest {
    @Test
    fun apiHostsPinCloudflare() {
        assertEquals(
            listOf(PixivDns.CF_IP_PRIMARY, PixivDns.CF_IP_SECONDARY),
            PixivDns.fallbackFor("app-api.pixiv.net"),
        )
        assertEquals(
            PixivDns.FALLBACK_API_IPS,
            PixivDns.fallbackFor("oauth.secure.pixiv.net"),
        )
        assertEquals(
            PixivDns.FALLBACK_API_IPS,
            PixivDns.fallbackFor("accounts.pixiv.net"),
        )
    }

    @Test
    fun imageHostsPinLegacyPixiv() {
        assertEquals(PixivDns.FALLBACK_IMAGE_IPS, PixivDns.fallbackFor("i.pximg.net"))
        assertEquals(PixivDns.FALLBACK_IMAGE_IPS, PixivDns.fallbackFor("s.pximg.net"))
    }

    @Test
    fun unknownHostsHaveNoPin() {
        assertTrue(PixivDns.fallbackFor("example.com").isEmpty())
        assertTrue(!PixivDns.isPinnedHost("example.com"))
        assertTrue(PixivDns.isPinnedHost("app-api.pixiv.net"))
    }

    @Test
    fun parsesDohARecords() {
        val json = """
            {"Status":0,"Answer":[
              {"name":"app-api.pixiv.net.","type":1,"TTL":60,"data":"104.18.42.239"},
              {"name":"app-api.pixiv.net.","type":5,"TTL":60,"data":"ignored.example."},
              {"name":"app-api.pixiv.net.","type":1,"TTL":60,"data":"172.64.145.17"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("104.18.42.239", "172.64.145.17"),
            PixivDns.parseDohAnswers(json),
        )
    }
}
