package ceui.pixshaft.shared.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageHostsTest {
    @Test
    fun officialLeavesPximg() {
        ImageHosts.configure(ImageHosts.MODE_PIXIV, "")
        val url = "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/123_p0.jpg"
        assertEquals(url, ImageHosts.rewrite(url))
    }

    @Test
    fun catMapsBothHosts() {
        ImageHosts.configure(ImageHosts.MODE_CAT, "")
        assertEquals(
            "https://i.pixiv.cat/img-original/img/x.jpg",
            ImageHosts.rewrite("https://i.pximg.net/img-original/img/x.jpg"),
        )
        assertEquals(
            "https://s.pixiv.cat/common/images/no_profile.png",
            ImageHosts.rewrite("https://s.pximg.net/common/images/no_profile.png"),
        )
    }

    @Test
    fun reAndCustomPreservePath() {
        ImageHosts.configure(ImageHosts.MODE_RE, "")
        assertEquals(
            "https://i.pixiv.re/path/foo.jpg?bar=1",
            ImageHosts.rewrite("https://i.pximg.net/path/foo.jpg?bar=1"),
        )
        ImageHosts.configure(ImageHosts.MODE_CUSTOM, "https://cdn.example/pixiv")
        assertEquals(
            "https://cdn.example/pixiv/img/x.jpg",
            ImageHosts.rewrite("https://i.pximg.net/img/x.jpg"),
        )
    }
}
