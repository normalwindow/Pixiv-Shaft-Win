package ceui.pixshaft.desktop

import ceui.pixshaft.shared.auth.PixivOAuth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * First valid OAuth callback wins.
 *
 * The Chromium DevTools watcher, `pixiv://` / `shaft://` protocol handler,
 * clipboard paste, and `/json/list` polling all offer URLs here so login
 * can finish even when the callback happens outside the attached page.
 */
class LoginReturnChannel {
    private val found = AtomicReference<String?>(null)
    private val latch = CountDownLatch(1)

    val isComplete: Boolean
        get() = found.get() != null

    val uri: String?
        get() = found.get()

    fun offer(raw: String?): Boolean {
        val extracted = PixivOAuth.extractCallback(raw) ?: return false
        if (found.compareAndSet(null, extracted)) {
            latch.countDown()
            return true
        }
        return false
    }

    fun await(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)
}
