package ceui.pixshaft.shared.net

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Desktop port of Android [ceui.lisa.http.RubySSLSocketFactory]:
 * GFW resets TLS that carries `i.pximg.net` in SNI. Passing a null hostname
 * omits the SNI extension; the image CDN routes by IP.
 */
object DirectTls {
    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    val noSniFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), SecureRandom())
        NoSniSslSocketFactory(context.socketFactory)
    }
}

internal class NoSniSslSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
        val sni = if (host != null && host.endsWith("pximg.net", ignoreCase = true)) null else host
        val ssl = delegate.createSocket(s, sni, port, autoClose) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols
        return ssl
    }

    override fun createSocket(host: String?, port: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)")

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)")

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)")

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = throw UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)")
}
