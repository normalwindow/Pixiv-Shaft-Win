package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.BatchSelection
import ceui.pixshaft.desktop.FeatureColumn
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 其它用户页：资料头 + 插画 / 漫画 / 关注（收藏）三个页签。滚动时资料区收缩。 */
@Composable
fun UserScreen(
    graph: AppGraph,
    userId: Long,
    loader: ImageLoader,
    snackbar: SnackbarHostState,
    onOpenIllust: (Illust) -> Unit,
    onOpenUser: (Long) -> Unit = {},
) {
    var detail by remember(userId) { mutableStateOf<ceui.pixshaft.shared.model.UserDetail?>(null) }
    var detailLoading by remember(userId) { mutableStateOf(true) }
    var tab by rememberSaveable(userId) { mutableStateOf(0) }
    var bookmarkRestrict by rememberSaveable(userId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val batch = remember(userId, tab, bookmarkRestrict) { BatchSelection() }
    val collapse = remember(userId) { mutableFloatStateOf(0f) }
    val rangePx = with(LocalDensity.current) { 96.dp.toPx() }.coerceAtLeast(1f)
    val connection = remember(rangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val prev = collapse.floatValue
                val next = (prev + (-available.y) / rangePx).coerceIn(0f, 1f)
                collapse.floatValue = next
                return Offset(0f, -(next - prev) * rangePx)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val prev = collapse.floatValue
                val next = (prev - available.y / rangePx).coerceIn(0f, 1f)
                collapse.floatValue = next
                return Offset(0f, (prev - next) * rangePx)
            }
        }
    }

    LaunchedEffect(userId) {
        collapse.floatValue = 0f
        detailLoading = true
        runCatching { withContext(Dispatchers.IO) { graph.client.api.userDetail(userId) } }
            .onSuccess { detail = it }
            .onFailure { snackbar.showSnackbar(it.userMessage()) }
        detailLoading = false
    }

    val user = detail?.user
    val profile = detail?.profile
    val t = collapse.floatValue
    val avatar = lerp(72f, 22f, t).dp
    val vPad = lerp(16f, 2f, t).dp
    val nameStyle = if (t > 0.4f) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall
    val feature = FeatureColumn.author(userId, user?.name.orEmpty())
    val cacheKey = when (tab) {
        0 -> "user-illusts:$userId"
        1 -> "user-manga:$userId"
        else -> when (bookmarkRestrict) {
            0 -> "user-bookmarks:$userId:public"
            1 -> "user-bookmarks:$userId:private"
            else -> null
        }
    }

    Column(Modifier.fillMaxSize().nestedScroll(connection)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = 20.dp, vertical = vPad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixivImage(
                    url = user?.avatar(),
                    contentDescription = user?.name,
                    modifier = Modifier.size(avatar).clip(CircleShape),
                    loader = loader,
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(user?.name ?: "user $userId", style = nameStyle, maxLines = 1)
                    if (t < 0.4f) {
                        Text(
                            "@${user?.account.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    if (t < 0.22f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${profile?.total_illusts ?: 0} 插画 · ${profile?.total_manga ?: 0} 漫画 · ${profile?.total_follow_users ?: 0} 关注",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (t < 0.08f && !user?.comment.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(user!!.comment!!, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
                val followed = user?.is_followed == true
                if (t <= 0.55f) {
                    if (followed) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { graph.client.api.unfollowUser(userId) } }
                                        .onSuccess { detail = detail?.copy(user = user?.copy(is_followed = false)) }
                                        .onFailure { snackbar.showSnackbar(it.userMessage()) }
                                }
                            },
                        ) { Text("已关注") }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            graph.client.api.followUser(
                                                userId,
                                                if (graph.settings.current.privateFollow) "private" else "public",
                                            )
                                        }
                                    }
                                        .onSuccess { detail = detail?.copy(user = user?.copy(is_followed = true)) }
                                        .onFailure { snackbar.showSnackbar(it.userMessage()) }
                                }
                            },
                        ) { Text("关注") }
                    }
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("插画", "漫画", "关注").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        if (cacheKey != null) {
            FeedActionRow(
                graph = graph,
                batch = batch,
                feature = feature,
                onRefresh = { graph.feedStore.refresh(cacheKey) },
                onDownloadAll = {
                    graph.feedStore.get(cacheKey).items.orEmpty().forEach { graph.queue.enqueue(it) }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when (tab) {
            0 -> UserWorksFeed(
                graph = graph,
                userId = userId,
                loader = loader,
                type = "illust",
                cacheKey = "user-illusts:$userId",
                onOpenIllust = onOpenIllust,
                batch = batch,
            )
            1 -> UserWorksFeed(
                graph = graph,
                userId = userId,
                loader = loader,
                type = "manga",
                cacheKey = "user-manga:$userId",
                onOpenIllust = onOpenIllust,
                batch = batch,
            )
            else -> Column(Modifier.fillMaxSize()) {
                HorizontalWheelRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    listOf(0 to "公开", 1 to "私人", 2 to "关注的用户", 3 to "好P友").forEach { (value, label) ->
                        FilterChip(
                            selected = bookmarkRestrict == value,
                            onClick = { bookmarkRestrict = value },
                            label = { Text(label) },
                        )
                    }
                }
                when (bookmarkRestrict) {
                    0, 1 -> {
                        val restrict = if (bookmarkRestrict == 0) "public" else "private"
                        SimpleFeedPage(
                            graph = graph,
                            loader = loader,
                            load = { graph.client.api.userBookmarks(userId, restrict) },
                            onOpen = onOpenIllust,
                            loadMore = { url -> graph.client.api.nextIllusts(url) },
                            key = restrict,
                            cacheKey = "user-bookmarks:$userId:$restrict",
                            gridKey = restrict,
                            batch = batch,
                            showToolbar = false,
                            feature = feature,
                        )
                    }
                    2 -> UserListScreen(
                        graph = graph,
                        loader = loader,
                        load = { graph.client.api.followingUsers(userId) },
                        onOpenUser = onOpenUser,
                        key = "$userId:following",
                    )
                    else -> UserListScreen(
                        graph = graph,
                        loader = loader,
                        load = { graph.client.api.userMyPixiv(userId) },
                        onOpenUser = onOpenUser,
                        key = "$userId:mypixiv",
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun UserWorksFeed(
    graph: AppGraph,
    userId: Long,
    loader: ImageLoader,
    type: String,
    cacheKey: String,
    onOpenIllust: (Illust) -> Unit,
    batch: BatchSelection,
) {
    SimpleFeedPage(
        graph = graph,
        loader = loader,
        load = { graph.client.api.userIllusts(userId, type) },
        onOpen = onOpenIllust,
        loadMore = { url -> graph.client.api.nextIllusts(url) },
        cacheKey = cacheKey,
        gridKey = cacheKey,
        batch = batch,
        showToolbar = false,
        feature = FeatureColumn.author(userId, ""),
    )
}
