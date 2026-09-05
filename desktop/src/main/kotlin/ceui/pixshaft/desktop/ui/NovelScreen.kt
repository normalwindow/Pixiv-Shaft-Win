package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.model.Novel
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NovelListScreen(
    graph: AppGraph,
    loader: ImageLoader,
    load: (suspend () -> ceui.pixshaft.shared.model.NovelResponse)? = null,
    onOpen: (Novel) -> Unit,
) {
    var items by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                (load ?: { graph.client.api.recommendedNovels() })()
            }
        }
            .onSuccess { items = it.novels }
            .onFailure { error = it.userMessage() }
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { novel ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(novel) },
                ) {
                    Row(Modifier.padding(10.dp)) {
                        PixivImage(
                            url = novel.cover(),
                            contentDescription = novel.title,
                            loader = loader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(72.dp).height(96.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(novel.title ?: "#${novel.id}", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(novel.user?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${novel.text_length} 字 · ${novel.total_bookmarks ?: 0} 收藏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (graph.settings.current.showNovelCardTags && !novel.tags.isNullOrEmpty()) {
                                Text(
                                    novel.tags.orEmpty().take(6).joinToString("  ") {
                                        "#${if (graph.settings.current.showNovelCardTagTranslations) it.display() else it.name.orEmpty()}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (loading && items.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (!error.isNullOrBlank() && items.isEmpty()) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}

@Composable
fun NovelReaderScreen(graph: AppGraph, id: Long) {
    var title by remember(id) { mutableStateOf("") }
    var body by remember(id) { mutableStateOf("") }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val detail = graph.client.api.novelDetail(id).novel
                val text = graph.client.api.novelText(id).body()
                (detail?.title ?: "#$id") to text
            }
        }.onSuccess { (t, text) ->
            title = t
            body = text
        }.onFailure { error = it.userMessage() }
        loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        when {
            loading -> CircularProgressIndicator()
            !error.isNullOrBlank() -> Text(error!!, color = MaterialTheme.colorScheme.error)
            else -> Text(body.ifBlank { "（无正文）" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
