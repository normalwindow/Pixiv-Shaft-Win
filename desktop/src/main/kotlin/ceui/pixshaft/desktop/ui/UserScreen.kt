package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.model.UserDetail
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserScreen(
    graph: AppGraph,
    userId: Long,
    loader: ImageLoader,
    onOpenIllust: (Illust) -> Unit,
) {
    var detail by remember(userId) { mutableStateOf<UserDetail?>(null) }
    var works by remember(userId) { mutableStateOf<List<Illust>>(emptyList()) }
    var next by remember(userId) { mutableStateOf<String?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(userId) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val d = graph.client.api.userDetail(userId)
                val list = graph.client.api.userIllusts(userId)
                Triple(d, list.illusts, list.next_url)
            }
        }.onSuccess { (d, list, n) ->
            detail = d
            works = list
            next = n
        }.onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        val user = detail?.user
        val profile = detail?.profile
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixivImage(
                url = user?.avatar(),
                contentDescription = user?.name,
                modifier = Modifier.size(72.dp).clip(CircleShape),
                loader = loader,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
                    Text(user!!.comment!!, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                }
            }
            val followed = user?.is_followed == true
            if (followed) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { graph.client.api.unfollowUser(userId) } }
                        detail = detail?.copy(user = user?.copy(is_followed = false))
                    }
                }) { Text("已关注") }
            } else {
                Button(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { graph.client.api.followUser(userId) } }
                        detail = detail?.copy(user = user?.copy(is_followed = true))
                    }
                }) { Text("关注") }
            }
        }
        IllustWaterfall(
            illusts = works,
            loading = loading,
            error = error,
            loader = loader,
            onOpen = onOpenIllust,
            onLoadMore = {
                val url = next ?: return@IllustWaterfall
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { graph.client.api.nextIllusts(url) }
                    }.onSuccess {
                        works = works + it.illusts
                        next = it.next_url
                    }
                }
            },
        )
    }
}
