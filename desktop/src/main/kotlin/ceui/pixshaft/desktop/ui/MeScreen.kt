package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import coil3.ImageLoader

/**
 * 「我的」页 —— 桌面化布局：
 * 顶部个人横幅；下方左右两栏（宽屏）/ 单列（窄窗）：左侧快捷操作，右侧分组入口。
 */
@Composable
fun MeScreen(
    graph: AppGraph,
    loader: ImageLoader,
    onOpen: (Dest) -> Unit,
) {
    val me = graph.sessionStore.user
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 个人横幅
        Box(
            Modifier
                .fillMaxWidth()
                .height(172.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                            Color(0xFF1C1C28),
                        ),
                    ),
                ),
        ) {
            PixivImage(
                url = me?.avatar(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                loader = loader,
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixivImage(
                    url = me?.avatar(),
                    contentDescription = me?.name,
                    modifier = Modifier.size(68.dp).clip(CircleShape),
                    loader = loader,
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        me?.name ?: "旅行者",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (!me?.account.isNullOrBlank()) "@${me?.account}" else "UID ${me?.id ?: 0}",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 切换账号
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable {
                            onOpen(Dest.Accounts)
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = "切换账号",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 左：快捷操作
            Column(Modifier.weight(0.32f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "快捷入口",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                MeAction(Icons.Outlined.Home, "个人主页", "我的插画、漫画与资料") {
                    me?.id?.let { onOpen(Dest.User(it)) }
                }
                MeAction(Icons.Outlined.Download, "下载管理", "队列、并发与保存路径") {
                    onOpen(Dest.Queue)
                }
                MeAction(Icons.Outlined.History, "浏览记录", "最近看过的作品") {
                    onOpen(Dest.History)
                }
                MeAction(Icons.Outlined.Person, "我的关注", "关注的画师列表") {
                    onOpen(Dest.FollowingUsers)
                }
                MeAction(Icons.Outlined.Settings, "设置", "主题、网络、下载与缓存") {
                    onOpen(Dest.Settings)
                }
            }
            // 右：分组入口（双列卡片）
            Column(Modifier.weight(0.68f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                drawerSections(tr()).forEach { section ->
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
                    )
                    section.items.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { item ->
                                MeLinkCard(Modifier.weight(1f), item) { onOpen(item.dest) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun MeAction(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MeLinkCard(modifier: Modifier, item: DrawerItem, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                item.icon,
                contentDescription = item.title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!item.badge.isNullOrBlank()) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        item.badge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
