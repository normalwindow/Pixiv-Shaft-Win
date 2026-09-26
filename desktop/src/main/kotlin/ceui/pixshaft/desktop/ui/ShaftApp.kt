package ceui.pixshaft.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.BatchSelection
import ceui.pixshaft.desktop.FeatureColumn
import ceui.pixshaft.desktop.FeedState
import ceui.pixshaft.desktop.shouldHide
import ceui.pixshaft.desktop.SingleInstance
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.model.Novel
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShaftApp(
    graph: AppGraph,
    initialUri: String?,
    windowState: WindowState,
    modifier: Modifier = Modifier.fillMaxSize(),
    onToggleFullscreen: () -> Unit = {},
) {
    val imagePreview = remember { ImagePreviewHost() }
    val feedZoom = remember { mutableFloatStateOf(1f) }
    ShaftTheme(
        themeMode = graph.settings.current.themeMode,
        accentIndex = graph.settings.current.accentColor,
    ) {
        var loggedIn by remember { mutableStateOf(graph.sessionStore.isLoggedIn) }
        var loginError by remember { mutableStateOf<String?>(null) }
        var pendingDest by remember { mutableStateOf(DeepLinks.parse(initialUri)) }
        val loader = rememberImageLoader(graph)

        val loginScope = rememberCoroutineScope()
        DisposableEffect(graph) {
            val listener: (String) -> Unit = { uri ->
                val dest = DeepLinks.parse(uri)
                if (dest != null && graph.sessionStore.isLoggedIn) {
                    pendingDest = dest
                } else if (!graph.offerLoginCallback(uri)) {
                    loginScope.launch {
                        runCatching { graph.completeLoginFromUri(uri) }
                            .onSuccess {
                                loggedIn = true
                                loginError = null
                            }
                            .onFailure { loginError = it.userMessage() }
                    }
                }
            }
            SingleInstance.onUri(listener)
            onDispose { SingleInstance.removeUri(listener) }
        }

        LaunchedEffect(initialUri) {
            if (!initialUri.isNullOrBlank() && !loggedIn && DeepLinks.parse(initialUri) == null) {
                runCatching { graph.completeLoginFromUri(initialUri) }
                    .onSuccess { loggedIn = true }
                    .onFailure { loginError = it.userMessage() }
            }
        }

        CompositionLocalProvider(
            LocalImagePolicy provides ImagePolicy(
                largeThumbnail = graph.settings.current.showLargeThumbnailImage,
                originalDetail = graph.settings.current.showOriginalPreviewImage,
            ),
            LocalDesktopSettings provides graph.settings.current,
            LocalImagePreview provides imagePreview,
            LocalFeedZoom provides feedZoom,
            LocalAppLocale provides graph.settings.current.appLocale,
            LocalDownloadedIds provides graph.downloaded.ids,
        ) {
            Surface(modifier) {
                if (!loggedIn) {
                    LoginScreen(
                        graph = graph,
                        error = loginError,
                        onLoggedIn = { loggedIn = true },
                        onError = { loginError = it },
                    )
                } else {
                    key(graph.sessionGeneration) {
                        LoggedInShell(
                            graph = graph,
                            loader = loader,
                            windowState = windowState,
                            pendingDest = pendingDest,
                            onPendingConsumed = { pendingDest = null },
                            onLogout = {
                                graph.logout()
                                loggedIn = false
                            },
                            onToggleFullscreen = onToggleFullscreen,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoggedInShell(
    graph: AppGraph,
    loader: ImageLoader,
    windowState: WindowState,
    pendingDest: Dest?,
    onPendingConsumed: () -> Unit,
    onLogout: () -> Unit,
    onToggleFullscreen: () -> Unit = {},
) {
    val backStack = remember { mutableStateListOf(startDestOf(graph.settings.current.navigationInitPosition)) }
    val current = backStack.last()
    var searchDraft by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var railHidden by remember { mutableStateOf(false) }
    var paneId by remember { mutableStateOf<Long?>(null) }
    var panesSwapped by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val preview = LocalImagePreview.current
    val fullscreen = windowState.placement == WindowPlacement.Fullscreen
    val pageStates = rememberSaveableStateHolder()

    // 侧栏 hover 捕获源。读取全部下沉到 ShaftRail / RailOverlay 内部：
    // hover 变化只重组侧栏组件，不会带着整个页面（瀑布流）一起重组 —— 修复内容闪烁。
    val railSlotHover = remember { MutableInteractionSource() }
    val railWideHover = remember { MutableInteractionSource() }

    fun push(dest: Dest) {
        if (dest is Dest.Search && dest.query.isNotBlank()) {
            graph.searchHistory.add(dest.query)
        }
        if (backStack.last() != dest) backStack += dest
    }

    fun selectTab(dest: Dest) {
        backStack.clear()
        backStack += dest
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun toggleFullscreen() {
        onToggleFullscreen()
    }

    val splitOn = graph.settings.current.browseLayout == 1
    val split = splitOn && current.supportsSplitBrowse()

    fun openIllustId(id: Long) {
        if (splitOn && current.supportsSplitBrowse()) paneId = id
        else push(Dest.Artwork(id))
    }

    fun openIllust(illust: Illust) = openIllustId(illust.id)

    LaunchedEffect(pendingDest) {
        val dest = pendingDest ?: return@LaunchedEffect
        push(dest)
        onPendingConsumed()
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { searchFocus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                preview.request != null -> {
                    when (event.key) {
                        Key.Escape -> preview.close()
                        Key.DirectionLeft -> preview.step(-1)
                        Key.DirectionRight -> preview.step(1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                event.key == Key.Escape -> {
                    when {
                        searchOpen -> searchOpen = false
                        drawerOpen -> drawerOpen = false
                        split && paneId != null -> paneId = null
                        fullscreen -> onToggleFullscreen()
                        else -> pop()
                    }
                    true
                }
                event.isCtrlPressed && event.key == Key.F -> {
                    searchOpen = true
                    true
                }
                event.key == Key.One -> { selectTab(Dest.Home); true }
                event.key == Key.Two -> { selectTab(Dest.Ranking); true }
                event.key == Key.Three -> { selectTab(Dest.Following); true }
                event.key == Key.Four -> { searchOpen = true; true }
                event.key == Key.Five -> { selectTab(Dest.Me); true }
                else -> false
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 任何页面交互先收起搜索浮层，草稿保留在输入框状态里
                        if (searchOpen) searchOpen = false
                    }
                },
        ) {
            ShaftRail(
                hidden = railHidden,
                selected = current.railTab(),
                drawerOpen = drawerOpen,
                canGoBack = backStack.size > 1,
                fullscreen = fullscreen,
                slotHover = railSlotHover,
                onToggleHidden = { railHidden = !railHidden },
                onBack = { pop() },
                onHome = { selectTab(Dest.Home) },
                onRanking = { selectTab(Dest.Ranking) },
                onFollowing = { selectTab(Dest.Following) },
                onSearch = { searchOpen = true },
                onMe = { selectTab(Dest.Me) },
                onDownload = { selectTab(Dest.Queue) },
                onSettings = { selectTab(Dest.Settings) },
                onToggleFullscreen = { toggleFullscreen() },
                onOpenDrawer = { drawerOpen = true },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalBrowseChrome provides BrowseChrome(paneId, split)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(if (split) (if (panesSwapped) 0.62f else 0.38f) else 1f).fillMaxHeight()) {
                    Crossfade(
                        targetState = current,
                        animationSpec = tween(150),
                        label = "page",
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val keepAlive = page.keepAliveKey()
                        if (keepAlive != null) {
                            pageStates.SaveableStateProvider(keepAlive) {
                                DestContent(page, graph, loader, snackbar, ::push, ::openIllust, ::openIllustId, onLogout)
                            }
                        } else {
                            DestContent(page, graph, loader, snackbar, ::push, ::openIllust, ::openIllustId, onLogout)
                        }
                    }
                }
                if (split) {
                    Box(Modifier.fillMaxHeight().width(30.dp)) {
                        VerticalDivider(Modifier.align(Alignment.Center))
                        Surface(
                            shape = CircleShape,
                            tonalElevation = 3.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .size(28.dp)
                                .clickable { panesSwapped = !panesSwapped },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = "左右交换",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(if (panesSwapped) 0.38f else 0.62f).fillMaxHeight()) {
                        val id = paneId
                        if (id == null) {
                            SplitBrowsePlaceholder()
                        } else {
                            ArtworkScreen(
                                graph = graph,
                                id = id,
                                loader = loader,
                                snackbar = snackbar,
                                onOpenUser = { push(Dest.User(it)) },
                                onOpenTag = { push(Dest.Search(it)) },
                                onOpenIllust = { paneId = it },
                                onOpenManga = { push(Dest.MangaReader(it)) },
                                onOpenRelated = { push(Dest.Related(it)) },
                                embedded = true,
                                onExpand = { push(Dest.Artwork(id)) },
                            )
                        }
                    }
                }
                }
                }
                SnackbarHost(snackbar, modifier = Modifier.fillMaxWidth())
            }
        }
        RailOverlay(
            hidden = railHidden,
            selected = current.railTab(),
            drawerOpen = drawerOpen,
            canGoBack = backStack.size > 1,
            fullscreen = fullscreen,
            slotHover = railSlotHover,
            wideHover = railWideHover,
            onToggleHidden = { railHidden = !railHidden },
            onBack = { pop() },
            onHome = { selectTab(Dest.Home) },
            onRanking = { selectTab(Dest.Ranking) },
            onFollowing = { selectTab(Dest.Following) },
            onSearch = { searchOpen = true },
            onMe = { selectTab(Dest.Me) },
            onDownload = { selectTab(Dest.Queue) },
            onSettings = { selectTab(Dest.Settings) },
            onToggleFullscreen = { toggleFullscreen() },
            onOpenDrawer = { drawerOpen = true },
        )
        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 10.dp,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(0.56f),
            ) {
                OutlinedTextField(
                    value = searchDraft,
                    onValueChange = { searchDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .focusRequester(searchFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                searchOpen = false
                                selectTab(Dest.Search(searchDraft.trim()))
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    label = { Text("搜索插画 / 标签  ·  Ctrl+F") },
                )
            }
        }
        AppDrawer(
            visible = drawerOpen,
            graph = graph,
            loader = loader,
            onClose = { drawerOpen = false },
            onOpen = { push(it) },
        )
        preview.request?.let { req ->
            Box(Modifier.fillMaxSize().zIndex(30f)) {
                ImagePreviewOverlay(req, loader, onClose = { preview.close() })
            }
        }
    }
}

@Composable
private fun DestContent(
    dest: Dest,
    graph: AppGraph,
    loader: ImageLoader,
    snackbar: SnackbarHostState,
    push: (Dest) -> Unit,
    openIllust: (Illust) -> Unit,
    openIllustId: (Long) -> Unit,
    onLogout: () -> Unit,
) {
    when (dest) {
        Dest.Home -> HomePage(
            graph = graph,
            loader = loader,
            onOpen = openIllust,
            onOpenTags = { push(Dest.TrendingTags) },
        )
        Dest.Ranking -> RankingPage(graph, loader, onOpen = openIllust)
        Dest.Following -> FollowingPage(
            graph = graph,
            loader = loader,
            onOpen = openIllust,
            onOpenNovel = { push(Dest.Novel(it.id)) },
        )
        is Dest.Search -> SearchPage(
            graph = graph,
            query = dest.query,
            loader = loader,
            onOpen = openIllust,
            onTag = { push(Dest.Search(it)) },
        )
        Dest.Me -> MeScreen(graph, loader) { push(it) }
        Dest.Bookmarks -> {
            val uid = graph.sessionStore.user?.id
            if (uid == null) Text("未登录", modifier = Modifier.padding(24.dp))
            else SimpleFeedPage(
                graph = graph,
                loader = loader,
                load = { graph.client.api.userBookmarks(uid) },
                onOpen = openIllust,
                loadMore = { url -> graph.client.api.nextIllusts(url) },
                cacheKey = "bookmarks",
                gridKey = "bookmarks",
                feature = FeatureColumn.bookmarks(),
            )
        }
        Dest.Newest -> SimpleFeedPage(
            graph = graph,
            loader = loader,
            load = { graph.client.api.newWorks("illust") },
            onOpen = openIllust,
            loadMore = { url -> graph.client.api.nextIllusts(url) },
            cacheKey = "newest",
            gridKey = "newest",
            feature = FeatureColumn.newest(),
        )
        Dest.History -> {
            val batch = remember { BatchSelection() }
            Column(Modifier.fillMaxSize()) {
                FeedActionRow(graph = graph, batch = batch)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                IllustWaterfall(
                    illusts = graph.history.list(),
                    loading = false,
                    error = null,
                    loader = loader,
                    onOpen = openIllust,
                    gridKey = "history",
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
                )
                }
            }
        }
        Dest.Novels -> NovelListScreen(graph, loader) { push(Dest.Novel(it.id)) }
        is Dest.Novel -> NovelReaderScreen(graph, dest.id)
        Dest.FollowingUsers -> FollowingUsersScreen(graph, loader) { push(Dest.User(it)) }
        Dest.Settings -> SettingsHubScreen { push(Dest.SettingsCategory(it)) }
        is Dest.SettingsCategory -> SettingsCategoryScreen(graph, dest.key, onLogout) { push(it) }
        Dest.Fanbox -> FanboxHomeScreen(graph, loader) { push(Dest.FanboxPost(it)) }
        is Dest.FanboxPost -> FanboxPostScreen(graph, dest.id)
        Dest.Comic -> ComicHomeScreen(graph, loader)
        is Dest.MangaReader -> MangaReaderScreen(graph, dest.id, loader)
        Dest.Chat -> ChatScreen(graph, onOpenIllust = openIllustId, onOpenUser = { push(Dest.User(it)) })
        Dest.ReverseSearch -> ReverseSearchScreen()
        Dest.Library -> LibraryScreen(graph, openIllustId)
        Dest.Queue -> DownloadQueueScreen(graph, loader, openIllustId)
        Dest.Ai -> AiLabScreen()
        Dest.Accounts -> AccountsScreen(graph) { }
        is Dest.Artwork -> ArtworkScreen(
            graph = graph,
            id = dest.id,
            loader = loader,
            snackbar = snackbar,
            onOpenUser = { push(Dest.User(it)) },
            onOpenTag = { push(Dest.Search(it)) },
            onOpenIllust = { push(Dest.Artwork(it)) },
            onOpenManga = { push(Dest.MangaReader(it)) },
            onOpenRelated = { push(Dest.Related(it)) },
        )
        is Dest.Related -> RelatedPage(graph, loader, dest.id, openIllust)
        Dest.TrendingTags -> TrendingTagsPage(graph, loader, onOpen = openIllust, onTag = { push(Dest.Search(it)) })
        Dest.Discovery -> DiscoveryScreen(graph, loader, onOpen = openIllust)
        Dest.Plaza -> PlazaScreen(graph, loader, onOpenIllust = openIllustId, onOpenUser = { push(Dest.User(it)) }, onOpenNovel = { push(Dest.Novel(it)) })
        is Dest.User -> UserScreen(graph, dest.id, loader, snackbar, openIllust, onOpenUser = { push(Dest.User(it)) })
        Dest.NovelBookmarks -> {
            val uid = graph.sessionStore.user?.id
            if (uid == null) Text("未登录", modifier = Modifier.padding(24.dp))
            else NovelListScreen(
                graph = graph,
                loader = loader,
                load = { graph.client.api.userNovelBookmarks(uid) },
                onOpen = { push(Dest.Novel(it.id)) },
            )
        }
        Dest.Fans -> FollowingUsersScreen(graph, loader, followers = true) { push(Dest.User(it)) }
        Dest.WatchLater -> StubScreen("稍后再看")
        Dest.Pinned -> StubScreen("我置顶的内容")
        Dest.Feature -> FeatureColumnsPage(
            graph = graph,
            onOpenColumn = { column ->
                when (column.kind) {
                    "search" -> push(Dest.Search(column.key))
                    "author" -> column.key.toLongOrNull()?.let { push(Dest.User(it)) }
                    "related" -> column.key.toLongOrNull()?.let { push(Dest.Related(it)) }
                    "following" -> push(Dest.Following)
                    "ranking" -> push(Dest.Ranking)
                    "newest" -> push(Dest.Newest)
                    "bookmarks" -> push(Dest.Bookmarks)
                    else -> Unit
                }
            },
        )
        Dest.Watchlist -> StubScreen("追更列表")
        Dest.NovelMarkers -> StubScreen("小说书签")
        Dest.Usage -> StubScreen("使用情况", "借号搜索用量，桌面稍后接入。")
        Dest.Snapshots -> StubScreen("离线快照")
        Dest.Notifications -> StubScreen("通知与公告")
        Dest.Muted -> MutedScreen(graph)
        Dest.EventHistory -> StubScreen("操作记录")
        Dest.About -> AboutScreen(graph)
        Dest.LocalNovels -> LocalNovelsScreen(graph)
        Dest.BulkDebug -> StubScreen("批量下载 Debug")
        Dest.SafTest -> StubScreen("SAF 写入压测", "Windows 无 SAF，此页仅占位。")
        Dest.NetworkTest -> NetworkTestScreen(graph)
        Dest.WebHome -> StubScreen("Web 首页")
    }
}

@Composable
private fun FeatureColumnsPage(
    graph: AppGraph,
    onOpenColumn: (FeatureColumn) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("精华列", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "把一次搜索或一位作者收藏为一栏，每栏都是一条独立瀑布流",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (graph.features.items.isEmpty()) {
            Text(
                "还没有收藏的栏。在搜索、作者、关注、相关、排行等瀑布流点星标收入精华列。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listItems(
                graph.features.items,
                key = { it.kind + ":" + it.key },
            ) { column ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenColumn(column) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (column.kind) {
                                "author" -> Icons.Outlined.Person
                                "search" -> Icons.Outlined.Search
                                "ranking" -> Icons.Outlined.Whatshot
                                "bookmarks" -> Icons.Outlined.Star
                                "following" -> Icons.Outlined.Home
                                else -> Icons.Outlined.Explore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(column.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                column.kindLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { graph.features.remove(column) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "删除",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitBrowsePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("左右分栏", style = MaterialTheme.typography.titleLarge)
            Text(
                "点左侧瀑布流中的作品，详情会显示在这里。Esc 可清空。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 首页：热门标签入口 + 刷新 + 插画/漫画切换 + 推荐瀑布流。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onOpenTags: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("illust") }
    val batch = remember { BatchSelection() }
    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = batch,
            onRefresh = { graph.feedStore.refresh("home:$type") },
            leading = {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenTags),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Whatshot,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tr()["hotTags"], style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        SimpleFeedPage(
            graph = graph,
            loader = loader,
            load = {
                graph.client.api.recommended(type).let { resp ->
                    ceui.pixshaft.shared.model.IllustResponse(
                        illusts = (resp.ranking_illusts + resp.illusts).uniqueIllusts(),
                        next_url = resp.next_url,
                    )
                }
            },
            onOpen = onOpen,
            loadMore = { url -> graph.client.api.nextIllusts(url) },
            key = type,
            cacheKey = "home:$type",
            gridKey = type,
            batch = batch,
            showToolbar = false,
            header = {
                Row(Modifier.padding(4.dp)) {
                    listOf("illust" to "插画", "manga" to "漫画").forEach { (id, label) ->
                        FilterChip(
                            selected = type == id,
                            onClick = { type = id },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            },
        )
        }
    }
}

private val RANK_ILLUST_MODES = listOf(
    "day" to "日榜", "week" to "周榜", "month" to "月榜", "day_ai" to "AI 日榜",
    "day_male" to "男性热榜", "day_female" to "女性热榜", "week_original" to "原创周榜", "week_rookie" to "新人周榜",
    "day_r18" to "R-18 日榜", "week_r18" to "R-18 周榜", "day_male_r18" to "R-18 男性", "day_female_r18" to "R-18 女性",
    "day_r18_ai" to "R-18 AI", "week_r18g" to "R-18G 周榜",
)

private val RANK_MANGA_MODES = listOf(
    "day_manga" to "漫画日榜", "week_manga" to "漫画周榜", "month_manga" to "漫画月榜",
    "week_rookie_manga" to "漫画新人周榜", "day_r18_manga" to "R-18 漫画日榜",
)

/** 排行页：与手机端对齐的 19 种榜单 + 自定义日期。 */
@Composable
private fun RankingPage(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
) {
    var mangaMode by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("day") }
    var date by rememberSaveable { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    val batch = remember { BatchSelection() }

    val modes = (if (mangaMode) RANK_MANGA_MODES else RANK_ILLUST_MODES)
        .filter { graph.settings.current.mainViewR18 || !it.first.contains("r18") }
    LaunchedEffect(modes) {
        if (modes.none { it.first == mode }) mode = modes.firstOrNull()?.first ?: "day"
    }
    val modeLabel = modes.firstOrNull { it.first == mode }?.second ?: mode

    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = batch,
            feature = FeatureColumn.ranking(mode, modeLabel),
            onRefresh = { graph.feedStore.refresh("ranking:$mode:$date") },
            onDownloadAll = {
                graph.feedStore.get("ranking:$mode:$date").items.orEmpty().forEach { graph.queue.enqueue(it) }
            },
            leading = {
                FilterChip(selected = !mangaMode, onClick = { mangaMode = false; mode = "day" }, label = { Text(tr()["illust"]) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mangaMode, onClick = { mangaMode = true; mode = "day_manga" }, label = { Text(tr()["manga"]) })
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Outlined.Event, contentDescription = "选择日期")
                }
                if (date.isNotBlank()) {
                    FilterChip(selected = true, onClick = { showDatePicker = true }, label = { Text(date) })
                    TextButton(onClick = { date = "" }) { Text(tr()["today"]) }
                } else {
                    Text(
                        tr()["pickDateHint"],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        HorizontalWheelRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            modes.forEach { (id, label) ->
                FilterChip(
                    selected = mode == id,
                    onClick = { mode = id },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SimpleFeedPage(
                graph = graph,
                loader = loader,
                load = { graph.client.api.ranking(mode, date.ifBlank { null }) },
                onOpen = onOpen,
                loadMore = { url -> graph.client.api.nextIllusts(url) },
                key = "$mode:$date",
                cacheKey = "ranking:$mode:$date",
                hideBookmarked = graph.settings.current.filterRankBookmarked,
                gridKey = "$mode:$date",
                batch = batch,
                showToolbar = false,
                feature = FeatureColumn.ranking(mode, modeLabel),
            )
        }
    }
    if (showDatePicker) {
        RankingDateDialog(
            initial = if (date.isBlank()) java.time.LocalDate.now().toString() else date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            },
        )
    }
}

/** 月历式日期选择：上一月 / 下一月 / 点选日期 / 回到今日。 */
@Composable
private fun RankingDateDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var month by remember { mutableStateOf(runCatching { java.time.LocalDate.parse(initial).withDayOfMonth(1) }.getOrDefault(java.time.LocalDate.now().withDayOfMonth(1))) }
    var selected by remember { mutableStateOf(runCatching { java.time.LocalDate.parse(initial) }.getOrNull()) }
    val today = java.time.LocalDate.now()
    val firstDow = month.dayOfWeek.value % 7 // 周日=0
    val daysInMonth = month.lengthOfMonth()
    val cells: List<java.time.LocalDate?> = List(firstDow) { null } + (1..daysInMonth).map { month.withDayOfMonth(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上一月")
                    }
                    Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下一月")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        week.forEach { day ->
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val isSel = selected == day
                                    val isToday = day == today
                                    Surface(
                                        shape = CircleShape,
                                        color = when {
                                            isSel -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else -> Color.Transparent
                                        },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .clickable { selected = day },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${day.dayOfMonth}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { onConfirm("") }) { Text("回到今日（不指定日期）") }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onConfirm(it.toString()) } }, enabled = selected != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 关注页：插画·漫画 / 小说 + 全部 / 公开 / 私人。 */
@Composable
private fun FollowingPage(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onOpenNovel: (Novel) -> Unit,
) {
    var novelMode by rememberSaveable { mutableStateOf(false) }
    var restrict by rememberSaveable { mutableStateOf("all") }
    val t = tr()
    val batch = remember(restrict) { BatchSelection() }
    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = if (novelMode) null else batch,
            feature = if (novelMode) null else FeatureColumn.following(restrict),
            onRefresh = if (novelMode) null else ({ graph.feedStore.refresh("following:$restrict") }),
            onDownloadAll = if (novelMode) null else ({
                graph.feedStore.get("following:$restrict").items.orEmpty().forEach { graph.queue.enqueue(it) }
            }),
            leading = {
                FilterChip(selected = !novelMode, onClick = { novelMode = false }, label = { Text(t["illustManga"]) })
                FilterChip(selected = novelMode, onClick = { novelMode = true }, label = { Text(t["novel"]) })
                Spacer(Modifier.width(6.dp))
                listOf("all" to t["all"], "public" to t["public"], "private" to t["private"]).forEach { (value, label) ->
                    FilterChip(selected = restrict == value, onClick = { restrict = value }, label = { Text(label) })
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (novelMode) {
            NovelListScreen(
                graph = graph,
                loader = loader,
                load = { graph.client.api.novelFollowing(restrict) },
                onOpen = onOpenNovel,
            )
        } else {
            SimpleFeedPage(
                graph = graph,
                loader = loader,
                load = { graph.client.api.following(restrict) },
                onOpen = onOpen,
                loadMore = { url -> graph.client.api.nextIllusts(url) },
                key = restrict,
                cacheKey = "following:$restrict",
                gridKey = restrict,
                batch = batch,
                showToolbar = false,
                feature = FeatureColumn.following(restrict),
            )
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPage(
    graph: AppGraph,
    query: String,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onTag: (String) -> Unit,
) {
    if (query.isBlank()) {
        SearchLanding(graph, loader, onOpen, onTag)
        return
    }
    val sort = graph.settings.current.searchDefaultSortType
    val target = graph.settings.current.searchTarget
    val bookmarkMin = graph.settings.current.searchBookmarkMin
    val r18 = graph.settings.current.searchR18
    val t = tr()
    val batch = remember(query) { BatchSelection() }
    val cacheKey = "search:$query:$sort:$target:$bookmarkMin:$r18"
    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = batch,
            feature = FeatureColumn.search(query),
            onRefresh = { graph.feedStore.refresh(cacheKey) },
            onDownloadAll = {
                graph.feedStore.get(cacheKey).items.orEmpty().forEach { graph.queue.enqueue(it) }
            },
        )
        HorizontalWheelRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                t["filter"],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 12.dp),
            )
            listOf("date_desc" to "最新", "date_asc" to "最旧", "popular_desc" to "热度").forEach { (value, label) ->
                FilterChip(
                    selected = sort == value,
                    onClick = { graph.settings.update { it.copy(searchDefaultSortType = value) } },
                    label = { Text(label) },
                )
            }
            listOf(
                "partial_match_for_tags" to "部分一致",
                "exact_match_for_tags" to "完全一致",
                "title_and_caption" to "标题说明文",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = target == value,
                    onClick = { graph.settings.update { it.copy(searchTarget = value) } },
                    label = { Text(label) },
                )
            }
            listOf(0 to "收藏不限", 500 to "500+", 1000 to "1000+", 5000 to "5000+", 10000 to "1 万+").forEach { (value, label) ->
                FilterChip(
                    selected = bookmarkMin == value,
                    onClick = { graph.settings.update { it.copy(searchBookmarkMin = value) } },
                    label = { Text(label) },
                )
            }
            listOf(0 to "R-18 全部", 1 to "仅 R-18", 2 to "无 R-18").forEach { (value, label) ->
                FilterChip(
                    selected = r18 == value,
                    onClick = { graph.settings.update { it.copy(searchR18 = value) } },
                    label = { Text(label) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SimpleFeedPage(
                graph = graph,
                loader = loader,
                load = {
                    graph.client.api.searchIllust(
                        query,
                        sort = sort,
                        searchTarget = target,
                    )
                },
                onOpen = onOpen,
                loadMore = { url -> graph.client.api.nextIllusts(url) },
                key = "$query:$sort:$target:$bookmarkMin:$r18",
                cacheKey = cacheKey,
                gridKey = "$query:$sort:$target:$bookmarkMin:$r18",
                batch = batch,
                showToolbar = false,
                feature = FeatureColumn.search(query),
                predicate = { illust ->
                    (bookmarkMin == 0 || (illust.total_bookmarks ?: 0) >= bookmarkMin) &&
                        when (r18) {
                            1 -> illust.isR18()
                            2 -> !illust.isR18()
                            else -> true
                        }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchLanding(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onTag: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (graph.searchHistory.items.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "搜索历史",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { graph.searchHistory.clear() }) { Text("清空") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                graph.searchHistory.items.forEach { q ->
                    FilterChip(
                        selected = false,
                        onClick = { onTag(q) },
                        label = { Text(q) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        TrendingTagsPane(graph, loader, onOpen, onTag)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TrendingTagsPane(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onTag: (String) -> Unit,
) {
    var tags by remember { mutableStateOf<List<ceui.pixshaft.shared.model.TrendingTag>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { graph.client.api.trendingTags("illust") } }
            .onSuccess { tags = it.trend_tags }
    }
    Column(Modifier.padding(16.dp)) {
        Text("搜索发现", style = MaterialTheme.typography.titleMedium)
        Text("点标签搜索，或点封面进作品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.take(24).forEach { tag ->
                val name = tag.tag.orEmpty()
                FilterChip(
                    selected = false,
                    onClick = { if (name.isNotBlank()) onTag(name) },
                    label = { Text("#${tag.display()}") },
                )
            }
        }
        RankingStrip(
            illusts = tags.mapNotNull { it.illust },
            loader = loader,
            onOpen = onOpen,
        )
    }
}

/** 相关作品瀑布流（带页面缓存：进出不重复请求）。 */
@Composable
private fun RelatedPage(
    graph: AppGraph,
    loader: ImageLoader,
    id: Long,
    onOpen: (Illust) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val feed = graph.feedStore.get("related:$id")
    val items = feed.items.orEmpty()
    val batch = remember(id) { BatchSelection() }
    LaunchedEffect(id, feed.reload) {
        if (feed.items != null) return@LaunchedEffect
        feed.loading = true
        feed.error = null
        runCatching { withContext(Dispatchers.IO) { graph.client.api.related(id) } }
            .onSuccess { resp ->
                feed.items = resp.illusts.uniqueIllusts().filterNot { illust -> graph.settings.current.shouldHide(illust) }
                feed.next = resp.next_url
            }
            .onFailure { feed.error = it.userMessage() }
        feed.loading = false
    }
    Column(Modifier.fillMaxSize()) {
        FeedActionRow(
            graph = graph,
            batch = batch,
            feature = FeatureColumn.related(id),
            onRefresh = { graph.feedStore.refresh("related:$id") },
            onDownloadAll = { items.forEach { graph.queue.enqueue(it) } },
            leading = {
                Text("相关作品", style = MaterialTheme.typography.titleLarge)
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        IllustWaterfall(
            illusts = items,
            loading = feed.loading,
            error = feed.error,
            loader = loader,
            onOpen = onOpen,
            gridKey = id,
            onRetry = { feed.reset() },
            batch = batch,
            onBatchDownload = { list ->
                list.forEach { graph.queue.enqueue(it) }
                batch.reset()
            },
            onDownload = { graph.queue.enqueue(it) },
            onToggleBookmark = { target ->
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
                        feed.items = feed.items?.map {
                            if (it.id == target.id) it.copy(is_bookmarked = !target.isBookmarked) else it
                        }
                    }
                }
            },
            onAddFeature = { illust ->
                val author = illust.user ?: return@IllustWaterfall
                graph.features.add(FeatureColumn.author(author.id, author.name.orEmpty()))
            },
            onLoadMore = {
                val url = feed.next ?: return@IllustWaterfall
                if (feed.loadingMore) return@IllustWaterfall
                feed.loadingMore = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.client.api.nextIllusts(url) } }
                        .onSuccess {
                            feed.items = (feed.items.orEmpty() + it.illusts).uniqueIllusts()
                                .filterNot { illust -> graph.settings.current.shouldHide(illust) }
                            feed.next = it.next_url
                        }
                    feed.loadingMore = false
                }
            },
        )
        }
    }
}

/** 热门标签独立页：标签 + 封面网格（对齐手机端热门标签页）。 */
@Composable
private fun TrendingTagsPage(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onTag: (String) -> Unit,
) {
    var tags by remember { mutableStateOf<List<ceui.pixshaft.shared.model.TrendingTag>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { graph.client.api.trendingTags("illust") } }
            .onSuccess { tags = it.trend_tags; error = null }
            .onFailure { error = it.userMessage() }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            tr()["hotTags"],
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !error.isNullOrBlank() && tags.isEmpty() -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(220.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        tags.filter { it.illust != null },
                        key = { it.tag.orEmpty().ifBlank { "#${System.identityHashCode(it)}" } },
                    ) { tag ->
                        val illust = tag.illust ?: return@items
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { tag.tag?.let(onTag) },
                        ) {
                            PixivImage(
                                url = illust.previewUrl(large = graph.settings.current.showLargeThumbnailImage),
                                contentDescription = tag.display(),
                                loader = loader,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            )
                            Text(
                                "#${tag.display()}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShaftRail(
    hidden: Boolean,
    selected: RailTab,
    drawerOpen: Boolean,
    canGoBack: Boolean,
    fullscreen: Boolean,
    slotHover: MutableInteractionSource,
    onToggleHidden: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRanking: () -> Unit,
    onFollowing: () -> Unit,
    onSearch: () -> Unit,
    onMe: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    if (hidden) {
        // Visible restore tab, inset so maximized DWM overscan cannot clip it.
        Box(Modifier.fillMaxHeight().width(28.dp).zIndex(12f), contentAlignment = Alignment.CenterStart) {
            Surface(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .height(88.dp)
                    .width(22.dp)
                    .clickable(onClick = onToggleHidden),
                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.KeyboardDoubleArrowRight,
                        contentDescription = tr()["restoreSidebar"],
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    } else {
        // 常驻窄栏：布局占位只有 60dp，图标水平居中；展开态由根 Box 里的浮层负责
        Surface(
            modifier = Modifier.fillMaxHeight().width(60.dp).hoverable(slotHover),
            shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            RailItems(
                expanded = false,
                selected = selected,
                drawerOpen = drawerOpen,
                canGoBack = canGoBack,
                fullscreen = fullscreen,
                onBack = onBack,
                onHome = onHome,
                onRanking = onRanking,
                onFollowing = onFollowing,
                onSearch = onSearch,
                onMe = onMe,
                onDownload = onDownload,
                onSettings = onSettings,
                onToggleFullscreen = onToggleFullscreen,
                onToggleHidden = onToggleHidden,
                onOpenDrawer = onOpenDrawer,
            )
        }
    }
}

/**
 * 展开态侧栏浮层：固定 196dp，盖在内容上、不占布局。
 * hover 状态在本组件内部读取 —— 重组只发生在这里，不影响页面内容。
 */
@Composable
private fun RailOverlay(
    hidden: Boolean,
    selected: RailTab,
    drawerOpen: Boolean,
    canGoBack: Boolean,
    fullscreen: Boolean,
    slotHover: MutableInteractionSource,
    wideHover: MutableInteractionSource,
    onToggleHidden: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRanking: () -> Unit,
    onFollowing: () -> Unit,
    onSearch: () -> Unit,
    onMe: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val slotHovered by slotHover.collectIsHoveredAsState()
    val wideHovered by wideHover.collectIsHoveredAsState()
    val expanded = !hidden && (slotHovered || wideHovered)
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(110)),
        exit = fadeOut(tween(110)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(196.dp)
                .hoverable(wideHover),
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(196.dp),
                shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 10.dp,
            ) {
                RailItems(
                    expanded = true,
                    selected = selected,
                    drawerOpen = drawerOpen,
                    canGoBack = canGoBack,
                    fullscreen = fullscreen,
                    onBack = onBack,
                    onHome = onHome,
                    onRanking = onRanking,
                    onFollowing = onFollowing,
                    onSearch = onSearch,
                    onMe = onMe,
                    onDownload = onDownload,
                    onSettings = onSettings,
                    onToggleFullscreen = onToggleFullscreen,
                    onToggleHidden = onToggleHidden,
                    onOpenDrawer = onOpenDrawer,
                )
            }
        }
    }
}

@Composable
private fun RailItems(
    expanded: Boolean,
    selected: RailTab,
    drawerOpen: Boolean,
    canGoBack: Boolean,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRanking: () -> Unit,
    onFollowing: () -> Unit,
    onSearch: () -> Unit,
    onMe: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val t = tr()
    Column(
        Modifier.fillMaxHeight().padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        RailGlyph(
            expanded = expanded,
            selected = false,
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            label = t["back"],
            onClick = onBack,
            enabled = canGoBack,
        )
        Spacer(Modifier.height(8.dp))
        RailGlyph(expanded, selected == RailTab.Home, Icons.Outlined.Home, t["home"], onHome)
        RailGlyph(expanded, selected == RailTab.Ranking, Icons.Outlined.Star, t["ranking"], onRanking)
        RailGlyph(expanded, selected == RailTab.Following, Icons.Outlined.Explore, t["following"], onFollowing)
        RailGlyph(expanded, selected == RailTab.Search, Icons.Outlined.Search, t["search"], onSearch)
        RailGlyph(expanded, selected == RailTab.Me, Icons.Outlined.Person, t["me"], onMe)
        RailGlyph(expanded, selected == RailTab.Download, Icons.Outlined.Download, t["download"], onDownload)
        Spacer(Modifier.weight(1f))
        RailGlyph(
            expanded,
            false,
            if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            if (fullscreen) t["exitFullscreen"] else t["fullscreen"],
            onToggleFullscreen,
        )
        RailGlyph(
            expanded,
            false,
            Icons.Outlined.KeyboardDoubleArrowLeft,
            t["hideSidebar"],
            onToggleHidden,
        )
        RailGlyph(expanded, selected == RailTab.Settings, Icons.Outlined.Settings, t["settings"], onSettings)
        RailGlyph(expanded, drawerOpen, Icons.Outlined.Menu, t["menu"], onOpenDrawer)
    }
}

@Composable
private fun RailGlyph(
    expanded: Boolean,
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (enabled) 1f else 0.35f),
        )
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.alpha(if (enabled) 1f else 0.35f),
            )
        }
    }
}

/**
 * 通用瀑布流页面：数据放进 [FeedStore]（cacheKey 非空时），切换页面 / 返回不丢数据、不重复请求。
 */
@Composable
internal fun SimpleFeedPage(
    graph: AppGraph,
    loader: ImageLoader,
    load: suspend () -> ceui.pixshaft.shared.model.IllustResponse,
    onOpen: (Illust) -> Unit,
    loadMore: suspend (String) -> ceui.pixshaft.shared.model.IllustResponse,
    key: Any = Unit,
    hideBookmarked: Boolean = false,
    header: @Composable (() -> Unit)? = null,
    cacheKey: String? = null,
    predicate: ((Illust) -> Boolean)? = null,
    gridKey: Any = Unit,
    batch: BatchSelection? = null,
    showToolbar: Boolean = true,
    feature: FeatureColumn? = null,
) {
    val feed = cacheKey?.let { graph.feedStore.get(it) } ?: remember(key) { FeedState() }
    val items = feed.items.orEmpty()
    val scope = rememberCoroutineScope()
    val ownedBatch = remember(cacheKey, key) { BatchSelection() }
    val selection = batch ?: ownedBatch

    fun filtered(list: List<Illust>): List<Illust> =
        list.uniqueIllusts()
            .filterNot { graph.settings.current.shouldHide(it) || (hideBookmarked && it.isBookmarked) }
            .let { if (predicate != null) it.filter(predicate) else it }

    fun toggleBookmark(target: Illust) {
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
                feed.items = feed.items?.map {
                    if (it.id == target.id) it.copy(is_bookmarked = !target.isBookmarked) else it
                }
            }
        }
    }

    LaunchedEffect(key, feed.reload) {
        if (feed.items != null) return@LaunchedEffect
        feed.loading = true
        feed.error = null
        runCatching { withContext(Dispatchers.IO) { load() } }
            .onSuccess {
                feed.items = filtered(it.illusts)
                feed.next = it.next_url
            }
            .onFailure { feed.error = it.userMessage() }
        feed.loading = false
    }
    val waterfall = @Composable {
        IllustWaterfall(
            illusts = items,
            loading = feed.loading,
            error = feed.error,
            loader = loader,
            onOpen = onOpen,
            header = header,
            loadingMore = feed.loadingMore,
            gridKey = gridKey,
            onToggleBookmark = { toggleBookmark(it) },
            onAddFeature = { illust ->
                val author = illust.user ?: return@IllustWaterfall
                graph.features.add(FeatureColumn.author(author.id, author.name.orEmpty()))
            },
            onHide = { target -> feed.items = feed.items?.filterNot { it.id == target.id } },
            batch = selection,
            onBatchDownload = { list ->
                list.forEach { graph.queue.enqueue(it) }
                selection.reset()
            },
            onDownload = { graph.queue.enqueue(it) },
            onRetry = { feed.reset() },
            onLoadMore = {
                val url = feed.next ?: return@IllustWaterfall
                if (feed.loadingMore) return@IllustWaterfall
                feed.loadingMore = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { loadMore(url) } }
                        .onSuccess {
                            feed.items = filtered(feed.items.orEmpty() + it.illusts)
                            feed.next = it.next_url
                        }
                    feed.loadingMore = false
                }
            },
        )
    }
    if (showToolbar) {
        Column(Modifier.fillMaxSize()) {
            FeedActionRow(
                graph = graph,
                batch = selection,
                feature = feature,
                onRefresh = cacheKey?.let { ck -> { graph.feedStore.refresh(ck) } },
                onDownloadAll = if (feature != null) {
                    { items.forEach { graph.queue.enqueue(it) } }
                } else {
                    null
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { waterfall() }
        }
    } else {
        waterfall()
    }
}
