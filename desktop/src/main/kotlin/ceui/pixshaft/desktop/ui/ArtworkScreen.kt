package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.model.Comment
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtworkScreen(
    graph: AppGraph,
    id: Long,
    loader: ImageLoader,
    snackbar: SnackbarHostState,
    onOpenUser: (Long) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenIllust: (Long) -> Unit,
    onOpenManga: (Long) -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
    embedded: Boolean = false,
    onExpand: (() -> Unit)? = null,
) {
    var illust by remember(id) { mutableStateOf<Illust?>(null) }
    var related by remember(id) { mutableStateOf<List<Illust>>(emptyList()) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val preview = LocalImagePreview.current

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
            if (graph.settings.current.saveViewHistory) graph.history.record(item)
        }.onFailure { error = it.userMessage() }
        loading = false
    }

    when {
        loading && illust == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        illust == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
            }
        }
        else -> {
            val item = illust!!
            val urls = item.viewUrls(original = graph.settings.current.showOriginalPreviewImage)
            var page by remember(id) { mutableStateOf(0) }
            var showPanel by remember(id) {
                mutableStateOf(!graph.settings.current.detailPanelCollapsedByDefault)
            }

            fun toggleBookmark() {
                val target = illust ?: return
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (target.isBookmarked) graph.client.api.removeBookmark(target.id)
                            else graph.client.api.addBookmark(
                                target.id,
                                if (graph.settings.current.privateStar) "private" else "public",
                            )
                        }
                    }.onSuccess {
                        illust = target.copy(is_bookmarked = !target.isBookmarked)
                        if (!target.isBookmarked) {
                            if (graph.settings.current.autoDownloadAfterStar) graph.queue.enqueue(target)
                            if (graph.settings.current.autoFollowAfterStar) {
                                target.user?.id?.let { uid ->
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            graph.client.api.followUser(
                                                uid,
                                                if (graph.settings.current.privateFollow) "private" else "public",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }.onFailure { snackbar.showSnackbar(it.userMessage()) }
                }
            }

            fun download() {
                graph.queue.enqueue(item)
                scope.launch { snackbar.showSnackbar("已加入下载队列") }
            }

            fun openPreview() {
                preview.open(urls, page.coerceIn(0, urls.lastIndex.coerceAtLeast(0)), item.title)
            }

            if (graph.settings.current.detailStyle == 1) {
                PhoneDetailLayout(
                    item = item,
                    graph = graph,
                    loader = loader,
                    page = page,
                    onPage = { page = it },
                    onPreview = ::openPreview,
                    related = related,
                    bookmarked = item.isBookmarked,
                    onToggleBookmark = ::toggleBookmark,
                    onDownload = ::download,
                    onOpenUser = onOpenUser,
                    onOpenTag = onOpenTag,
                    onOpenIllust = onOpenIllust,
                    onOpenRelated = onOpenRelated,
                    onOpenManga = onOpenManga,
                    snackbar = snackbar,
                    embedded = embedded,
                    onExpand = onExpand,
                )
            } else {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(if (showPanel) 1.15f else 1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!showPanel) {
                            AssistChip(onClick = { showPanel = true }, label = { Text("显示资料") })
                        }
                        IllustPager(
                            item = item,
                            graph = graph,
                            loader = loader,
                            page = page,
                            onPage = { page = it },
                            onPreview = ::openPreview,
                        )
                    }
                    if (showPanel) Column(
                        Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                    ) {
                        Text(item.title ?: "#${item.id}", style = MaterialTheme.typography.headlineSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { showPanel = false }, label = { Text("收起资料") })
                            if (embedded && onExpand != null) {
                                AssistChip(onClick = onExpand, label = { Text("全屏查看") })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AuthorRow(item, loader, onOpenUser)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${item.total_bookmarks ?: 0} 收藏 · ${item.total_view ?: 0} 浏览 · ${item.create_date?.substringBefore('T').orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { toggleBookmark() }) {
                                Icon(
                                    if (item.isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (item.isBookmarked) "已收藏" else "收藏")
                            }
                            OutlinedButton(onClick = { download() }) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("下载")
                            }
                            if (item.isManga()) {
                                OutlinedButton(onClick = { onOpenManga(item.id) }) { Text("漫画阅读器") }
                            }
                        }
                        if (!item.caption.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(stripHtml(item.caption!!), style = MaterialTheme.typography.bodyMedium)
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "作品 ID ${item.id} · 作者 UID ${item.user?.id ?: "?"} · ${item.width}×${item.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CommentsSection(graph, id, loader, Modifier.padding(top = 12.dp))
                        Spacer(Modifier.height(8.dp))
                        RelatedStrip(
                            related = related,
                            illustId = item.id,
                            loader = loader,
                            onOpenIllust = onOpenIllust,
                            onOpenRelated = onOpenRelated,
                        )
                    }
                }
            }
        }
    }
}

/** 多页作品：主图 + 页码胶囊 + 左右翻页 + 缩略图条；单页作品直接整宽显示。 */
@Composable
private fun IllustPager(
    item: Illust,
    graph: AppGraph,
    loader: ImageLoader,
    page: Int,
    onPage: (Int) -> Unit,
    onPreview: () -> Unit,
) {
    if (item.isGif()) {
        UgoiraPlayer(graph, item, loader)
        return
    }
    val urls = item.viewUrls(original = graph.settings.current.showOriginalPreviewImage)
    if (urls.isEmpty()) return
    if (urls.size == 1) {
        PixivImage(
            url = urls.first(),
            contentDescription = item.title,
            contentScale = ContentScale.FillWidth,
            loader = loader,
            modifier = Modifier.fillMaxWidth().clickable { onPreview() },
        )
        return
    }
    val current = page.coerceIn(0, urls.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            PixivImage(
                url = urls[current],
                contentDescription = "${item.title} 第 ${current + 1} 页",
                contentScale = ContentScale.FillWidth,
                loader = loader,
                modifier = Modifier.fillMaxWidth().clickable { onPreview() },
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "${current + 1} / ${urls.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (current > 0) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).size(34.dp),
                ) {
                    IconButton(onClick = { onPage(current - 1) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = "上一页",
                            tint = Color.White,
                        )
                    }
                }
            }
            if (current < urls.lastIndex) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).size(34.dp),
                ) {
                    IconButton(onClick = { onPage(current + 1) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "下一页",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            urls.forEachIndexed { i, url ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(56.dp)
                        .then(
                            if (i == current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            else Modifier,
                        )
                        .clickable { onPage(i) },
                ) {
                    PixivImage(
                        url = url,
                        contentDescription = "第 ${i + 1} 页",
                        contentScale = ContentScale.Crop,
                        loader = loader,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorRow(item: Illust, loader: ImageLoader, onOpenUser: (Long) -> Unit) {
    val user = item.user ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { onOpenUser(user.id) }) {
            PixivImage(user.avatar(), user.name, loader = loader)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.clickable { onOpenUser(user.id) }) {
            Text(user.name ?: "user ${user.id}", style = MaterialTheme.typography.titleMedium)
            Text("@${user.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 手机原样模式：仿手机 App 的单列排版，图在上，信息在下。 */
@Composable
private fun PhoneDetailLayout(
    item: Illust,
    graph: AppGraph,
    loader: ImageLoader,
    page: Int,
    onPage: (Int) -> Unit,
    onPreview: () -> Unit,
    related: List<Illust>,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onOpenUser: (Long) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenIllust: (Long) -> Unit,
    onOpenRelated: (Long) -> Unit,
    onOpenManga: (Long) -> Unit,
    snackbar: SnackbarHostState,
    embedded: Boolean,
    onExpand: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var following by remember(item.id) { mutableStateOf(item.user?.is_followed == true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IllustPager(item = item, graph = graph, loader = loader, page = page, onPage = onPage, onPreview = onPreview)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val user = item.user
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { user?.id?.let(onOpenUser) }) {
                PixivImage(user?.avatar(), user?.name, loader = loader)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { user?.id?.let(onOpenUser) }) {
                Text(
                    user?.name ?: "user ${user?.id ?: item.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "@${user?.account.orEmpty()} · ${item.create_date?.substringBefore('T').orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (user != null) {
                if (following) {
                    OutlinedButton(onClick = {
                        following = false
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { graph.client.api.unfollowUser(user.id) } }
                                .onFailure { following = true; snackbar.showSnackbar(it.userMessage()) }
                        }
                    }) { Text("已关注") }
                } else {
                    FilledTonalButton(onClick = {
                        following = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    graph.client.api.followUser(
                                        user.id,
                                        if (graph.settings.current.privateFollow) "private" else "public",
                                    )
                                }
                            }.onFailure { following = false; snackbar.showSnackbar(it.userMessage()) }
                        }
                    }) { Text("+ 关注") }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onToggleBookmark) {
                Icon(
                    if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (bookmarked) "已收藏 ${item.total_bookmarks ?: 0}" else "收藏 ${item.total_bookmarks ?: 0}")
            }
            OutlinedButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("下载")
            }
            if (item.isManga()) {
                OutlinedButton(onClick = { onOpenManga(item.id) }) { Text("漫画阅读器") }
            }
            if (embedded && onExpand != null) {
                OutlinedButton(onClick = onExpand) { Text("全屏查看") }
            }
        }
        Text(
            item.title ?: "#${item.id}",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "${item.total_view ?: 0} 浏览 · ${item.width}×${item.height} · ${if (item.isGif()) "动图" else if (item.isManga()) "漫画" else "插画"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (!item.tags.isNullOrEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.orEmpty().forEach { tag ->
                    AssistChip(
                        onClick = { tag.name?.let(onOpenTag) },
                        label = { Text("#${tag.display()}") },
                    )
                }
            }
        }
        if (!item.caption.isNullOrBlank()) {
            Text(
                stripHtml(item.caption!!),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Text(
            "作品 ID ${item.id} · 作者 UID ${item.user?.id ?: "?"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        CommentsSection(graph, item.id, loader, Modifier.padding(horizontal = 16.dp))
        RelatedStrip(
            related = related,
            illustId = item.id,
            loader = loader,
            onOpenIllust = onOpenIllust,
            onOpenRelated = onOpenRelated,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CommentsSection(graph: AppGraph, id: Long, loader: ImageLoader, modifier: Modifier = Modifier) {
    var comments by remember(id) { mutableStateOf<List<Comment>>(emptyList()) }
    LaunchedEffect(id) {
        runCatching {
            withContext(Dispatchers.IO) { graph.client.api.comments(id).comments }
        }.onSuccess { comments = it }
    }
    val shown = if (graph.settings.current.filterComment) {
        comments.filterNot { looksLikeSpam(it.comment) }
    } else {
        comments
    }
    if (shown.isEmpty()) return
    Column(modifier) {
        Text("评论 (${shown.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        shown.take(20).forEach { comment ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                PixivImage(
                    url = comment.user?.avatar(),
                    contentDescription = comment.user?.name,
                    loader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        comment.user?.name.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(comment.comment.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (comments.size > 20) {
            Text(
                "仅显示前 20 条评论",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RelatedStrip(
    related: List<Illust>,
    illustId: Long,
    loader: ImageLoader,
    onOpenIllust: (Long) -> Unit,
    onOpenRelated: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (related.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("相关作品", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOpenRelated(illustId) }) { Text("查看全部") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            related.take(20).forEach { rel ->
                Column(
                    Modifier.width(124.dp).clickable { onOpenIllust(rel.id) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PixivImage(
                        url = rel.previewUrl(large = LocalDesktopSettings.current.showLargeThumbnailImage),
                        contentDescription = rel.title,
                        contentScale = ContentScale.Crop,
                        loader = loader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Text(
                        rel.title ?: "#${rel.id}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        rel.user?.name.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun looksLikeSpam(text: String?): Boolean {
    val t = text.orEmpty().lowercase()
    if (t.isBlank()) return false
    return t.contains("http") || t.contains("www.") || t.contains("t.me") || t.contains("discord.gg")
}

private fun stripHtml(raw: String): String =
    raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()
