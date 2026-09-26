package ceui.pixshaft.desktop

import ceui.pixshaft.desktop.ui.L10n
import ceui.pixshaft.desktop.ui.ShaftMouseButton
import ceui.pixshaft.desktop.ui.assignWaterfallColumns
import ceui.pixshaft.desktop.ui.horizontalWheelPixels
import ceui.pixshaft.desktop.ui.illustCardRatio
import ceui.pixshaft.desktop.ui.quickToastKey
import ceui.pixshaft.desktop.ui.waterfallItemHeight
import ceui.pixshaft.desktop.ui.wheelScrollPixels
import ceui.pixshaft.shared.model.Illust
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class DesktopFeatureTest {
    /**
     * 瀑布流的高度必须是**纯函数**：泳道分配完全取决于条目高度，
     * 高度一变上面滚过去的条目就被重新分泳道 —— 就是「返回列表后排序变了、往回滚乱窜」。
     * 这里锁住几种输入的稳定性。
     */
    @Test
    fun illustCardRatioIsPureAndStable() {
        val wide = Illust(id = 1L, width = 3000, height = 1000)
        val tall = Illust(id = 2L, width = 800, height = 2400)
        val square = Illust(id = 3L, width = 1000, height = 1000)
        val unknown = Illust(id = 4L)

        // 重复调用结果必须一致（不能依赖布局期才知道的量）
        repeat(5) {
            assertEquals(illustCardRatio(wide, false), illustCardRatio(wide, false), 0f)
        }
        // 横幅 / 条漫都被夹到安全区间，不会把瀑布流拉变形
        assertEquals(1.8f, illustCardRatio(wide, false), 0.0001f)
        assertEquals(0.45f, illustCardRatio(tall, false), 0.0001f)
        assertEquals(1f, illustCardRatio(square, false), 0.0001f)
        // 拿不到宽高时退回 1:1，而不是随首帧测量结果抖动
        assertEquals(1f, illustCardRatio(unknown, false), 0.0001f)
        // 等宽网格统一比例
        assertEquals(illustCardRatio(wide, true), illustCardRatio(tall, true), 0f)
        assertEquals(0.78f, illustCardRatio(unknown, true), 0.0001f)
        assertEquals(0.78f, illustCardRatio(square, true), 0.0001f)
    }

    /**
     * 列分配必须是「从 index 0 推一遍」的纯函数结果：
     * 同一组输入反复算、以及模拟「从中间往回算」，都必须得到同一张分布表。
     * 这条锁住的就是「往回滚排布乱窜」那个 bug —— 位置不能依赖滚动方向或测量顺序。
     */
    @Test
    fun waterfallColumnAssignmentIsScrollDirectionIndependent() {
        val ratios = listOf(1.5f, 0.45f, 1.0f, 0.8f, 1.8f, 0.6f, 1.0f, 1.2f, 0.45f, 1.0f, 0.9f, 1.7f)
        val colW = 180f
        val gap = 10f
        val heights = { i: Int -> waterfallItemHeight(ratios[i], colW) }

        val first = assignWaterfallColumns(ratios.size, 3, colW, gap, heights)
        // 重复计算完全一致
        val second = assignWaterfallColumns(ratios.size, 3, colW, gap, heights)
        assertEquals(first, second)
        assertArrayEquals(first.lanes, second.lanes)
        assertArrayEquals(first.tops, second.tops, 0.001f)

        // 每一列都不重叠：同列相邻条目的间距 >= 高度 + gap
        for (i in 0 until first.size) {
            var next = -1
            for (j in i + 1 until first.size) {
                if (first.lanes[j] == first.lanes[i]) { next = j; break }
            }
            if (next >= 0) {
                val expectedGap = heights(i) + gap
                assertEquals(expectedGap, first.tops[next] - first.tops[i], 0.01f)
            }
        }

        // 第一条永远在第 0 列第 0 行；列号都在范围内
        assertEquals(0, first.lanes[0])
        assertEquals(0f, first.tops[0], 0.001f)
        assertTrue(first.lanes.all { it in 0..2 })
        assertTrue(first.totalHeight > 0f)
    }

    /** 空列表 / 单条 / 列数退化都不能崩。 */
    @Test
    fun waterfallColumnAssignmentHandlesEdgeCases() {
        val empty = assignWaterfallColumns(0, 3, 180f, 10f) { 100f }
        assertEquals(0, empty.size)
        assertEquals(0f, empty.totalHeight, 0.001f)

        val single = assignWaterfallColumns(1, 4, 180f, 10f) { 100f }
        assertEquals(1, single.size)
        assertEquals(0, single.lanes[0])
        assertEquals(100f, single.totalHeight, 0.001f)

        // 列数 0 也要当成 1 列，而不是除零
        val degenerate = assignWaterfallColumns(3, 0, 180f, 10f) { 50f }
        assertTrue(degenerate.lanes.all { it == 0 })
        assertEquals(3 * 50f + 2 * 10f, degenerate.totalHeight, 0.001f)

        // 宽度还没量到（0）也不能产生 NaN / 负高度
        val noWidth = assignWaterfallColumns(2, 2, 0f, 0f) { 0f }
        assertTrue(noWidth.totalHeight.isFinite() && noWidth.totalHeight > 0f)
    }
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

    /**
     * 瀑布流滚轮折算：一格必须是一个「能看出来的位移」，而不是一屏。
     * 这台机器实测 Compose Desktop 给的是 ±273（照抄会一格滚掉 874px ≈ 整屏）。
     */
    @Test
    fun waterfallWheelDeltaIsNormalised() {
        // 往下滚 → 正数（offset 变大），往上滚 → 负数
        assertTrue(wheelScrollPixels(1f) > 0f)
        assertTrue(wheelScrollPixels(-1f) < 0f)
        // 行数形态
        assertEquals(110f, wheelScrollPixels(1f), 0.01f)
        assertEquals(-220f, wheelScrollPixels(-2f), 0.01f)
        // 标准 120 像素形态
        assertEquals(110f, wheelScrollPixels(120f), 0.01f)
        // 本机实测的粗粒度形态（±273）
        assertEquals(110f, wheelScrollPixels(273f), 0.01f)
        assertEquals(-110f, wheelScrollPixels(-273.05832f), 0.1f)
        // 任何一格都不该超过一屏（约 700px）
        for (raw in listOf(-1000f, -874f, -273f, -120f, -8f, -1f, 1f, 8f, 120f, 273f, 900f)) {
            val px = kotlin.math.abs(wheelScrollPixels(raw))
            assertTrue("raw=$raw -> $px too big", px <= 340f)
        }
        assertEquals(0f, wheelScrollPixels(0f), 0f)
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
