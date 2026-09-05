package ceui.pixshaft.desktop.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 其它用户页：资料头 + 插画 / 漫画 / 关注（收藏）三个页签。 */
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

    LaunchedEffect(userId) {
        detailLoading = true
        runCatching { withContext(Dispatchers.IO) { graph.client.api.userDetail(userId) } }
            .onSuccess { detail = it }
            .onFailure { snackbar.showSnackbar(it.userMessage()) }
        detailLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        val user = detail?.user
        val profile = detail?.profile
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixivImage(
                url = user?.avatar(),
                contentDescription = user?.name,
                modifier = Modifier.size(72.dp).clip(CircleShape),
                loader = loader,
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(user?.name ?: "user $userId", style = MaterialTheme.typography.headlineSmall)
                Text("@${user?.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${profile?.total_illusts ?: 0} 插画 · ${profile?.total_manga ?: 0} 漫画 · ${profile?.total_follow_users ?: 0} 关注",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!user?.comment.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(user!!.comment!!, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
            }
            val followed = user?.is_followed == true
            if (followed) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { graph.client.api.unfollowUser(userId) } }
                            .onSuccess { detail = detail?.copy(user = user?.copy(is_followed = false)) }
                            .onFailure { snackbar.showSnackbar(it.userMessage()) }
                    }
                }) { Text("已关注") }
            } else {
                Button(onClick = {
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
                }) { Text("关注") }
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("插画", "漫画", "关注").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> UserWorksFeed(
                graph = graph,
                userId = userId,
                loader = loader,
                type = "illust",
                cacheKey = "user-illusts:$userId",
                onOpenIllust = onOpenIllust,
            )
            1 -> UserWorksFeed(
                graph = graph,
                userId = userId,
                loader = loader,
                type = "manga",
                cacheKey = "user-manga:$userId",
                onOpenIllust = onOpenIllust,
            )
            else -> Column(Modifier.fillMaxSize()) {
                // 公开 / 私人 = 该用户的收藏；关注的用户 / 好P友 = 用户列表（与公开、私人并行）
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

@Composable
private fun UserWorksFeed(
    graph: AppGraph,
    userId: Long,
    loader: ImageLoader,
    type: String,
    cacheKey: String,
    onOpenIllust: (Illust) -> Unit,
) {
    SimpleFeedPage(
        graph = graph,
        loader = loader,
        load = { graph.client.api.userIllusts(userId, type) },
        onOpen = onOpenIllust,
        loadMore = { url -> graph.client.api.nextIllusts(url) },
        cacheKey = cacheKey,
        gridKey = cacheKey,
    )
}
