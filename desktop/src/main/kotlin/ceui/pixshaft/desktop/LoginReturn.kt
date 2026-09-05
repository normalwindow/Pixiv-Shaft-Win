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
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val latch = CountDownLatch(1)

    val isComplete: Boolean
        get() = found.get() != null || cancelled.get()

    val isCancelled: Boolean
        get() = cancelled.get()

    val uri: String?
        get() = found.get()

    fun offer(raw: String?): Boolean {
        if (cancelled.get()) return false
        val extracted = PixivOAuth.extractCallback(raw) ?: return false
        if (found.compareAndSet(null, extracted)) {
            latch.countDown()
            return true
        }
        return false
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) latch.countDown()
    }

    fun await(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)
}
