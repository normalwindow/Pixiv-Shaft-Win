package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import ceui.pixshaft.desktop.WebAuthHost
import ceui.pixshaft.desktop.WebAuthInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WebAuthHelperPanel(
    compact: Boolean = false,
    onAvailabilityChanged: (Boolean) -> Unit = {},
) {
    val t = tr()
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(WebAuthHost.available()) }
    var pathText by remember {
        mutableStateOf(WebAuthHost.findExe()?.toAbsolutePath()?.toString().orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        installed = WebAuthHost.available()
        pathText = WebAuthHost.findExe()?.toAbsolutePath()?.toString().orEmpty()
        onAvailabilityChanged(installed)
    }

    fun download() {
        busy = true
        progress = 0f
        message = t["webAuthDownloading"]
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WebAuthInstaller.download { copied, total ->
                        progress = if (total > 0) copied.toFloat() / total.toFloat() else 0f
                    }
                }
            }
            busy = false
            progress = -1f
            result.fold(
                onSuccess = {
                    refresh()
                    message = t["webAuthDownloaded"]
                },
                onFailure = { err ->
                    message = t["webAuthDownloadFailed"] + "：" + (err.message ?: err.toString())
                },
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (installed) t["webAuthInstalled"] else t["webAuthNotInstalled"],
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                pathText.ifBlank { t["webAuthHint"] },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (!message.isNullOrBlank()) {
                Text(message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { download() },
                    enabled = !busy,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(if (installed) t["webAuthRedownload"] else t["webAuthDownload"])
                }
                if (installed) {
                    OutlinedButton(
                        onClick = {
                            val n = WebAuthInstaller.deleteCopies()
                            refresh()
                            message = t["webAuthDelete"] + " · " + n
                        },
                        enabled = !busy,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(t["webAuthDelete"])
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching { WebAuthInstaller.openReleasePage() }
                            .onFailure { message = it.message ?: it.toString() }
                    },
                    enabled = !busy,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(t["webAuthOpenPage"])
                }
                OutlinedButton(
                    onClick = {
                        val picked = WebAuthInstaller.pickExe() ?: return@OutlinedButton
                        busy = true
                        message = t["webAuthDownloading"]
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { WebAuthInstaller.installFromFile(picked) }
                            }
                            busy = false
                            result.fold(
                                onSuccess = {
                                    refresh()
                                    message = t["webAuthPicked"] + "\n" + it.toAbsolutePath()
                                },
                                onFailure = { err ->
                                    message = t["webAuthPickFailed"] + "：" + (err.message ?: err.toString())
                                },
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(t["webAuthPickFile"])
                }
                OutlinedButton(
                    onClick = {
                        runCatching { WebAuthInstaller.openInstallFolder() }
                            .onFailure { message = it.message ?: it.toString() }
                    },
                    enabled = !busy,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(t["webAuthOpenFolder"])
                }
            }
        }
    }
}
