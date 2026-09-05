package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AccountStore
import ceui.pixshaft.desktop.AiTools
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.BatchSelection
import ceui.pixshaft.desktop.FeatureColumn
import ceui.pixshaft.desktop.ChatPollScope
import ceui.pixshaft.desktop.ChatWsClient
import ceui.pixshaft.desktop.LibraryScanner
import ceui.pixshaft.desktop.UgoiraLoader
import ceui.pixshaft.desktop.WinPaths
import ceui.pixshaft.desktop.downloadUrls
import ceui.pixshaft.desktop.resolvedIllustDir
import ceui.pixshaft.shared.model.ChatHistoryItem
import ceui.pixshaft.shared.model.ComicWork
import ceui.pixshaft.shared.model.FanboxPost
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FanboxHomeScreen(graph: AppGraph, loader: ImageLoader, onOpen: (String) -> Unit) {
    graph.client.fanboxCookie = graph.settings.current.fanboxCookie
    var posts by remember { mutableStateOf<List<FanboxPost>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { graph.client.fanboxApi.postListHome() } }
            .onSuccess { posts = it.body?.items.orEmpty() }
            .onFailure { error = it.userMessage() + "\n未登录 FANBOX 时请在设置里粘贴 FANBOXSESSID，或浏览器打开 fanbox.cc" }
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row {
                    TextButton(onClick = { runCatching { Desktop.getDesktop().browse(URI("https://www.fanbox.cc/")) } }) {
                        Text("在浏览器登录 FANBOX")
                    }
                }
            }
            items(posts, key = { it.id.orEmpty() }) { post ->
                Column(Modifier.fillMaxWidth().clickable { post.id?.let(onOpen) }.padding(8.dp)) {
                    PixivImage(
                        url = post.cover?.url ?: post.coverImageUrl,
                        contentDescription = post.title,
                        loader = loader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Text(post.title ?: post.id.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${post.user?.name.orEmpty()} · ¥${post.feeRequired ?: 0}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (!error.isNullOrBlank() && posts.isEmpty()) {
            Text(error!!, modifier = Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun FanboxPostScreen(graph: AppGraph, postId: String) {
    var post by remember(postId) { mutableStateOf<FanboxPost?>(null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    LaunchedEffect(postId) {
        graph.client.fanboxCookie = graph.settings.current.fanboxCookie
        runCatching { withContext(Dispatchers.IO) { graph.client.fanboxApi.postGet(postId) } }
            .onSuccess { post = it.body?.post }
            .onFailure { error = it.userMessage() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(post?.title ?: "FANBOX #$postId", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(post?.excerpt ?: error ?: "正文被 Cloudflare 挡在 post.info，桌面用 post.get 元数据。完整正文请打开网页。")
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val creator = post?.creatorId.orEmpty()
            val url = if (creator.isBlank()) "https://www.fanbox.cc/"
            else "https://www.fanbox.cc/@$creator/posts/$postId"
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }) { Text("在浏览器打开") }
    }
}

@Composable
fun ComicHomeScreen(graph: AppGraph, loader: ImageLoader) {
    var works by remember { mutableStateOf<List<ComicWork>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { graph.client.comicApi.getComicTop() } }
            .onSuccess { works = it.data?.recent_updated_official_works.orEmpty() }
            .onFailure { error = it.userMessage() }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("pixiv COMIC", style = MaterialTheme.typography.headlineSmall) }
        if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
        items(works, key = { it.id }) { work ->
            Row(Modifier.fillMaxWidth().clickable {
                runCatching { Desktop.getDesktop().browse(URI("https://comic.pixiv.net/works/${work.id}")) }
            }.padding(8.dp)) {
                PixivImage(
                    url = work.thumbnail_image_url ?: work.main_image_url,
                    contentDescription = work.title,
                    loader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(72.dp).height(96.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(work.title ?: "#${work.id}", style = MaterialTheme.typography.titleMedium)
                    Text(work.author.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text("${work.stories_count} 话", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 漫画阅读器：原图加载失败可切中等清晰度；适应宽度/整页、日漫右开、深色背景；
 * 支持左右方向键翻页与点击图片左右半区翻页。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MangaReaderScreen(graph: AppGraph, id: Long, loader: ImageLoader) {
    var detail by remember(id) { mutableStateOf<Illust?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var index by remember(id) { mutableStateOf(0) }
    var quality by remember(id) { mutableStateOf(0) } // 0 原图 1 中等
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val s = graph.settings.current

    LaunchedEffect(id) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { graph.client.api.illustDetail(id).illust } }
            .onSuccess { d ->
                detail = d
                if (d == null || d.pageUrls().isEmpty()) error = "没有可读的页面"
            }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    val originals = detail?.pageUrls().orEmpty()
    val mediums = detail?.viewUrls().orEmpty()
    val pages = if (quality == 0 && originals.isNotEmpty()) originals else mediums.ifEmpty { originals }
    val total = pages.size
    val rtl = s.readerRtl

    fun turn(delta: Int) {
        if (total == 0) return
        val next = (index + delta * (if (rtl) -1 else 1)).coerceIn(0, total - 1)
        index = next
    }

    val bgColor = if (s.readerDarkBg) Color(0xFF0E0E12) else MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> { turn(if (rtl) 1 else -1); true }
                    androidx.compose.ui.input.key.Key.DirectionRight -> { turn(if (rtl) -1 else 1); true }
                    else -> false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    detail?.title ?: "漫画阅读器",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (s.readerDarkBg) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (total > 0) "${index + 1} / $total" else error ?: "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.readerDarkBg) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { quality = if (quality == 0) 1 else 0 }) {
                Text(if (quality == 0) "原图" else "中等", color = if (s.readerDarkBg) Color.White else MaterialTheme.colorScheme.onSurface)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "阅读设置",
                        tint = if (s.readerDarkBg) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (s.readerFit == 0) "适应：宽度 ✓" else "适应：宽度") },
                        onClick = { graph.settings.update { it.copy(readerFit = 0) }; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (s.readerFit == 1) "适应：整页 ✓" else "适应：整页") },
                        onClick = { graph.settings.update { it.copy(readerFit = 1) }; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (s.readerRtl) "方向：日漫右开 ✓" else "方向：从左到右") },
                        onClick = { graph.settings.update { it.copy(readerRtl = !s.readerRtl) }; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (s.readerDarkBg) "背景：深色 ✓" else "背景：浅色") },
                        onClick = { graph.settings.update { it.copy(readerDarkBg = !s.readerDarkBg) }; menuOpen = false },
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(pages, rtl) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                                val w = size.width
                                val x = event.changes.firstOrNull()?.position?.x ?: return@awaitPointerEventScope
                                turn(if (x < w / 2) -1 else 1)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            val url = pages.getOrNull(index)
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                url != null -> LoadableImage(
                    url = url,
                    contentDescription = "第 ${index + 1} 页",
                    contentScale = if (s.readerFit == 0) ContentScale.FillWidth else ContentScale.Fit,
                    loader = loader,
                    modifier = Modifier.fillMaxSize(),
                )
                !error.isNullOrBlank() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        scope.launch {
                            error = null
                            loading = true
                            runCatching { withContext(Dispatchers.IO) { graph.client.api.illustDetail(id).illust } }
                                .onSuccess { detail = it; if (it == null) error = "没有可读的页面" }
                                .onFailure { error = it.userMessage() }
                            loading = false
                        }
                    }) { Text("重试") }
                }
            }
            if (total > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    Text(
                        "${index + 1} / $total",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { turn(-1) },
                enabled = if (rtl) index < total - 1 else index > 0,
            ) { Text(if (rtl) "下一页" else "上一页") }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = { turn(1) },
                enabled = if (rtl) index > 0 else index < total - 1,
            ) { Text(if (rtl) "上一页" else "下一页") }
        }
        androidx.compose.material3.SnackbarHost(snackbar)
    }
}

@Composable
fun UgoiraPlayer(graph: AppGraph, illust: Illust, loader: ImageLoader) {
    var frame by remember(illust.id) { mutableStateOf<Path?>(null) }
    var error by remember(illust.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(illust.id, graph.settings.current.autoPlayUgoira) {
        if (!graph.settings.current.autoPlayUgoira) return@LaunchedEffect
        runCatching { UgoiraLoader.load(graph, illust.id) }
            .onSuccess { clip ->
                if (clip.frames.isEmpty()) return@onSuccess
                var i = 0
                while (true) {
                    frame = clip.frames[i % clip.frames.size]
                    delay(clip.delays.getOrElse(i % clip.delays.size) { 120 }.toLong())
                    i++
                }
            }
            .onFailure { error = it.userMessage() }
    }
    Column {
        val path = frame
        if (path != null) {
            PixivImage(path.toUri().toString(), illust.title, loader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(420.dp))
        } else {
            PixivImage(illust.previewUrl(), illust.title, loader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(280.dp))
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else Text("正在准备动图…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 聊天室：聊天气泡 + 自动刷新 + WebSocket 收发。
 * 发送依赖 SHAFT_EVENTS_HMAC（与官方包一致）；未配置密钥时只读浏览。
 */
@Composable
fun ChatScreen(graph: AppGraph) {
    val selfUid = graph.sessionStore.user?.id ?: 0L
    val messages = remember { mutableStateListOf<ChatHistoryItem>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("未连接") }
    var online by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

    fun merge(item: ChatHistoryItem) {
        val key = item.client_msg_id ?: return
        messages.removeAll { it.client_msg_id == key }
        messages.add(item)
        messages.sortBy { it.ts }
    }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { graph.client.chatApi.history(limit = 100) } }
            .onSuccess { resp ->
                messages.clear()
                messages.addAll(resp.items)
                error = null
            }
            .onFailure { error = it.userMessage() }
        loading = false
        while (true) {
            delay(15_000)
            runCatching { withContext(Dispatchers.IO) { graph.client.chatApi.history(limit = 100) } }
                .onSuccess { resp ->
                    val known = messages.mapNotNull { it.client_msg_id }.toHashSet()
                    resp.items.forEach { if (it.client_msg_id !in known) messages.add(it) }
                    messages.sortBy { it.ts }
                }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { graph.client.chatApi.stats("global") } }
            .onSuccess { online = it.online }
    }
    val ws = remember(selfUid) {
        ChatWsClient(
            uid = selfUid,
            onFrame = { frame ->
                val item = ChatHistoryItem(
                    id = -1L,
                    uid = frame.get("uid")?.asLong ?: 0L,
                    client_msg_id = frame.get("client_msg_id")?.asString,
                    display_name = frame.get("display_name")?.asString,
                    text = frame.get("text")?.asString,
                    illust_id = frame.get("illust_id")?.asLong,
                    ts = frame.get("ts")?.asLong ?: System.currentTimeMillis(),
                )
                merge(item)
            },
            onState = { status = it },
        )
    }
    DisposableEffect(selfUid) {
        ws.connect()
        onDispose { ws.close() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("聊天室 · global", style = MaterialTheme.typography.headlineSmall)
                Text(
                    buildString {
                        append(when {
                            status == "connected" -> "已连接"
                            status.startsWith("err:") -> "发送被拒：${status.removePrefix("err:")}"
                            status.startsWith("failed:") -> "连接失败"
                            status.startsWith("closed:") -> "连接关闭"
                            status == "connecting" -> "连接中…"
                            else -> status
                        })
                        online?.let { append(" · $it 人在线") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { ChatPollScope.launch {
                runCatching { withContext(Dispatchers.IO) { graph.client.chatApi.history(limit = 100) } }
                    .onSuccess { resp ->
                        messages.clear()
                        messages.addAll(resp.items)
                    }
            } }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && messages.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !error.isNullOrBlank() && messages.isEmpty() -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                messages.isEmpty() -> Text(
                    "还没有消息",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.client_msg_id ?: "server:${m.id}" }) { _, msg ->
                    ChatBubble(msg, mine = msg.uid == selfUid, timeFmt)
                }
            }
        }
        if (!lastError.isNullOrBlank()) {
            Text(
                lastError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 2048) input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (status == "connected") "说点什么…" else "只读模式（需要 SHAFT_EVENTS_HMAC 才能发送）") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
            FilledTonalButton(
                onClick = {
                    if (input.isBlank()) return@FilledTonalButton
                    val text = input
                    val sentId = ws.sendGlobal(text)
                    if (sentId == null) {
                        lastError = "发送失败：WebSocket 未连接"
                    } else {
                        input = ""
                        lastError = null
                        merge(
                            ChatHistoryItem(
                                id = -1L,
                                uid = selfUid,
                                client_msg_id = sentId,
                                display_name = graph.sessionStore.user?.name,
                                text = text,
                                illust_id = null,
                                ts = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("发送")
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatHistoryItem, mine: Boolean, timeFmt: DateTimeFormatter) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (mine) 14.dp else 3.dp,
                bottomEnd = if (mine) 3.dp else 14.dp,
            ),
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine) {
                    Text(
                        msg.display_name ?: "uid ${msg.uid}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(msg.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    timeFmt.format(Instant.ofEpochMilli(msg.ts).atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** 本地屏蔽 / 过滤规则总览（对应源 App 的屏蔽记录页）。 */
@Composable
fun MutedScreen(graph: AppGraph) {
    val store = graph.settings
    val s = store.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("屏蔽与过滤", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "本地过滤规则，立即对瀑布流与搜索生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        MuteRule("过滤 R-18 内容", "瀑布流不显示 R-18 作品", s.r18FilterDefaultEnable) { value ->
            store.update { it.copy(r18FilterDefaultEnable = value) }
        }
        MuteRule("屏蔽 AI 生成作品", "隐藏标注为 AI 的作品", s.deleteAIIllust) { value ->
            store.update { it.copy(deleteAIIllust = value) }
        }
        if (s.deleteAIIllust) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AI 屏蔽强度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                listOf(0 to "完全不显示", 1 to "仅标记").forEach { (value, label) ->
                    FilterChipCompact(selected = s.aiBlockStrength == value, label = label) {
                        store.update { it.copy(aiBlockStrength = value) }
                    }
                }
            }
        }
        MuteRule("排行榜过滤已收藏", "日 / 周 / 月榜不出现已收藏", s.filterRankBookmarked) { value ->
            store.update { it.copy(filterRankBookmarked = value) }
        }
        MuteRule("搜索过滤已收藏", "搜索结果不出现已收藏", s.deleteStarIllust) { value ->
            store.update { it.copy(deleteStarIllust = value) }
        }
        MuteRule("过滤垃圾评论", "隐藏带链接 / 广告特征的评论", s.filterComment) { value ->
            store.update { it.copy(filterComment = value) }
        }
    }
}

@Composable
private fun MuteRule(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun FilterChipCompact(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** 本地小说：扫描小说保存目录里的 txt，双击用系统默认程序打开。 */
@Composable
fun LocalNovelsScreen(graph: AppGraph) {
    val dir = graph.settings.current.novelPath.ifBlank { WinPaths.defaultNovel() }
    var files by remember { mutableStateOf<List<Path>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            files = runCatching {
                Path.of(dir).takeIf { Files.isDirectory(it) }?.let { d ->
                    Files.list(d).use { stream ->
                        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".txt", ignoreCase = true) }
                            .sorted()
                            .toList()
                    }
                }
            }.getOrNull().orEmpty()
            loaded = true
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("本地小说", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            dir,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (loaded && files.isEmpty()) {
            Text(
                "目录里还没有 .txt 小说。先在设置里指定小说保存位置，或把 txt 放进去。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(files, key = { _, f -> f.toString() }) { _, file ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        runCatching { Desktop.getDesktop().open(file.toFile()) }
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.fileName.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Files.size(file) / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("打开", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun ReverseSearchScreen() {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("选择本地图片后打开 SauceNAO / ascii2d（与源项目一样走真浏览器过 Cloudflare）") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("以图搜图", style = MaterialTheme.typography.headlineSmall)
        Text(log, style = MaterialTheme.typography.bodySmall)
        Row {
            Button(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { ceui.pixshaft.desktop.pickImageFile() }
                    if (file == null) log = "未选择文件"
                    else {
                        log = "已选 ${file.fileName}，打开 SauceNAO。在网页里上传这张图。"
                        runCatching { Desktop.getDesktop().browse(URI("https://saucenao.com/")) }
                        runCatching { Desktop.getDesktop().open(file.toFile()) }
                    }
                }
            }) { Text("SauceNAO") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                runCatching { Desktop.getDesktop().browse(URI("https://ascii2d.net/")) }
            }) { Text("ascii2d") }
        }
    }
}

@Composable
fun LibraryScreen(graph: AppGraph, onOpen: (Long) -> Unit) {
    var items by remember { mutableStateOf(emptyList<ceui.pixshaft.desktop.LibraryItem>()) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { LibraryScanner.scan(graph.settings.current) } }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text("本地书库（扫描下载目录）", style = MaterialTheme.typography.headlineSmall) }
        items(items, key = { it.path.toString() }) { row ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(row.id) }.padding(8.dp)) {
                Text("#${row.id}  ${row.name}")
                Text(row.path.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) item { Text("还没有扫描到已下载文件。先下载作品或检查设置里的保存位置。") }
    }
}

/** 下载管理：筛选分组、进度条、暂停/继续、单任务取消/重试/移除、批量重试、打开文件夹。 */
@Composable
fun DownloadQueueScreen(graph: AppGraph) {
    val queue = graph.queue
    val jobs = queue.jobs
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    val filtered = when (filter) {
        "running" -> jobs.filter { it.status == "running" }
        "pending" -> jobs.filter { it.status == "pending" }
        "done" -> jobs.filter { it.status == "done" }
        "error" -> jobs.filter { it.status == "error" || it.status == "canceled" }
        else -> jobs.toList()
    }
    val running = jobs.count { it.status == "running" }
    val pending = jobs.count { it.status == "pending" }
    val failed = jobs.count { it.status == "error" || it.status == "canceled" }
    val done = jobs.count { it.status == "done" }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("下载管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "$running 进行中 · $pending 等待 · $done 完成 · $failed 失败" + if (queue.paused) " · 已暂停" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { queue.pause(!queue.paused) }, enabled = jobs.isNotEmpty()) {
                Icon(
                    if (queue.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (queue.paused) "继续" else "暂停",
                )
            }
            TextButton(onClick = { queue.retryAllFailed() }, enabled = failed > 0) { Text("全部重试") }
            TextButton(onClick = { queue.clearFinished() }, enabled = done > 0 || failed > 0) { Text("清除已完成") }
        }
        HorizontalWheelRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            listOf(
                "all" to "全部(${jobs.size})",
                "running" to "进行中($running)",
                "pending" to "等待($pending)",
                "done" to "已完成($done)",
                "error" to "失败($failed)",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { job ->
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, tint) = when (job.status) {
                                "running" -> Icons.Outlined.Download to MaterialTheme.colorScheme.primary
                                "done" -> Icons.Outlined.CheckCircle to Color(0xFF2BB673)
                                "error" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                                "canceled" -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
                                else -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(icon, contentDescription = job.status, tint = tint)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${job.title}  (#${job.illustId})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val detail = when (job.status) {
                                    "pending" -> "等待中" + if (queue.paused) "（已暂停）" else ""
                                    "running" -> "下载中… ${job.finished}/${job.total}"
                                    "done" -> "已完成 · ${job.total} 张"
                                    "canceled" -> "已取消"
                                    else -> "失败：${job.error.orEmpty()}"
                                }
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (job.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (job.status == "running" || job.status == "pending") {
                                TextButton(onClick = { queue.cancel(job) }) { Text("取消") }
                            }
                            if (job.status == "done") {
                                TextButton(onClick = {
                                    val dir = graph.settings.current.resolvedIllustDir(
                                        Illust(id = job.illustId, title = job.title, page_count = job.urls.size),
                                    )
                                    runCatching { Desktop.getDesktop().open(dir.toFile()) }
                                }) { Text("打开文件夹") }
                            }
                            if (job.status == "error" || job.status == "canceled") {
                                TextButton(onClick = { queue.retry(job) }) { Text("重试") }
                            }
                            IconButton(onClick = { queue.remove(job) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "移除",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (job.status == "running" && job.total > 0) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { job.finished.toFloat() / job.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (jobs.isEmpty()) Text("队列空。作品页点下载会进入队列。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AiLabScreen() {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("端侧 AI 调用本机 ncnn / tesseract。把 Windows 可执行文件放到 %APPDATA%\\PixShaft\\tools") }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("端侧 AI", style = MaterialTheme.typography.headlineSmall)
        Text(log, style = MaterialTheme.typography.bodySmall)
        AiTools.tools.forEach { tool ->
            val found = AiTools.find(tool)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tool.title)
                    Text(if (found != null) found.toString() else "未安装 · ${tool.hint}", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val img = AiTools.pickImage() ?: return@launch
                        log = "运行 ${tool.title}…"
                        log = AiTools.run(tool, img)
                    }
                }, enabled = found != null) { Text("运行") }
            }
        }
    }
}

@Composable
fun AccountsScreen(graph: AppGraph, onSwitched: () -> Unit) {
    val store = graph.accounts
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("多账号", style = MaterialTheme.typography.headlineSmall)
        Text("当前登录会写入账号列表。点切换会替换本机会话。", style = MaterialTheme.typography.bodySmall)
        store.accounts.forEach { acc ->
            val uid = acc.user?.id ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(acc.user?.name ?: "user $uid")
                    Text("@${acc.user?.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    graph.sessionStore.save(acc)
                    onSwitched()
                }) { Text("切换") }
                TextButton(onClick = { store.remove(uid) }) { Text("删除") }
            }
        }
        if (store.accounts.isEmpty()) Text("还没有保存的账号。登录后会自动加入。")
    }
}

/** 探索广场：shaft-plaza-api 的社区帖子流（文本 + 插画/小说/用户引用，可点赞）。 */
@Composable
fun PlazaScreen(
    graph: AppGraph,
    loader: ImageLoader,
    onOpenIllust: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
    onOpenNovel: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val posts = remember { mutableStateListOf<ceui.pixshaft.shared.model.PlazaPost>() }
    var nextBefore by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var likeError by remember { mutableStateOf<String?>(null) }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm") }

    fun load(reset: Boolean) {
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) { graph.client.plazaApi.feed(before = if (reset) null else nextBefore) }
            }.onSuccess { resp ->
                if (reset) posts.clear()
                posts.addAll(resp.items)
                nextBefore = resp.next_before
                error = null
            }.onFailure { error = it.userMessage() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("探索广场", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Shaft 社区动态 · 分享作品与发现",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { load(true) }) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") }
        }
        if (!likeError.isNullOrBlank()) {
            Text(
                likeError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(posts, key = { it.id }) { post ->
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                post.display_name ?: "uid ${post.uid}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                timeFmt.format(Instant.ofEpochMilli(post.ts).atZone(ZoneId.systemDefault())),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (post.text.isNotBlank()) {
                            Text(post.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (post.refs.illust.isNotEmpty()) {
                            Row(
                                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                post.refs.illust.forEach { ref ->
                                    Column(
                                        Modifier
                                            .width(96.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { ref.meta?.target_id?.let(onOpenIllust) },
                                    ) {
                                        PixivImage(
                                            url = ref.meta?.thumb_url,
                                            contentDescription = ref.meta?.title,
                                            loader = loader,
                                            modifier = Modifier.fillMaxWidth().height(96.dp),
                                        )
                                        Text(
                                            ref.meta?.title.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        post.refs.novel.forEach { ref ->
                            Text(
                                "📖 ${ref.meta?.title.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp).clickable { ref.meta?.target_id?.let(onOpenNovel) },
                            )
                        }
                        post.refs.user.forEach { ref ->
                            Text(
                                "@${ref.meta?.name.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp).clickable { ref.meta?.target_id?.let(onOpenUser) },
                            )
                        }
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "♥ ${post.like_count}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (post.liked_by_viewer == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    if (post.liked_by_viewer == true) graph.client.plazaApi.unlike(post.id)
                                                    else graph.client.plazaApi.like(post.id)
                                                }
                                            }.onSuccess { resp ->
                                                val i = posts.indexOfFirst { it.id == post.id }
                                                if (i >= 0) {
                                                    posts[i] = posts[i].copy(
                                                        like_count = resp.like_count,
                                                        liked_by_viewer = resp.added ?: (resp.removed == false),
                                                    )
                                                }
                                                likeError = null
                                            }.onFailure {
                                                likeError = "点赞失败：${it.message.orEmpty()}（可能需要 SHAFT_EVENTS_HMAC）"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                            Text(
                                "💬 ${post.comment_count}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (nextBefore != null && posts.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { load(false) }) { Text("加载更多") }
                    }
                }
            }
            if (loading && posts.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            if (!error.isNullOrBlank() && posts.isEmpty()) {
                item { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}

/** 发现：从浏览历史里随机重新挖掘看过的作品。 */
@Composable
fun DiscoveryScreen(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
) {
    var seed by remember { mutableStateOf(0) }
    var items by remember(seed) { mutableStateOf(graph.history.list().shuffled(java.util.Random(seed.toLong())).take(40)) }
    val batch = remember { BatchSelection() }
    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = batch,
            onRefresh = { seed += 1 },
            leading = {
                Column {
                    Text("发现", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "从你的浏览历史里随机重新挖掘——换个角度再看一遍",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        IllustWaterfall(
            illusts = items,
            loading = false,
            error = null,
            loader = loader,
            onOpen = onOpen,
            batch = batch,
            onBatchDownload = { list ->
                list.forEach { graph.queue.enqueue(it) }
                batch.reset()
            },
            onDownload = { graph.queue.enqueue(it) },
            onAddFeature = { illust ->
                val author = illust.user ?: return@IllustWaterfall
                graph.features.add(FeatureColumn.author(author.id, author.name.orEmpty()))
            },
            header = {
                if (items.isEmpty()) {
                    Text(
                        "浏览历史还是空的——先去看几幅作品吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            },
        )
        }
    }
}
