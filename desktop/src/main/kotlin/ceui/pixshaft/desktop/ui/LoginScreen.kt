package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
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
    val scope = rememberCoroutineScope()

    fun complete(text: String) {
        val value = text.trim()
        if (value.isEmpty() || busy) return
        busy = true
        onError(null)
        scope.launch {
            runCatching {
                if (value.contains("://")) graph.completeLoginFromUri(value)
                else graph.loginWithRefreshToken(value)
            }.onSuccess { onLoggedIn() }
                .onFailure { onError(it.message ?: it.toString()) }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Text("PixShaft", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Unofficial Pixiv client for Windows. Fork of CeuiLiSA/Pixiv-Shaft.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (busy) return@Button
                    onError(null)
                    busy = true
                    webStatus = "正在启动 Chromium 登录窗…"
                    scope.launch {
                        runCatching {
                            graph.loginWithChromium { webStatus = it }
                        }.onSuccess { onLoggedIn() }
                            .onFailure { onError(it.message ?: it.toString()) }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "登录中…" else "用 Chromium 登录（推荐）")
            }
            Spacer(Modifier.height(8.dp))
            if (busy || webStatus.isNotBlank()) {
                Text(
                    webStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "登录会弹出独立的 Edge/Chrome 窗口（Android 身份 + QUIC）。请在该窗口完成 Pixiv 登录，不要关窗口切去系统浏览器。成功后窗口会自动关掉并回到本应用。若系统询问“打开 PixShaft”，请允许。也可粘贴地址栏里带 code= 的 pixiv:// / shaft:// 回调或 refresh_token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = refreshToken,
                onValueChange = { refreshToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("refresh_token 或 shaft:// / pixiv:// 回调") },
                minLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { complete(refreshToken) },
                enabled = !busy && refreshToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "登录中…" else "用 refresh_token / 回调登录")
            }
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
