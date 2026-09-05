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
    fun defaultCacheIsUnderAppDataNotSystemTemp() {
        val cache = AppPaths.defaultCache().toString()
        assertTrue(cache.endsWith("cache") || cache.endsWith("cache\\") || cache.endsWith("cache/"))
        assertFalse(cache.contains("Temp", ignoreCase = true) && cache.contains("AppData\\Local\\Temp"))
    }
}
