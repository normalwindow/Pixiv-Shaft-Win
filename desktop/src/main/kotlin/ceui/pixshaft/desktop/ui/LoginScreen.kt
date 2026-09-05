package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    graph: AppGraph,
    error: String?,
    onLoggedIn: () -> Unit,
    onError: (String?) -> Unit,
) {
    var refreshToken by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var webStatus by remember { mutableStateOf("") }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun abort() {
        graph.cancelLogin()
        loginJob?.cancel()
        loginJob = null
        busy = false
    }

    fun complete(text: String) {
        val value = text.trim()
        if (value.isEmpty() || busy) return
        busy = true
        onError(null)
        loginJob = scope.launch {
            runCatching {
                if (value.contains("://")) graph.completeLoginFromUri(value)
                else graph.loginWithRefreshToken(value)
            }.onSuccess { onLoggedIn() }
                .onFailure { onError(it.userMessage()) }
            busy = false
        }
    }

    fun startWebLogin() {
        abort()
        onError(null)
        busy = true
        webStatus = "正在打开登录窗…"
        loginJob = scope.launch {
            try {
                graph.loginWithChromium { webStatus = it }
                onLoggedIn()
            } catch (_: CancellationException) {
                webStatus = "已取消"
            } catch (error: Throwable) {
                onError(error.userMessage())
            } finally {
                busy = false
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(28.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PixShaft", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Windows 上的 Pixiv 客户端",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { startWebLogin() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (busy) "登录中…" else "网页登录")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { startWebLogin() },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("刷新")
                    }
                    TextButton(
                        onClick = {
                            abort()
                            webStatus = "已取消"
                            onError(null)
                        },
                        enabled = busy,
                        modifier = Modifier.width(88.dp),
                    ) {
                        Text("取消")
                    }
                }
                if (busy || webStatus.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(webStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    "网页登录使用系统 WebView2（与 Pixeval 相同），拦截 pixiv:// 回调。卡住请点刷新。也可粘贴回调或 refresh_token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = refreshToken,
                    onValueChange = { refreshToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("refresh_token 或回调链接") },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { complete(refreshToken) },
                    enabled = !busy && refreshToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("用 token / 回调登录")
                }
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error.take(500), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
