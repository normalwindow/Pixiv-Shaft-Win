package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import ceui.pixshaft.desktop.FeatureColumn
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
    var authorWorks by remember(id) { mutableStateOf<List<Illust>>(emptyList()) }
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
                val first = runCatching { graph.client.api.related(id) }.getOrNull()
                val more = first?.next_url?.let { url ->
                    runCatching { graph.client.api.nextIllusts(url) }.getOrNull()
                }
                val rel = ((first?.illusts ?: emptyList()) + (more?.illusts ?: emptyList()))
                    .distinctBy { it.id }
                detail to rel
            }
        }.onSuccess { (item, rel) ->
            illust = item
            related = rel
            if (graph.settings.current.saveViewHistory) graph.history.record(item)
            // 作者其它作品（与相关作品同样的横滑样式）
            item.user?.id?.let { uid ->
                runCatching {
                    withContext(Dispatchers.IO) { graph.client.api.userIllusts(uid, "illust").illusts }
                }.onSuccess { list ->
                    authorWorks = list.uniqueIllusts().filterNot { it.id == item.id }
                }
            }
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
            var imageCollapsed by remember(id) { mutableStateOf(false) }
            var menuOpen by remember { mutableStateOf(false) }

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

            fun addAuthorColumn() {
                val uid = item.user?.id ?: return
                val name = item.user?.name ?: "uid $uid"
                graph.features.add(FeatureColumn.author(uid, name))
                scope.launch { snackbar.showSnackbar("已把「$name 的作品」收入精华列") }
            }

            fun openPreview() {
                preview.open(urls, page.coerceIn(0, urls.lastIndex.coerceAtLeast(0)), item.title)
            }

            Box(Modifier.fillMaxSize()) {
                if (graph.settings.current.detailStyle == 1) {
                    PhoneDetailLayout(
                        item = item,
                        graph = graph,
                        loader = loader,
                        page = page,
                        onPage = { page = it },
                        onPreview = ::openPreview,
                        imageCollapsed = imageCollapsed,
                        onToggleImageCollapsed = { imageCollapsed = !imageCollapsed },
                        related = related,
                        authorWorks = authorWorks,
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
                        menuOpen = menuOpen,
                        onMenuOpen = { menuOpen = true },
                        onMenuDismiss = { menuOpen = false },
                        menuActions = DetailMenuActions(
                            onToggleBookmark = ::toggleBookmark,
                            onDownload = ::download,
                            onAddAuthorColumn = ::addAuthorColumn,
                            onCopyIllustId = { copySnackbar(snackbar, scope, item.id.toString()) },
                            onCopyUserId = { item.user?.id?.let { copySnackbar(snackbar, scope, it.toString()) } },
                            onOpenInBrowser = {
                                runCatching {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.pixiv.net/artworks/${item.id}"))
                                }
                            },
                            onOpenManga = if (item.isManga()) ({ onOpenManga(item.id) }) else null,
                            onExpand = onExpand.takeIf { embedded },
                            onTogglePanel = null,
                            onToggleImageCollapsed = { imageCollapsed = !imageCollapsed },
                        ),
                    )
                } else {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(if (showPanel) 1.15f else 1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AuthorRow(item, loader, onOpenUser, Modifier.weight(1f))
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = "菜单")
                                    }
                                    IllustDetailMenu(
                                        expanded = menuOpen,
                                        onDismiss = { menuOpen = false },
                                        bookmarked = item.isBookmarked,
                                        actions = DetailMenuActions(
                                            onToggleBookmark = ::toggleBookmark,
                                            onDownload = ::download,
                                            onAddAuthorColumn = ::addAuthorColumn,
                                            onCopyIllustId = { copySnackbar(snackbar, scope, item.id.toString()) },
                                            onCopyUserId = { item.user?.id?.let { copySnackbar(snackbar, scope, it.toString()) } },
                                            onOpenInBrowser = {
                                                runCatching {
                                                    java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.pixiv.net/artworks/${item.id}"))
                                                }
                                            },
                                            onOpenManga = if (item.isManga()) ({ onOpenManga(item.id) }) else null,
                                            onExpand = onExpand.takeIf { embedded },
                                            onTogglePanel = { showPanel = !showPanel },
                                        ),
                                        showPanel = showPanel,
                                    )
                                }
                            }
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
                            CopyableMeta(item, snackbar, scope)
                            RelatedStrip(
                                related = related,
                                illustId = item.id,
                                loader = loader,
                                onOpenIllust = onOpenIllust,
                                onOpenRelated = onOpenRelated,
                            )
                            item.user?.id?.let { uid ->
                                RelatedStrip(
                                    related = authorWorks,
                                    illustId = uid,
                                    loader = loader,
                                    onOpenIllust = onOpenIllust,
                                    onOpenRelated = onOpenUser,
                                    title = "作者作品",
                                )
                            }
                            CommentsSection(graph, id, loader, Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 详情菜单动作集合。 */
data class DetailMenuActions(
    val onToggleBookmark: () -> Unit,
    val onDownload: () -> Unit,
    val onAddAuthorColumn: () -> Unit,
    val onCopyIllustId: () -> Unit,
    val onCopyUserId: () -> Unit,
    val onOpenInBrowser: () -> Unit,
    val onOpenManga: (() -> Unit)? = null,
    val onExpand: (() -> Unit)? = null,
    val onTogglePanel: (() -> Unit)? = null,
    val onToggleImageCollapsed: (() -> Unit)? = null,
)

@Composable
private fun IllustDetailMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    bookmarked: Boolean,
    actions: DetailMenuActions,
    showPanel: Boolean? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (bookmarked) "取消收藏" else "❤ 收藏") },
            onClick = { actions.onToggleBookmark(); onDismiss() },
        )
        DropdownMenuItem(text = { Text("下载") }, onClick = { actions.onDownload; onDismiss() })
        DropdownMenuItem(text = { Text("把作者作品收入精华列") }, onClick = { actions.onAddAuthorColumn(); onDismiss() })
        actions.onOpenManga?.let {
            DropdownMenuItem(text = { Text("漫画阅读器") }, onClick = { it(); onDismiss() })
        }
        actions.onExpand?.let {
            DropdownMenuItem(text = { Text("全屏查看") }, onClick = { it(); onDismiss() })
        }
        actions.onTogglePanel?.let {
            DropdownMenuItem(
                text = { Text(if (showPanel == true) "收起资料" else "显示资料") },
                onClick = { it(); onDismiss() },
            )
        }
        actions.onToggleImageCollapsed?.let {
            DropdownMenuItem(text = { Text("收起 / 展开图片") }, onClick = { it(); onDismiss() })
        }
        DropdownMenuItem(text = { Text("复制作品 ID") }, onClick = { actions.onCopyIllustId(); onDismiss() })
        DropdownMenuItem(text = { Text("复制作者 ID") }, onClick = { actions.onCopyUserId(); onDismiss() })
        DropdownMenuItem(text = { Text("在浏览器打开") }, onClick = { actions.onOpenInBrowser; onDismiss() })
    }
}

fun copySnackbar(snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, text: String) {
    copyToClipboard(text)
    scope.launch { snackbar.showSnackbar("已复制：$text") }
}

/** 作品 ID / 作者 ID 可点击复制行。 */
@Composable
private fun CopyableMeta(item: Illust, snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "作品 ID ${item.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { copySnackbar(snackbar, scope, item.id.toString()) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.user?.id?.let { uid ->
            Text(
                "作者 UID $uid",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { copySnackbar(snackbar, scope, uid.toString()) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${item.width}×${item.height}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        LoadableImage(
            url = urls.first(),
            contentDescription = item.title,
            contentScale = ContentScale.FillWidth,
            loader = loader,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onPreview() },
        )
        return
    }
    val current = page.coerceIn(0, urls.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            LoadableImage(
                url = urls[current],
                contentDescription = "${item.title} 第 ${current + 1} 页",
                contentScale = ContentScale.FillWidth,
                loader = loader,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPreview() },
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
private fun AuthorRow(item: Illust, loader: ImageLoader, onOpenUser: (Long) -> Unit, modifier: Modifier = Modifier) {
    val user = item.user ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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

/** 手机原样模式：仿手机 App 的单列排版，图在上、信息在下；图片区可整体收起（抽屉开关）。 */
@Composable
private fun PhoneDetailLayout(
    item: Illust,
    graph: AppGraph,
    loader: ImageLoader,
    page: Int,
    onPage: (Int) -> Unit,
    onPreview: () -> Unit,
    imageCollapsed: Boolean,
    onToggleImageCollapsed: () -> Unit,
    related: List<Illust>,
    authorWorks: List<Illust>,
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
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    menuActions: DetailMenuActions,
) {
    val scope = rememberCoroutineScope()
    var following by remember(item.id) { mutableStateOf(item.user?.is_followed == true) }
    val urls = item.viewUrls(original = graph.settings.current.showOriginalPreviewImage)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部条：标题 + 折叠图片 + 菜单
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title ?: "#${item.id}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleImageCollapsed) {
                Icon(
                    if (imageCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = if (imageCollapsed) "展开图片" else "收起图片",
                )
            }
            Box {
                IconButton(onClick = onMenuOpen) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "菜单")
                }
                IllustDetailMenu(
                    expanded = menuOpen,
                    onDismiss = onMenuDismiss,
                    bookmarked = bookmarked,
                    actions = menuActions,
                )
            }
        }
        if (imageCollapsed) {
            // 抽屉收起态：缩略图细条，点一下展开
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggleImageCollapsed() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixivImage(
                    url = urls.getOrNull(page.coerceIn(0, urls.lastIndex.coerceAtLeast(0))),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    loader = loader,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title ?: "#${item.id}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        if (urls.size > 1) "已收起图片（${page.coerceIn(0, urls.lastIndex.coerceAtLeast(0)) + 1}/${urls.size}）· 点击展开"
                        else "已收起图片 · 点击展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            IllustPager(item = item, graph = graph, loader = loader, page = page, onPage = onPage, onPreview = onPreview)
        }
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
        CopyableMeta(item, snackbar, scope)
        RelatedStrip(
            related = related,
            illustId = item.id,
            loader = loader,
            onOpenIllust = onOpenIllust,
            onOpenRelated = onOpenRelated,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        item.user?.id?.let { uid ->
            RelatedStrip(
                related = authorWorks,
                illustId = uid,
                loader = loader,
                onOpenIllust = onOpenIllust,
                onOpenRelated = onOpenUser,
                title = "作者作品",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        CommentsSection(graph, item.id, loader, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun CommentsSection(graph: AppGraph, id: Long, loader: ImageLoader, modifier: Modifier = Modifier) {
    var comments by remember(id) { mutableStateOf<List<Comment>>(emptyList()) }
    var nextUrl by remember(id) { mutableStateOf<String?>(null) }
    var loadingComments by remember(id) { mutableStateOf(true) }
    var loadingMore by remember(id) { mutableStateOf(false) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    var draft by remember(id) { mutableStateOf("") }
    var sending by remember(id) { mutableStateOf(false) }
    var sendError by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        loadingComments = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { graph.client.api.comments(id) }
        }.onSuccess {
            comments = it.comments
            nextUrl = it.next_url
        }.onFailure {
            loadError = it.userMessage()
        }
        loadingComments = false
    }
    val shown = if (graph.settings.current.filterComment) {
        comments.filterNot { looksLikeSpam(it.comment) }
    } else {
        comments
    }
    Column(modifier) {
        Text(
            when {
                loadingComments -> "评论加载中…"
                loadError != null && comments.isEmpty() -> "评论"
                shown.isEmpty() -> "暂无评论"
                else -> "评论 (${shown.size})"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (loadError != null) {
            Text(
                loadError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        loadingComments = true
                        loadError = null
                        runCatching {
                            withContext(Dispatchers.IO) { graph.client.api.comments(id) }
                        }.onSuccess {
                            comments = it.comments
                            nextUrl = it.next_url
                        }.onFailure { loadError = it.userMessage() }
                        loadingComments = false
                    }
                },
            ) { Text("重试") }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; sendError = null },
                modifier = Modifier.weight(1f),
                enabled = !sending,
                singleLine = false,
                maxLines = 4,
                placeholder = { Text("写下评论…") },
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isBlank() || sending) return@FilledTonalButton
                    sending = true
                    sendError = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { graph.client.api.postIllustComment(id, text) }
                        }.onSuccess { resp ->
                            val posted = resp.comment ?: Comment(
                                id = System.currentTimeMillis(),
                                comment = text,
                                date = java.time.OffsetDateTime.now().toString(),
                                user = graph.sessionStore.user,
                            )
                            comments = listOf(posted) + comments
                            draft = ""
                        }.onFailure { sendError = it.userMessage() }
                        sending = false
                    }
                },
                enabled = !sending && draft.isNotBlank(),
            ) { Text(if (sending) "发送中" else "发送") }
        }
        if (sendError != null) {
            Text(
                sendError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        shown.forEach { comment ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                PixivImage(
                    url = comment.user?.avatar(),
                    contentDescription = comment.user?.name,
                    loader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp,
                    ),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                comment.user?.name.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            comment.date?.let { date ->
                                Text(
                                    date.substringBefore('T'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val body = stripHtml(comment.bodyText())
                        if (body.isNotBlank()) {
                            Text(body, style = MaterialTheme.typography.bodySmall)
                        }
                        comment.stamp?.stamp_url?.takeIf { it.isNotBlank() }?.let { stampUrl ->
                            PixivImage(
                                url = stampUrl,
                                contentDescription = "stamp",
                                loader = loader,
                                modifier = Modifier.padding(top = 6.dp).size(48.dp),
                            )
                        }
                    }
                }
            }
        }
        if (!nextUrl.isNullOrBlank()) {
            TextButton(
                onClick = {
                    val url = nextUrl ?: return@TextButton
                    if (loadingMore) return@TextButton
                    loadingMore = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { graph.client.api.nextComments(url) }
                        }.onSuccess {
                            comments = comments + it.comments
                            nextUrl = it.next_url
                        }.onFailure { loadError = it.userMessage() }
                        loadingMore = false
                    }
                },
                enabled = !loadingMore,
            ) { Text(if (loadingMore) "加载中…" else "更多评论") }
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
    title: String = "相关作品",
) {
    if (related.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOpenRelated(illustId) }) { Text("查看全部") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            related.take(30).forEach { rel ->
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
