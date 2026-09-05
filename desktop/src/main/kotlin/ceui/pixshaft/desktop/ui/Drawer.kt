package ceui.pixshaft.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OfflineBolt
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import coil3.ImageLoader

data class DrawerItem(
    val title: String,
    val dest: Dest,
    val icon: ImageVector,
    val badge: String? = null,
)

data class DrawerSection(
    val title: String,
    val items: List<DrawerItem>,
)

fun drawerSections(): List<DrawerSection> = listOf(
    DrawerSection(
        "我的",
        listOf(
            DrawerItem("我的插画收藏", Dest.Bookmarks, Icons.Outlined.Bookmark),
            DrawerItem("我的小说收藏", Dest.NovelBookmarks, Icons.Outlined.MenuBook),
            DrawerItem("稍后再看", Dest.WatchLater, Icons.Outlined.WatchLater),
            DrawerItem("我置顶的内容", Dest.Pinned, Icons.Outlined.PushPin),
            DrawerItem("精华列", Dest.Feature, Icons.Outlined.Star),
            DrawerItem("追更列表", Dest.Watchlist, Icons.Outlined.Timeline),
            DrawerItem("小说书签", Dest.NovelMarkers, Icons.Outlined.MenuBook),
            DrawerItem("我的关注", Dest.FollowingUsers, Icons.Outlined.People),
            DrawerItem("我的粉丝", Dest.Fans, Icons.Outlined.Person),
        ),
    ),
    DrawerSection(
        "搜索按热度排序",
        listOf(
            DrawerItem("使用情况", Dest.Usage, Icons.Outlined.AutoAwesome, badge = "NEW"),
        ),
    ),
    DrawerSection(
        "记录与管理",
        listOf(
            DrawerItem("浏览记录", Dest.History, Icons.Outlined.History),
            DrawerItem("下载管理", Dest.Queue, Icons.Outlined.Download),
            DrawerItem("离线快照", Dest.Snapshots, Icons.Outlined.OfflineBolt),
            DrawerItem("通知与公告", Dest.Notifications, Icons.Outlined.Notifications),
            DrawerItem("屏蔽记录", Dest.Muted, Icons.Outlined.Block),
            DrawerItem("操作记录", Dest.EventHistory, Icons.Outlined.History),
        ),
    ),
    DrawerSection(
        "其他",
        listOf(
            DrawerItem("设置", Dest.Settings, Icons.Outlined.Settings),
            DrawerItem("网络测试", Dest.NetworkTest, Icons.Outlined.Wifi),
            DrawerItem("画质提升", Dest.Ai, Icons.Outlined.AutoAwesome),
            DrawerItem("以图搜源", Dest.ReverseSearch, Icons.Outlined.ImageSearch),
            DrawerItem("关于", Dest.About, Icons.Outlined.Info),
        ),
    ),
    DrawerSection(
        "试验性",
        listOf(
            DrawerItem("发现", Dest.Discovery, Icons.Outlined.Explore),
            DrawerItem("本地小说", Dest.LocalNovels, Icons.Outlined.Storage),
            DrawerItem("聊天室", Dest.Chat, Icons.Outlined.Chat),
            DrawerItem("广场", Dest.Plaza, Icons.Outlined.Star),
            DrawerItem("批量下载 Debug", Dest.BulkDebug, Icons.Outlined.BugReport),
            DrawerItem("SAF 写入压测", Dest.SafTest, Icons.Outlined.Science),
            DrawerItem("Web 首页", Dest.WebHome, Icons.Outlined.Language),
            DrawerItem("pixiv FANBOX", Dest.Fanbox, Icons.Outlined.MenuBook),
        ),
    ),
)

@Composable
fun AppDrawer(
    visible: Boolean,
    graph: AppGraph,
    loader: ImageLoader,
    onClose: () -> Unit,
    onOpen: (Dest) -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable(onClick = onClose))
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it / 3 } + fadeIn(),
        exit = slideOutHorizontally { -it / 3 } + fadeOut(),
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(328.dp),
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                val me = graph.sessionStore.user
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            me?.id?.let { onOpen(Dest.User(it)) }
                            onClose()
                        }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixivImage(
                        url = me?.avatar(),
                        contentDescription = me?.name,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        loader = loader,
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(me?.name ?: "未登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!me?.account.isNullOrBlank()) "@${me?.account}" else "UID ${me?.id ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.SwapHoriz,
                        contentDescription = "切换账号",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                onOpen(Dest.Accounts)
                                onClose()
                            }
                            .padding(6.dp),
                    )
                }
                drawerSections().forEachIndexed { index, section ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                    }
                    Text(
                        section.title,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    section.items.forEach { item ->
                        DrawerRow(item) {
                            onOpen(item.dest)
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(item: DrawerItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, contentDescription = item.title, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!item.badge.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                Text(
                    item.badge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
fun StubScreen(title: String, detail: String = "桌面版稍后接入这一页，入口已与手机侧栏对齐。") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Construction,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
