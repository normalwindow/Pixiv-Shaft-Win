package ceui.pixshaft.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    @Test
    fun parsesArtworkAndUser() {
        assertEquals(Dest.Artwork(123), DeepLinks.parse("https://www.pixiv.net/artworks/123"))
        assertEquals(Dest.User(9), DeepLinks.parse("https://www.pixiv.net/users/9"))
        assertNull(DeepLinks.parse("pixiv://account/login?code=abc"))
        assertNull(DeepLinks.parse("https://accounts.pixiv.net/post-redirect"))
    }
}
