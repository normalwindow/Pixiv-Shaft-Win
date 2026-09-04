package ceui.pixshaft.desktop

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory

/**
 * Dummy `pixiv://` / `shaft://` handlers so JVM URL parsing and Chromium
 * protocol registration can keep the callback URL (with `code=`).
 */
object OAuthSchemes {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val prefix = "ceui.pixshaft.desktop.protocol"
            val existing = System.getProperty("java.protocol.handler.pkgs").orEmpty()
            if (!existing.contains(prefix)) {
                System.setProperty(
                    "java.protocol.handler.pkgs",
                    if (existing.isBlank()) prefix else "$existing|$prefix",
                )
            }
            runCatching { URL.setURLStreamHandlerFactory(Factory()) }
            installed = true
        }
    }

    private class Factory : URLStreamHandlerFactory {
        override fun createURLStreamHandler(protocol: String?): URLStreamHandler? {
            return if (protocol.equals("pixiv", true) || protocol.equals("shaft", true)) {
                Handler()
            } else {
                null
            }
        }
    }

    open class Handler : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection = Connection(u)
    }

    private class Connection(url: URL) : URLConnection(url) {
        override fun connect() {
            connected = true
        }

        override fun getInputStream(): InputStream {
            connect()
            return ByteArrayInputStream(ByteArray(0))
        }

        override fun getContentType(): String = "text/plain"
    }
}
