package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.AppPaths
import ceui.pixshaft.desktop.AppVersion
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files

@Composable
fun AboutScreen(graph: AppGraph) {
    val t = tr()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val runtime = remember {
        val os = System.getProperty("os.name").orEmpty() + " " + System.getProperty("os.arch").orEmpty()
        val jre = System.getProperty("java.vendor").orEmpty() + " " + System.getProperty("java.version").orEmpty()
        os + " · " + jre
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource("icon.xml"),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text("PixShaft-Win", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    t["version"] + " " + AppVersion.DISPLAY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            t["aboutBlurb"],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(t["forkNotice"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t["checkUpdate"], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            checking = true
                            updateText = t["checkingUpdate"]
                            updateUrl = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { checkLatestRelease(graph) }
                                checking = false
                                when (result) {
                                    is UpdateResult.Latest -> {
                                        updateText = t["upToDate"] + " · " + AppVersion.DISPLAY
                                        updateUrl = AppVersion.RELEASES_URL
                                    }
                                    is UpdateResult.Newer -> {
                                        updateText = t["updateAvailable"] + " " + result.tag
                                        updateUrl = result.htmlUrl ?: AppVersion.RELEASES_URL
                                    }
                                    is UpdateResult.Failed -> {
                                        updateText = t["updateFailed"] + "：" + result.reason
                                        updateUrl = AppVersion.RELEASES_URL
                                    }
                                }
                            }
                        },
                        enabled = !checking,
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) t["checkingUpdate"] else t["checkUpdate"])
                    }
                    if (!updateText.isNullOrBlank()) {
                        Text(updateText!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!updateUrl.isNullOrBlank()) {
                    OutlinedButton(onClick = { openUrl(updateUrl!!) }) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t["openReleases"])
                    }
                }
            }
        }

        AboutLink(Icons.Outlined.Code, t["openGithub"], AppVersion.GITHUB_URL)
        AboutLink(Icons.Outlined.NewReleases, t["openReleases"], AppVersion.RELEASES_URL)
        AboutLink(Icons.Outlined.BugReport, t["openIssues"], AppVersion.ISSUES_URL)
        AboutLink(Icons.Outlined.PhoneAndroid, t["openUpstream"], AppVersion.UPSTREAM_URL)
        AboutLink(Icons.Outlined.Language, t["openWebsite"], AppVersion.WEBSITE)

        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t["runtime"], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(runtime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    t["license"] + " · " + AppVersion.LICENSE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    t["credits"] + " · CeuiLiSA / Pixiv-Shaft · Compose Multiplatform · Coil · OkHttp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runCatching {
                    Files.createDirectories(AppPaths.root)
                    Desktop.getDesktop().open(AppPaths.root.toFile())
                }
            }) {
                Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(t["openDataFolder"])
            }
            OutlinedButton(onClick = {
                val log = AppPaths.root.resolve("crash.log")
                runCatching {
                    if (!Files.exists(log)) Files.writeString(log, "")
                    Desktop.getDesktop().open(log.toFile())
                }
            }) {
                Text(t["openCrashLog"])
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AboutLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, url: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        onClick = { openUrl(url) },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

private fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

private sealed class UpdateResult {
    data object Latest : UpdateResult()
    data class Newer(val tag: String, val htmlUrl: String?) : UpdateResult()
    data class Failed(val reason: String) : UpdateResult()
}

private fun checkLatestRelease(graph: AppGraph): UpdateResult = runCatching {
    val request = Request.Builder()
        .url(AppVersion.RELEASES_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "PixShaft-Win/" + AppVersion.NAME)
        .build()
    graph.http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return UpdateResult.Failed("HTTP " + resp.code)
        val body = resp.body?.string().orEmpty()
        val obj = JsonParser.parseString(body).asJsonObject
        val tag = obj.get("tag_name")?.asString.orEmpty()
        val html = obj.get("html_url")?.asString
        if (tag.isBlank()) return UpdateResult.Failed("empty tag")
        val cmp = AppVersion.compare(AppVersion.NAME, tag)
        if (cmp >= 0) UpdateResult.Latest else UpdateResult.Newer(AppVersion.parseTag(tag), html)
    }
}.getOrElse { UpdateResult.Failed(it.message ?: it.javaClass.simpleName) }
