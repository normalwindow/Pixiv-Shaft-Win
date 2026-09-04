package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.SingleInstance
import ceui.pixshaft.shared.model.Illust
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShaftApp(graph: AppGraph, initialUri: String?) {
    ShaftTheme {
        var loggedIn by remember { mutableStateOf(graph.sessionStore.isLoggedIn) }
        var loginError by remember { mutableStateOf<String?>(null) }
        val loader = rememberImageLoader(graph)

        val loginScope = rememberCoroutineScope()
        DisposableEffect(graph) {
            val listener: (String) -> Unit = { uri ->
                if (!graph.offerLoginCallback(uri)) {
                    loginScope.launch {
                        runCatching { graph.completeLoginFromUri(uri) }
                            .onSuccess {
                                loggedIn = true
                                loginError = null
                            }
                            .onFailure { loginError = it.message }
                    }
                }
            }
            SingleInstance.onUri(listener)
            onDispose { SingleInstance.removeUri(listener) }
        }

        LaunchedEffect(initialUri) {
            if (!initialUri.isNullOrBlank() && !loggedIn) {
                runCatching { graph.completeLoginFromUri(initialUri) }
                    .onSuccess { loggedIn = true }
                    .onFailure { loginError = it.message }
            }
        }

        Surface(Modifier.fillMaxSize()) {
            if (!loggedIn) {
                LoginScreen(
                    graph = graph,
                    error = loginError,
                    onLoggedIn = { loggedIn = true },
                    onError = { loginError = it },
                )
            } else {
                LoggedInShell(
                    graph = graph,
                    loader = loader,
                    onLogout = {
                        graph.logout()
                        loggedIn = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LoggedInShell(
    graph: AppGraph,
    loader: ImageLoader,
    onLogout: () -> Unit,
) {
    val backStack = remember { mutableStateListOf<Dest>(Dest.Home) }
    val current = backStack.last()
    var searchDraft by remember { mutableStateOf("") }

    fun push(dest: Dest) {
        if (backStack.last() != dest) backStack += dest
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    Row(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.Escape -> {
                    pop(); true
                }
                event.isCtrlPressed && event.key == Key.F -> {
                    push(Dest.Search(searchDraft)); true
                }
                event.key == Key.One -> { push(Dest.Home); true }
                event.key == Key.Two -> { push(Dest.Ranking); true }
                event.key == Key.Three -> { push(Dest.Following); true }
                event.key == Key.Four -> { push(Dest.Search()); true }
                event.key == Key.Five -> {
                    graph.sessionStore.user?.id?.let { push(Dest.User(it)) } ?: push(Dest.Me)
                    true
                }
                else -> false
            }
        },
    ) {
        NavigationRail(modifier = Modifier.fillMaxHeight()) {
            RailItem(RailTab.Home, current.railTab(), Icons.Outlined.Home, "首页") { push(Dest.Home) }
            RailItem(RailTab.Ranking, current.railTab(), Icons.Outlined.Star, "排行") { push(Dest.Ranking) }
            RailItem(RailTab.Following, current.railTab(), Icons.Outlined.Explore, "关注") { push(Dest.Following) }
            RailItem(RailTab.Search, current.railTab(), Icons.Outlined.Search, "搜索") { push(Dest.Search(searchDraft)) }
            RailItem(RailTab.Me, current.railTab(), Icons.Outlined.Person, "我的") {
                graph.sessionStore.user?.id?.let { push(Dest.User(it)) } ?: push(Dest.Me)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchDraft,
                    onValueChange = { searchDraft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索插画 / 标签") },
                )
                IconButton(onClick = { push(Dest.Search(searchDraft.trim())) }) {
                    Icon(Icons.Outlined.Search, contentDescription = "搜索")
                }
                val me = graph.sessionStore.user
                Text(me?.name ?: "", modifier = Modifier.padding(horizontal = 12.dp))
                IconButton(onClick = onLogout) {
                    Icon(Icons.Outlined.Logout, contentDescription = "退出登录")
                }
            }
            when (val dest = current) {
                Dest.Home -> FeedPage(
                    loader = loader,
                    load = { graph.client.api.recommended("illust") },
                    nextOf = { it.next_url },
                    itemsOf = { it.illusts },
                    rankingOf = { it.ranking_illusts },
                    onOpen = { push(Dest.Artwork(it.id)) },
                    loadMore = { url -> graph.client.api.nextIllusts(url) },
                )
                Dest.Ranking -> RankingPage(graph, loader) { push(Dest.Artwork(it.id)) }
                Dest.Following -> SimpleFeedPage(
                    loader = loader,
                    load = { graph.client.api.following("all") },
                    onOpen = { push(Dest.Artwork(it.id)) },
                    loadMore = { url -> graph.client.api.nextIllusts(url) },
                )
                is Dest.Search -> SearchPage(
                    graph = graph,
                    query = dest.query.ifBlank { searchDraft },
                    loader = loader,
                    onOpen = { push(Dest.Artwork(it.id)) },
                )
                Dest.Me -> {
                    val uid = graph.sessionStore.user?.id
                    if (uid != null) {
                        UserScreen(graph, uid, loader) { push(Dest.Artwork(it.id)) }
                    } else {
                        Text("未登录用户信息", modifier = Modifier.padding(24.dp))
                    }
                }
                is Dest.Artwork -> ArtworkScreen(
                    graph = graph,
                    id = dest.id,
                    loader = loader,
                    onOpenUser = { push(Dest.User(it)) },
                    onOpenTag = { push(Dest.Search(it)) },
                    onOpenIllust = { push(Dest.Artwork(it)) },
                )
                is Dest.User -> UserScreen(graph, dest.id, loader) { push(Dest.Artwork(it.id)) }
            }
        }
    }
}

@Composable
private fun RailItem(
    tab: RailTab,
    selected: RailTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = tab == selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankingPage(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
) {
    var mode by remember { mutableStateOf("day") }
    SimpleFeedPage(
        key = mode,
        loader = loader,
        load = { graph.client.api.ranking(mode) },
        onOpen = onOpen,
        loadMore = { url -> graph.client.api.nextIllusts(url) },
        header = {
            Row(Modifier.padding(4.dp)) {
                listOf("day" to "日榜", "week" to "周榜", "month" to "月榜", "day_male" to "男性", "day_female" to "女性").forEach { (id, label) ->
                    FilterChip(
                        selected = mode == id,
                        onClick = { mode = id },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SearchPage(
    graph: AppGraph,
    query: String,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
) {
    if (query.isBlank()) {
        Text("输入关键词后回车或点搜索", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SimpleFeedPage(
        key = query,
        loader = loader,
        load = { graph.client.api.searchIllust(query) },
        onOpen = onOpen,
        loadMore = { url -> graph.client.api.nextIllusts(url) },
    )
}

@Composable
private fun SimpleFeedPage(
    loader: ImageLoader,
    load: suspend () -> ceui.pixshaft.shared.model.IllustResponse,
    onOpen: (Illust) -> Unit,
    loadMore: suspend (String) -> ceui.pixshaft.shared.model.IllustResponse,
    key: Any = Unit,
    header: @Composable (() -> Unit)? = null,
) {
    var items by remember(key) { mutableStateOf<List<Illust>>(emptyList()) }
    var next by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { load() } }
            .onSuccess {
                items = it.illusts
                next = it.next_url
            }
            .onFailure { error = it.message }
        loading = false
    }
    IllustWaterfall(
        illusts = items,
        loading = loading,
        error = error,
        loader = loader,
        onOpen = onOpen,
        header = header,
        onLoadMore = {
            val url = next ?: return@IllustWaterfall
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { loadMore(url) } }
                    .onSuccess {
                        items = items + it.illusts
                        next = it.next_url
                    }
            }
        },
    )
}

@Composable
private fun FeedPage(
    loader: ImageLoader,
    load: suspend () -> ceui.pixshaft.shared.model.HomeIllustResponse,
    nextOf: (ceui.pixshaft.shared.model.HomeIllustResponse) -> String?,
    itemsOf: (ceui.pixshaft.shared.model.HomeIllustResponse) -> List<Illust>,
    rankingOf: (ceui.pixshaft.shared.model.HomeIllustResponse) -> List<Illust>,
    onOpen: (Illust) -> Unit,
    loadMore: suspend (String) -> ceui.pixshaft.shared.model.IllustResponse,
) {
    var items by remember { mutableStateOf<List<Illust>>(emptyList()) }
    var ranking by remember { mutableStateOf<List<Illust>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { load() } }
            .onSuccess {
                ranking = rankingOf(it)
                items = itemsOf(it)
                next = nextOf(it)
            }
            .onFailure { error = it.message }
        loading = false
    }
    IllustWaterfall(
        illusts = ranking + items,
        loading = loading,
        error = error,
        loader = loader,
        onOpen = onOpen,
        onLoadMore = {
            val url = next ?: return@IllustWaterfall
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { loadMore(url) } }
                    .onSuccess {
                        items = items + it.illusts
                        next = it.next_url
                    }
            }
        },
    )
}
