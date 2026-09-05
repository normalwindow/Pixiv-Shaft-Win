package ceui.pixshaft.shared.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ErrorsTest {
    @Test
    fun mapsNetworkFailures() {
        assertTrue(IOException("Failed to fetch").userMessage().contains("换令牌"))
        assertTrue(SocketTimeoutException("timeout").userMessage().contains("超时"))
        assertTrue(IOException("Connection reset").userMessage().contains("代理"))
        assertTrue(IllegalStateException().userMessage().isNotBlank())
    }
}
