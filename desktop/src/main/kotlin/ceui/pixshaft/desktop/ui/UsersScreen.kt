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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import ceui.pixshaft.shared.model.UserPreview
import ceui.pixshaft.shared.net.userMessage
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FollowingUsersScreen(
    graph: AppGraph,
    loader: ImageLoader,
    followers: Boolean = false,
    onOpenUser: (Long) -> Unit,
) {
    val uid = graph.sessionStore.user?.id ?: return
    UserListScreen(
        graph = graph,
        loader = loader,
        load = {
            if (followers) graph.client.api.userFollowers(uid) else graph.client.api.followingUsers(uid)
        },
        onOpenUser = onOpenUser,
        key = "$uid:$followers",
    )
}

/** 通用用户列表（任意 load 源：我的关注 / 某用户的关注 / 好P友…）。key 变化时重新加载。 */
@Composable
fun UserListScreen(
    graph: AppGraph,
    loader: ImageLoader,
    load: suspend () -> ceui.pixshaft.shared.model.UserPreviewResponse,
    onOpenUser: (Long) -> Unit,
    key: Any = Unit,
) {
    var items by remember { mutableStateOf<List<UserPreview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { load() } }
            .onSuccess { items = it.user_previews; error = null }
            .onFailure { error = it.userMessage() }
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.user?.id ?: 0L }) { preview ->
                val user = preview.user ?: return@items
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenUser(user.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixivImage(
                        url = user.avatar(),
                        contentDescription = user.name,
                        loader = loader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(user.name ?: "user ${user.id}", style = MaterialTheme.typography.titleMedium)
                        Text("@${user.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        if (!preview.illusts.isNullOrEmpty()) {
                            Text(
                                "${preview.illusts!!.size} 作品",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
