package ceui.pixshaft.desktop

import ceui.pixshaft.desktop.ui.L10n
import ceui.pixshaft.desktop.ui.horizontalWheelPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class DesktopFeatureTest {
    @Test
    fun l10nTablesShareTheSameKeys() {
        val zh = L10n.ZH.keys
        assertEquals(zh, L10n.EN.keys)
        assertEquals(zh, L10n.JA.keys)
        assertTrue(zh.containsAll(listOf(
            "addAccount", "downloadEmpty", "about", "checkUpdate", "chatReply", "restoreSidebar",
            "webAuthDownload", "webAuthDelete", "webAuthHint",
        )))
    }

    @Test
    fun versionCompareHandlesTags() {
        assertTrue(AppVersion.compare("0.0.3", "0.0.4") < 0)
        assertTrue(AppVersion.compare("0.0.3", "v0.0.3") == 0)
        assertTrue(AppVersion.compare("1.2.0", "1.1.9") > 0)
        assertEquals("0.0.4", AppVersion.parseTag("v0.0.4"))
    }

    @Test
    fun coversDisplayDetectsMaximizedButNotHalfSnap() {
        val work = Rectangle(0, 0, 1920, 1040)
        val screen = Rectangle(0, 0, 1920, 1080)
        assertTrue(WinNative.coversDisplay(Rectangle(0, 0, 1920, 1040), work, screen))
        assertTrue(WinNative.coversDisplay(Rectangle(1, 0, 1919, 1080), work, screen))
        assertTrue(!WinNative.coversDisplay(Rectangle(0, 0, 960, 1040), work, screen))
    }

    @Test
    fun horizontalWheelBoostsTinyNotches() {
        assertEquals(-96f, horizontalWheelPixels(0f, -1f), 0.01f)
        assertEquals(192f, horizontalWheelPixels(2f, 0f), 0.01f)
        assertEquals(160f, horizontalWheelPixels(0f, 50f), 0.01f)
        assertEquals(0f, horizontalWheelPixels(0f, 0f), 0.01f)
    }

    @Test
    fun chatProtocolEncodesReplyTo() {
        val (_, body) = ChatProtocol.encodeGlobal(
            text = "hello \"world\"",
            clientMsgId = "abc",
            illustId = 42L,
            replyTo = ChatReplyRef(7L, "parent-id"),
        )
        assertTrue(body.contains("\"kind\":\"msg\""))
        assertTrue(body.contains("\"room\":\"global\""))
        assertTrue(body.contains("\"illust_id\":42"))
        assertTrue(body.contains("\"reply_to\":{\"uid\":7"))
        assertTrue(body.contains("hello \\\"world\\\""))
        val obj = com.google.gson.JsonParser.parseString(
            "{\"uid\":7,\"client_msg_id\":\"parent-id\",\"display_name\":\"n\",\"text\":\"hi\"}"
        ).asJsonObject
        val ref = ChatProtocol.decodeReplyTo(obj)!!
        assertEquals(7L, ref.uid)
        assertEquals("parent-id", ref.clientMsgId)
        assertEquals("n", ref.displayName)
    }

    @Test
    fun queueJobRoundTripsThumbUrl() {
        val gson = com.google.gson.Gson()
        val job = QueueJob(
            id = "1",
            illustId = 99L,
            title = "t",
            urls = listOf("https://example/a.jpg"),
            status = "pending",
            finished = 1,
            total = 3,
            thumbUrl = "https://example/s.jpg",
        )
        val copy = gson.fromJson(gson.toJson(job), QueueJob::class.java)
        assertEquals("https://example/s.jpg", copy.thumbUrl)
        assertEquals(99L, copy.illustId)
        assertEquals(1, copy.finished)
    }

    @Test
    fun windowChromeHopsBetweenMaximizeAndFullscreen() {
        val max = androidx.compose.ui.window.WindowPlacement.Maximized
        val full = androidx.compose.ui.window.WindowPlacement.Fullscreen
        val float = androidx.compose.ui.window.WindowPlacement.Floating
        assertTrue(WindowChrome.hopRequired(max, full))
        assertTrue(WindowChrome.hopRequired(full, max))
        assertTrue(!WindowChrome.hopRequired(float, full))
        assertTrue(!WindowChrome.hopRequired(max, float))
    }

    @Test
    fun overscanOnlyCountsPixelsPastTheWorkArea() {
        val work = Rectangle(0, 0, 1920, 1040)
        val over = WindowChrome.overscan(Rectangle(-8, -8, 1936, 1056), work)
        assertEquals(8, over.top)
        assertEquals(8, over.left)
        assertEquals(8, over.right)
        assertEquals(8, over.bottom)
        val none = WindowChrome.overscan(Rectangle(100, 100, 800, 600), work)
        assertEquals(0, none.top + none.left + none.right + none.bottom)
        val taskbar = WindowChrome.overscan(Rectangle(0, 0, 1920, 1080), work)
        assertEquals(12, taskbar.bottom)
        assertTrue(taskbar.bottom < 40)
    }

    @Test
    fun webAuthInstallerParsesReleaseAsset() {
        val json = "{" + "\"assets\":[{\"name\":\"PixShaftWebAuth.exe\",\"browser_download_url\":\"https://example/PixShaftWebAuth.exe\"}]" + "}"
        assertEquals("https://example/PixShaftWebAuth.exe", WebAuthInstaller.parseAssetUrl(json))
        assertEquals("PixShaftWebAuth.exe", WebAuthInstaller.managedPath.fileName.toString())
        assertEquals("bin", WebAuthInstaller.managedPath.parent.fileName.toString())
        assertTrue(WebAuthInstaller.fallbackUrl().endsWith("/PixShaftWebAuth.exe"))
        val listJson = "[" + "{\"assets\":[{\"name\":\"PixShaftWebAuth.exe\",\"browser_download_url\":\"https://example/pre.exe\"}]}" + "]"
        assertEquals("https://example/pre.exe", WebAuthInstaller.parseAssetUrl(listJson))
        val msg = WebAuthInstaller.humanize(listOf("direct: HTTP 404 https://github.com/x"))
        assertTrue(msg.contains("PixShaftWebAuth.exe"))
    }
}
