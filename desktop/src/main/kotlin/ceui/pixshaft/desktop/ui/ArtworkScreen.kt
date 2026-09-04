package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.model.Illust
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.Request

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtworkScreen(
    graph: AppGraph,
    id: Long,
    loader: ImageLoader,
    onOpenUser: (Long) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenIllust: (Long) -> Unit,
) {
    var illust by remember(id) { mutableStateOf<Illust?>(null) }
    var related by remember(id) { mutableStateOf<List<Illust>>(emptyList()) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val detail = graph.client.api.illustDetail(id).illust
                    ?: error("作品不存在")
                val rel = runCatching { graph.client.api.related(id).illusts }.getOrDefault(emptyList())
                detail to rel
            }
        }.onSuccess { (item, rel) ->
            illust = item
            related = rel
        }.onFailure { error = it.message }
        loading = false
    }

    when {
        loading && illust == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        illust == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
        }
        else -> {
            val item = illust!!
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item.pageUrls().forEach { url ->
                        PixivImage(
                            url = url,
                            contentDescription = item.title,
                            contentScale = ContentScale.FillWidth,
                            loader = loader,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Column(
                    Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                ) {
                    Text(item.title ?: "#${item.id}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val user = item.user
                        if (user != null) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable { onOpenUser(user.id) },
                            ) {
                                PixivImage(user.avatar(), user.name, loader = loader)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.clickable { onOpenUser(user.id) }) {
                                Text(user.name ?: "user ${user.id}", style = MaterialTheme.typography.titleMedium)
                                Text("@${user.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        if (item.isBookmarked) graph.client.api.removeBookmark(item.id)
                                        else graph.client.api.addBookmark(item.id)
                                    }
                                    illust = item.copy(is_bookmarked = !item.isBookmarked)
                                }
                            }
                        }) {
                            Icon(
                                if (item.isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "收藏",
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { downloadIllust(graph, item) }
                        }) {
                            Icon(Icons.Outlined.Download, contentDescription = "下载")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${item.total_bookmarks ?: 0} 收藏 · ${item.total_view ?: 0} 浏览 · ${item.create_date.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!item.caption.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(item.caption!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!item.tags.isNullOrEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.tags.orEmpty().forEach { tag ->
                                AssistChip(
                                    onClick = { tag.name?.let(onOpenTag) },
                                    label = { Text("#${tag.display()}") },
                                )
                            }
                        }
                    }
                    if (related.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Text("相关作品", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            related.take(12).forEach { rel ->
                                Box(
                                    Modifier
                                        .size(96.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                        .clickable { onOpenIllust(rel.id) },
                                ) {
                                    PixivImage(
                                        rel.previewUrl(),
                                        rel.title,
                                        loader = loader,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun downloadIllust(graph: AppGraph, illust: Illust) {
    val pictures = Path.of(System.getProperty("user.home"), "Pictures", "PixShaft")
    withContext(Dispatchers.IO) {
        Files.createDirectories(pictures)
        illust.pageUrls().forEachIndexed { index, url ->
            val ext = url.substringAfterLast('.', "jpg").substringBefore('?').ifBlank { "jpg" }
            val name = if (illust.page_count > 1) "${illust.id}_p$index.$ext" else "${illust.id}.$ext"
            val target = pictures.resolve(name)
            val request = Request.Builder().url(url).build()
            graph.imageHttp.newCall(request).execute().use { response ->
                val body = response.body ?: return@use
                Files.write(target, body.bytes())
            }
        }
        runCatching { Desktop.getDesktop().open(pictures.toFile()) }
    }
}
