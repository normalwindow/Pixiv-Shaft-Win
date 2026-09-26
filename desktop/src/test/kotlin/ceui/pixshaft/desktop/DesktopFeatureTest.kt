package ceui.pixshaft.desktop

import ceui.pixshaft.desktop.ui.L10n
import ceui.pixshaft.desktop.ui.ShaftMouseButton
import ceui.pixshaft.desktop.ui.horizontalWheelPixels
import ceui.pixshaft.desktop.ui.quickToastKey
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
    fun queueJobProgressCoversSinglePageDownloads() {
        // 单图：只有 1 页，光看 finished/total 会一直是 0 然后直接跳到 1（就是那个 bug）
        val single = QueueJob(
            id = "1", illustId = 1L, title = "t",
            urls = listOf("https://example/a.jpg"), status = "running",
            finished = 0, total = 1, pageBytes = 0L, pageTotal = 500_000L,
        )
        assertEquals(0f, single.progress(), 0.0001f)
        assertEquals(0.5f, single.copy(pageBytes = 250_000L).progress(), 0.0001f)
        assertEquals(1f, single.copy(pageBytes = 500_000L).progress(), 0.0001f)
        // 没有 Content-Length 时退回页数进度，不能除以 0
        assertEquals(0f, single.copy(pageTotal = 0L, pageBytes = 999L).progress(), 0.0001f)

        // 多页：页内字节只占这一页的那一份
        val multi = QueueJob(
            id = "2", illustId = 2L, title = "t",
            urls = List(4) { "https://example/$it.jpg" }, status = "running",
            finished = 1, total = 4, pageBytes = 50L, pageTotal = 100L,
        )
        assertEquals(0.375f, multi.progress(), 0.0001f)
        assertEquals("1/4 · 50%", multi.progressText())
    }

    @Test
    fun queueJobFormatsBytes() {
        // 已下完 2MiB，正在下第 3 页（512KB / 1MiB）
        val job = QueueJob(
            id = "1", illustId = 1L, title = "t",
            urls = listOf("https://example/a.jpg"), status = "running",
            error = null, finished = 2, total = 3, thumbUrl = null,
            pageBytes = 512_000L, pageTotal = 1_048_576L,
            bytesDone = 2_097_152L, bytesTotal = 2_097_152L + 1_048_576L,
        )
        assertEquals(2_097_152L + 512_000L, job.downloadedBytes)
        // 已知总量 = 三页 Content-Length 之和（含正在下的第 3 页），不能把当前页算两遍
        assertEquals(3_145_728L, job.knownBytes)
        assertEquals("2.5MB", ceui.pixshaft.desktop.humanBytes(job.downloadedBytes))
        assertEquals("3.0MB", ceui.pixshaft.desktop.humanBytes(job.knownBytes))
        assertEquals("2.5MB / 3.0MB", job.bytesText())
        // 总量未知（服务器没给 Content-Length）时只报已下载
        assertEquals("2.5MB", job.copy(bytesTotal = 0L, pageTotal = 0L).bytesText())
        assertEquals("", job.copy(bytesDone = 0L, pageBytes = 0L, bytesTotal = 0L, pageTotal = 0L).bytesText())
    }

    @Test
    fun mouseButtonIndicesMapToSupportedButtons() {
        assertEquals(ShaftMouseButton.Right, ShaftMouseButton.fromBackIndex(0))
        assertEquals(ShaftMouseButton.Middle, ShaftMouseButton.fromBackIndex(1))
        assertEquals(ShaftMouseButton.Back, ShaftMouseButton.fromBackIndex(2))
        assertEquals(ShaftMouseButton.Forward, ShaftMouseButton.fromBackIndex(3))
        // 越界 / 老配置一律退回默认值
        assertEquals(ShaftMouseButton.Right, ShaftMouseButton.fromBackIndex(-1))
        assertEquals(ShaftMouseButton.Right, ShaftMouseButton.fromBackIndex(99))
        assertEquals(ShaftMouseButton.Middle, ShaftMouseButton.fromDownloadIndex(0))
        assertEquals(ShaftMouseButton.Middle, ShaftMouseButton.fromDownloadIndex(7))
        // 快捷下载不开放右键（右键是卡片上下文菜单）
        assertTrue(ShaftMouseButton.downloadOptions.none { it == ShaftMouseButton.Right })
    }

    @Test
    fun quickToastKeyCarriesIllustId() {
        val key = quickToastKey(12345L)
        assertEquals("12345", key.substringBefore('#'))
        assertTrue(key.contains('#'))
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
        assertTrue(WebAuthInstaller.releasePageUrl().endsWith("/releases"))
        val missing = java.nio.file.Path.of("C:\\definitely-not-pixshaft-webauth.exe")
        val missingErr = runCatching { WebAuthInstaller.installFromFile(missing) }.exceptionOrNull()
        assertTrue(missingErr is java.io.IOException)
        val tiny = java.nio.file.Files.createTempFile("webauth-tiny", ".exe")
        try {
            java.nio.file.Files.writeString(tiny, "nope")
            val tinyErr = runCatching { WebAuthInstaller.installFromFile(tiny) }.exceptionOrNull()
            assertTrue(tinyErr is java.io.IOException)
            assertTrue(tinyErr!!.message!!.contains("太小"))
        } finally {
            java.nio.file.Files.deleteIfExists(tiny)
        }
    }
}
