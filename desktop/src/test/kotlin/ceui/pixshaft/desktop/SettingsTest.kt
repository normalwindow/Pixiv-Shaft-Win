package ceui.pixshaft.desktop

import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun fileNameUsesTemplateAndSanitizesAuthor() {
        val illust = Illust(
            id = 123,
            title = "a/b",
            page_count = 2,
            user = User(id = 1, name = "x:y"),
        )
        val settings = DesktopSettings(illustFileName = "{illust_id}_{author}_{index}.{ext}")
        assertEquals("123_x_y_0.jpg", settings.illustFileName(illust, 0, "jpg"))
    }

    @Test
    fun r18AndAiFiltersHideMatchingWorks() {
        val r18 = Illust(id = 1, x_restrict = 1)
        val ai = Illust(id = 2, illust_ai_type = 2)
        val normal = Illust(id = 3)
        val settings = DesktopSettings(r18FilterDefaultEnable = true, deleteAIIllust = true)
        assertTrue(settings.shouldHide(r18))
        assertTrue(settings.shouldHide(ai))
        assertFalse(settings.shouldHide(normal))
    }

    @Test
    fun featureColumnFactoriesCoverFeedKinds() {
        assertEquals("following", FeatureColumn.following("public").kind)
        assertEquals("public", FeatureColumn.following("public").key)
        assertEquals("关注（公开）", FeatureColumn.following("public").title)
        assertEquals("related", FeatureColumn.related(99).kind)
        assertEquals("newest", FeatureColumn.newest().kind)
        assertEquals("bookmarks", FeatureColumn.bookmarks().kind)
        assertEquals("关注动态", FeatureColumn.following("all").kindLabel())
    }

    @Test
    fun featureColumnJsonRoundTripKeepsKindKeyTitle() {
        val gson = com.google.gson.Gson()
        val original = listOf(
            FeatureColumn.author(42L, "alice"),
            FeatureColumn.search("cat"),
            FeatureColumn.following("public"),
        )
        val json = gson.toJson(original)
        val type = object : com.google.gson.reflect.TypeToken<MutableList<FeatureColumn>>() {}.type
        val restored: List<FeatureColumn> = gson.fromJson(json, type)
        assertEquals(3, restored.size)
        assertEquals("author", restored[0].kind)
        assertEquals("42", restored[0].key)
        assertEquals("alice 的作品", restored[0].title)
        assertEquals("search", restored[1].kind)
        assertEquals("cat", restored[1].key)
        assertEquals("following", restored[2].kind)
        assertEquals("public", restored[2].key)
    }

    @Test
    fun commentBodyPrefersTextThenStampPlaceholder() {
        val text = ceui.pixshaft.shared.model.Comment(comment = "hello")
        val stamp = ceui.pixshaft.shared.model.Comment(
            stamp = ceui.pixshaft.shared.model.CommentStamp(stamp_id = 1, stamp_url = "https://example/s.png"),
        )
        val empty = ceui.pixshaft.shared.model.Comment()
        assertEquals("hello", text.bodyText())
        assertEquals("[stamp]", stamp.bodyText())
        assertEquals("", empty.bodyText())
    }

    @Test
    fun defaultSettingsEnableDirectConnectCustomTitleBarAndWaterfall() {
        val s = DesktopSettings()
        assertTrue(s.directConnect)
        assertTrue(s.customTitleBar)
        assertEquals(0, s.browseLayout)
        assertEquals(0, s.detailStyle)
        assertEquals("home", s.navigationInitPosition)
    }

    @Test
    fun defaultCacheIsUnderAppDataNotSystemTemp() {
        val cache = AppPaths.defaultCache().toString()
        assertTrue(cache.endsWith("cache") || cache.endsWith("cache\\") || cache.endsWith("cache/"))
        assertFalse(cache.contains("Temp", ignoreCase = true) && cache.contains("AppData\\Local\\Temp"))
    }
}
