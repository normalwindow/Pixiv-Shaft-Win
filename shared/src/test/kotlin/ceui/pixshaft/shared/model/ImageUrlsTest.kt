package ceui.pixshaft.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageUrlsTest {
    @Test
    fun thumbnailNeverUsesOriginal() {
        val urls = ImageUrls(
            original = "https://i.pximg.net/img-original/x.png",
            large = "https://i.pximg.net/c/600/x.jpg",
            medium = "https://i.pximg.net/c/540/x.jpg",
            square_medium = "https://i.pximg.net/c/360/x.jpg",
        )
        assertEquals(urls.square_medium, urls.thumbnail())
        assertEquals(urls.large, urls.display())
        assertEquals(urls.original, urls.originalOrLarge())
    }

    @Test
    fun thumbnailFallsBackWithoutOriginal() {
        val urls = ImageUrls(original = "https://i.pximg.net/img-original/x.png")
        assertNull(urls.thumbnail())
        assertEquals(urls.original, urls.originalOrLarge())
    }

    @Test
    fun previewPicksSquareUnlessLargeRequested() {
        val illust = Illust(
            id = 1,
            image_urls = ImageUrls(
                original = "o",
                large = "l",
                medium = "m",
                square_medium = "s",
            ),
        )
        assertEquals("s", illust.previewUrl(large = false))
        assertEquals("l", illust.previewUrl(large = true))
    }
}
