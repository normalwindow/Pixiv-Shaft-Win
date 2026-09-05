package ceui.pixshaft.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceui.pixshaft.shared.model.Illust
import coil3.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

fun Iterable<Illust>.uniqueIllusts(): List<Illust> {
    val seen = HashSet<Long>()
    return buildList {
        for (item in this@uniqueIllusts) {
            if (item.id > 0L && seen.add(item.id)) add(item)
        }
    }
}

private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 3f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IllustWaterfall(
    illusts: List<Illust>,
    loading: Boolean,
    error: String?,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    columnsOverride: Int = 0,
    header: @Composable (() -> Unit)? = null,
    gridKey: Any = Unit,
    onToggleBookmark: ((Illust) -> Unit)? = null,
    onAddBatch: ((Illust) -> Unit)? = null,
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
) {
    val unique = illusts.uniqueIllusts()
    val ui = LocalDesktopSettings.current
    val chrome = LocalBrowseChrome.current
    val zoomState = LocalFeedZoom.current
    val zoom = zoomState.floatValue
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Ctrl+滚轮 / 触控板双指捏合无极缩放。
                // 监听 Initial pass：先于网格的滚动处理并消费事件，缩放时页面不会跟着上下滚。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                zoomState.floatValue = (zoomState.floatValue * (if (delta > 0) 0.92f else 1f / 0.92f))
                                    .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        val gap = if (chrome.split || ui.compactUi) 6.dp else 10.dp
        val pad = if (chrome.split || ui.compactUi) 8.dp else 12.dp
        val columns = when {
            columnsOverride in 1..8 ->
                ((columnsOverride * zoom).roundToInt()).coerceIn(1, 16)
            chrome.split -> (maxWidth.value * zoom / 150f).toInt().coerceIn(1, 4)
            else -> (maxWidth.value * zoom / if (ui.compactUi) 150f else 180f).toInt().coerceIn(1, 12)
        }
        val state = rememberLazyStaggeredGridState()
        LaunchedEffect(state, unique.size, loadingMore) {
            if (onLoadMore == null) return@LaunchedEffect
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .collect { last ->
                    if (!loadingMore && unique.isNotEmpty() && last >= unique.lastIndex - columns) {
                        onLoadMore()
                    }
                }
        }
        if (ui.useStaggeredLayout) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = state,
                contentPadding = PaddingValues(pad),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalItemSpacing = gap,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (header != null) {
                    item(span = StaggeredGridItemSpan.FullLine) { header() }
                }
                items(unique, key = { it.id }) { illust ->
                    IllustCard(
                        illust, loader, onOpen, compact = chrome.split,
                        onToggleBookmark = onToggleBookmark,
                        onAddBatch = onAddBatch,
                        onAddFeature = onAddFeature,
                        onHide = onHide,
                    )
                }
                if (loadingMore) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(pad),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (header != null) {
                    item(span = { GridItemSpan(columns) }) { header() }
                }
                gridItems(unique, key = { it.id }) { illust ->
                    IllustCard(
                        illust, loader, onOpen, forceSquare = true, compact = chrome.split,
                        onToggleBookmark = onToggleBookmark,
                        onAddBatch = onAddBatch,
                        onAddFeature = onAddFeature,
                        onHide = onHide,
                    )
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(columns) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        when {
            loading && unique.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            !error.isNullOrBlank() && unique.isEmpty() -> {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    if (onRetry != null) Button(onClick = onRetry) { Text("重试") }
                }
            }
            !loading && unique.isEmpty() -> {
                Text(
                    "这里还没有作品",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        // 缩放指示器：百分比 + 加减 + 回到默认
        AnimatedVisibility(
            visible = zoom != 1f,
            enter = fadeIn(tween(120)) + slideInVertically(tween(120)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZoomGlyph(Icons.Outlined.Remove, "缩小") {
                        zoomState.floatValue = (zoomState.floatValue - 0.1f).coerceAtLeast(MIN_ZOOM)
                    }
                    Text(
                        "${(zoom * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { zoomState.floatValue = 1f }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    ZoomGlyph(Icons.Outlined.Add, "放大") {
                        zoomState.floatValue = (zoomState.floatValue + 0.1f).coerceAtMost(MAX_ZOOM)
                    }
                    ZoomGlyph(Icons.Outlined.Refresh, "回到默认") {
                        zoomState.floatValue = 1f
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

@Composable
fun RankingStrip(
    illusts: List<Illust>,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
) {
    val unique = illusts.uniqueIllusts()
    if (unique.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("排行速览", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            unique.take(20).forEach { illust ->
                IllustPoster(
                    illust = illust,
                    loader = loader,
                    onOpen = onOpen,
                    modifier = Modifier.width(128.dp).height(176.dp),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun IllustCard(
    illust: Illust,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    forceSquare: Boolean = false,
    compact: Boolean = false,
    onToggleBookmark: ((Illust) -> Unit)? = null,
    onAddBatch: ((Illust) -> Unit)? = null,
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
) {
    val ratio = if (forceSquare) {
        0.78f
    } else if (illust.width > 0 && illust.height > 0) {
        (illust.width.toFloat() / illust.height.toFloat()).coerceIn(0.45f, 1.8f)
    } else {
        1f
    }
    IllustPoster(
        illust = illust,
        loader = loader,
        onOpen = onOpen,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        compact = compact,
        onToggleBookmark = onToggleBookmark,
        onAddBatch = onAddBatch,
        onAddFeature = onAddFeature,
        onHide = onHide,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IllustPoster(
    illust: Illust,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onToggleBookmark: ((Illust) -> Unit)? = null,
    onAddBatch: ((Illust) -> Unit)? = null,
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
) {
    val policy = LocalImagePolicy.current
    val ui = LocalDesktopSettings.current
    val selected = LocalBrowseChrome.current.selectedId == illust.id
    val radius = if (ui.compactUi || compact) 10.dp else 14.dp
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val title = illust.title?.ifBlank { "#${illust.id}" } ?: "#${illust.id}"
    val meta = buildList {
        illust.user?.name?.takeIf { it.isNotBlank() }?.let(::add)
        illust.total_bookmarks?.takeIf { it > 0 }?.let { add("${it} 收藏") }
    }.joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(radius),
        shadowElevation = 0.dp,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(radius))
                else Modifier,
            )
            .onPointerSecondaryPress { menuOpen = true }
            .clickable { onOpen(illust) },
    ) {
        Box(Modifier.fillMaxSize()) {
            PixivImage(
                url = illust.previewUrl(large = policy.largeThumbnail),
                contentDescription = illust.title,
                contentScale = ContentScale.Crop,
                loader = loader,
                modifier = Modifier.fillMaxSize(),
            )
            if (ui.showCardOverlay) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.52f to Color.Transparent,
                            1f to Color(0xD90B0B12),
                        ),
                    ),
                )
            }
            if (illust.page_count > 1 || illust.isR18() || illust.isAi()) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (illust.page_count > 1) BadgeChip("${illust.page_count}P")
                    if (illust.isR18()) BadgeChip("R-18")
                    if (illust.isAi()) BadgeChip("AI")
                }
            }
            // 收藏爱心（可在设置中隐藏）
            if (onToggleBookmark != null && ui.showLikeButton) {
                val heartTint = if (illust.isBookmarked) MaterialTheme.colorScheme.primary else Color.White
                Icon(
                    if (illust.isBookmarked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (illust.isBookmarked) "取消收藏" else "收藏",
                    tint = heartTint,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(5.dp)
                        .clickable { onToggleBookmark(illust) },
                )
            }
            if (ui.showCardOverlay) {
                Column(Modifier.align(Alignment.BottomStart).padding(if (compact) 8.dp else 10.dp)) {
                    Text(
                        title,
                        color = Color.White,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IllustContextMenu(
                illust = illust,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                actions = IllustMenuActions(
                    onBookmark = onToggleBookmark?.let { fn -> ({ fn(illust) }) },
                    onDownload = null,
                    onAddBatch = onAddBatch?.let { fn -> ({ fn(illust) }) },
                    onAddFeature = onAddFeature?.let { fn -> ({ fn(illust) }) },
                    onCopyIllustId = { copyToClipboard(illust.id.toString()) },
                    onOpenInBrowser = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.pixiv.net/artworks/${illust.id}")) } },
                    onHide = onHide?.let { fn -> ({ fn(illust) }) },
                ),
            )
        }
    }
}

/** 右键按下时触发一次动作（桌面右键 = 手机长按）。 */
private fun Modifier.onPointerSecondaryPress(action: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    action()
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

@Composable
private fun BadgeChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
