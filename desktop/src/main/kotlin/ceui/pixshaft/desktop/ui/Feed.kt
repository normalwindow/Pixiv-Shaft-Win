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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.BatchSelection
import ceui.pixshaft.desktop.FeatureColumn
import ceui.pixshaft.shared.model.Illust
import coil3.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.floor
import kotlin.math.roundToInt

fun Iterable<Illust>.uniqueIllusts(): List<Illust> {
    val seen = HashSet<Long>()
    return buildList {
        for (item in this@uniqueIllusts) {
            if (item.id > 0L && seen.add(item.id)) add(item)
        }
    }
}

/** 卡片宽高比的安全区间：太扁的横幅和太长的条漫都会把瀑布流拉得没法看。 */
private const val MIN_CARD_RATIO = 0.45f
private const val MAX_CARD_RATIO = 1.8f

/** 等宽网格（非瀑布流）统一用这个比例。 */
private const val SQUARE_CARD_RATIO = 0.78f

/**
 * 卡片宽高比 = 卡片高度 / 宽度。
 *
 * **必须是纯函数**：瀑布流的列分配完全取决于每个条目的高度，
 * 高度一变（比如首帧还没拿到 width/height 时退回 1f，下一帧又变成真实比例），
 * 已经排好的条目就会整体错位。所以这里只依赖 illust 自身的宽高
 * （pixiv 的列表接口一直带这两个字段），不依赖任何布局期才知道的量。
 */
fun illustCardRatio(illust: Illust, forceSquare: Boolean): Float {
    if (forceSquare) return SQUARE_CARD_RATIO
    if (illust.width > 0 && illust.height > 0) {
        return (illust.width.toFloat() / illust.height.toFloat()).coerceIn(MIN_CARD_RATIO, MAX_CARD_RATIO)
    }
    return 1f
}

/**
 * 瀑布流列分配的结果。
 *
 * [lanes] 是每个条目所在的列号（按 index 对齐），[tops] 是它的顶边坐标，
 * [totalHeight] 是整片内容的高度。
 */
data class WaterfallColumns(
    val lanes: IntArray,
    val tops: FloatArray,
    val totalHeight: Float,
) {
    val size: Int get() = lanes.size

    override fun equals(other: Any?): Boolean =
        other is WaterfallColumns && lanes.contentEquals(other.lanes) &&
            tops.contentEquals(other.tops) && totalHeight == other.totalHeight

    override fun hashCode(): Int = (lanes.contentHashCode() * 31 + tops.contentHashCode()) * 31 + totalHeight.hashCode()
}

/**
 * 把条目按「最短列优先」分列，**从 index 0 推到末尾**。
 *
 * 这是修「往回滚排布乱窜」的关键：Compose 的 `LazyStaggeredGrid` 把列分配存在一个
 * **相对当前可见位置滑动**的窗口里，向上滚时窗口上移、上方条目的分配被丢弃，
 * 再按当时的局部信息重算 —— 同一个条目就换了列。
 * 这里改成一次算完、结果只依赖「条目列表 + 列数 + 列宽」，
 * 跟滚动方向、测量顺序完全无关，往上滚和往下滚看到的排布必然一致。
 */
fun assignWaterfallColumns(
    itemCount: Int,
    columnCount: Int,
    columnWidth: Float,
    gap: Float,
    heightOf: (Int) -> Float,
): WaterfallColumns {
    val columns = columnCount.coerceAtLeast(1)
    val lanes = IntArray(itemCount)
    val tops = FloatArray(itemCount)
    if (itemCount == 0) return WaterfallColumns(lanes, tops, 0f)
    val laneHeights = FloatArray(columns)
    for (i in 0 until itemCount) {
        var best = 0
        for (c in 1 until columns) if (laneHeights[c] < laneHeights[best]) best = c
        lanes[i] = best
        tops[i] = laneHeights[best]
        // 列宽 <= 0 说明还没量到宽度，退化成 1:1，至少保证布局是确定的
        val w = if (columnWidth > 0f) columnWidth else 1f
        val h = heightOf(i).coerceAtLeast(1f)
        laneHeights[best] += h + gap
    }
    var maxBottom = 0f
    for (i in 0 until itemCount) {
        val h = heightOf(i).coerceAtLeast(1f)
        maxBottom = maxOf(maxBottom, tops[i] + h)
    }
    return WaterfallColumns(lanes, tops, maxBottom)
}

/** 条目高度 = 列宽 / 宽高比。 */
fun waterfallItemHeight(ratio: Float, columnWidth: Float): Float {
    if (columnWidth <= 0f) return 1f
    return columnWidth / ratio.coerceAtLeast(0.01f)
}

/**
 * 瀑布流内容区：**自己管滚动**，条目位置由 [WaterfallColumns] 算好、用绝对坐标摆。
 *
 * 为什么不直接用 LazyVerticalStaggeredGrid：它的列分配是按测量窗口增量推导的
 * （见 [assignWaterfallColumns] 的注释），向上滚会把上方条目的列分配丢掉再重算，
 * 于是往回滚时排布乱窜。自己摆位置就没有这个问题 —— 位置是 index 的纯函数。
 *
 * 仍然保持懒组合：只有视口（上下各留一屏余量）里的条目才会被 compose。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WaterfallViewport(
    itemCount: Int,
    keyAt: (Int) -> Any,
    ratioAt: (Int) -> Float,
    gap: Float,
    pad: Float,
    zoom: Float,
    requestedColumns: Int,
    invertWheel: Boolean,
    scrollKey: Any,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    onNearEnd: () -> Unit = {},
    onScrolledChange: ((Boolean) -> Unit)? = null,
    contentAt: @Composable (Int) -> Unit,
) {
    // 滚动位置存在模块级缓存里：页面被销毁重建（进详情再退出）之后还能回到原位。
    // 之前用 rememberSaveable，但它存的是「上一次组合时的值」——
    // 滚动只改了本地 state、没回写，于是退出再回来就变成 0（直接到顶）。
    val scrollState = remember(scrollKey) { WaterfallScrollState(ScrollPositions.get(scrollKey)) }
    var nearEnd by remember { mutableStateOf(false) }
    val nearEndFlag = remember { BooleanArray(1) }

    // 缓动循环：滚轮只改 target，current 追着 target 走。
    // 关键在「不重启动画」—— 每来一个滚轮事件就重启一次 tween 的话，
    // 连续滚动会一顿一顿（就是割裂感的来源）。
    LaunchedEffect(scrollKey) {
        while (true) {
            val diff = scrollState.target - scrollState.current
            if (kotlin.math.abs(diff) < 0.5f) {
                if (scrollState.current != scrollState.target) {
                    scrollState.current = scrollState.target
                    ScrollPositions.put(scrollKey, scrollState.target)
                }
                kotlinx.coroutines.delay(32)
                continue
            }
            val next = scrollState.current + diff * 0.45f
            scrollState.current = next
            ScrollPositions.put(scrollKey, next)
            kotlinx.coroutines.delay(16)
        }
    }

    // 组合窗口锚点：只有它跨过 LAZY_STEP 才会重新组合 / 测量。
    // 滚动本身用下面的 graphicsLayer 平移 —— 滚轮每帧只是一次画面平移，
    // 不会每帧把整屏条目重新 measure 一遍（那就是卡顿的来源）。
    val windowBase by remember(scrollKey) {
        derivedStateOf { floor(scrollState.current / LAZY_STEP) * LAZY_STEP }
    }

    Column(modifier.fillMaxSize()) {
        header?.let {
            Box(Modifier.fillMaxWidth()) { it() }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // graphicsLayer 的平移**不会裁剪**，不裁的话卡片会画到上面的工具栏上面去
                .clipToBounds()
                .graphicsLayer { translationY = windowBase - scrollState.current }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Ctrl+滚轮留给无极缩放（父层 Initial pass 会先消费），这里不抢
                            if (event.type != PointerEventType.Scroll) continue
                            if (event.keyboardModifiers.isCtrlPressed) continue
                            val raw = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (raw == 0f) continue
                            val delta = wheelScrollPixels(raw)
                            if (delta == 0f) continue
                            // 往下滚 = 内容往上走 = offset 变大（正数）
                            // Compose 给的 raw 是「内容位移方向」，和 offset 方向相反，这里取反。
                            // 设置里可以反转方向（自然滚动 / 触控板习惯）。
                            val dir = if (invertWheel) 1f else -1f
                            scrollState.target =
                                (scrollState.target + dir * delta).coerceIn(0f, scrollState.maxScroll)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            WaterfallContent(
                itemCount = itemCount,
                keyAt = keyAt,
                ratioAt = ratioAt,
                gap = gap,
                pad = pad,
                zoom = zoom,
                requestedColumns = requestedColumns,
                windowBase = windowBase,
                scrollState = scrollState,
                nearEndFlag = nearEndFlag,
                contentAt = contentAt,
            )
        }
        footer?.let {
            Box(Modifier.fillMaxWidth()) { it() }
        }
    }

    LaunchedEffect(nearEndFlag[0], itemCount, nearEnd) {
        val wanted = nearEndFlag[0]
        if (wanted != nearEnd) nearEnd = wanted
        if (nearEnd && itemCount > 0) onNearEnd()
    }

    // 告诉外面「已经滚起来了没」：页头的 chip 行和工具栏据此收起
    if (onScrolledChange != null) {
        val scrolledNow by remember(scrollKey) {
            derivedStateOf { scrollState.target > SCROLLED_EPSILON }
        }
        LaunchedEffect(scrolledNow) { onScrolledChange(scrolledNow) }
    }
}

/** 超过这个距离就算「滚动中」，用来收起页头 chip 行。 */
private const val SCROLLED_EPSILON = 24f

/** 懒组合锚点的粒度：滚动超过这个距离才重新组合 / 测量一次。 */
private const val LAZY_STEP = 320f

/**
 * 真正摆条目的地方：`SubcomposeLayout` + **按条目 key 做槽位**。
 *
 * 槽位 key 必须是稳定的（这里用 illust id）。用「可见列表下标」当 key 的话，
 * 一滚动整段 key 就错位，每帧都把整屏卡片（含图片）拆了重建 —— 那就是卡顿的来源。
 */
@Composable
private fun WaterfallContent(
    itemCount: Int,
    keyAt: (Int) -> Any,
    ratioAt: (Int) -> Float,
    gap: Float,
    pad: Float,
    zoom: Float,
    requestedColumns: Int,
    windowBase: Float,
    scrollState: WaterfallScrollState,
    nearEndFlag: BooleanArray,
    contentAt: @Composable (Int) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val columnCount = waterfallColumnCount(w.toFloat(), zoom, requestedColumns)
        val columnWidth = ((w - pad * 2f - gap * (columnCount - 1)) / columnCount).coerceAtLeast(1f)
        val heights = FloatArray(itemCount) { i -> waterfallItemHeight(ratioAt(i), columnWidth) }
        val columns = assignWaterfallColumns(
            itemCount = itemCount,
            columnCount = columnCount,
            columnWidth = columnWidth,
            gap = gap,
            heightOf = { i -> heights[i] },
        )

        val contentHeight = pad * 2f + columns.totalHeight
        val maxScroll = (contentHeight - h).coerceAtLeast(0f)
        scrollState.maxScroll = maxScroll
        val scrolled = scrollState.current.coerceIn(0f, maxScroll)
        // 以 windowBase 为基准摆位，剩下的位移由外层 graphicsLayer 补
        val anchor = windowBase
        val composeTop = anchor + pad - h * 1.5f
        val composeBottom = anchor + h * 2.5f

        val itemConstraints = Constraints.fixedWidth(columnWidth.toInt().coerceAtLeast(1))
        val placed = ArrayList<Pair<Placeable, IntArray>>(64)
        for (i in 0 until itemCount) {
            val top = pad + columns.tops[i]
            val bottom = top + heights[i]
            if (bottom < composeTop || top > composeBottom) continue
            // 稳定 key：滚动时同一个条目始终是同一个槽位，不会被拆掉重建
            val placeable = subcompose(keyAt(i)) { contentAt(i) }.firstOrNull()
                ?.measure(itemConstraints) ?: continue
            placed += placeable to intArrayOf(
                (pad + columns.lanes[i] * (columnWidth + gap)).toInt(),
                (top - anchor).toInt(),
            )
        }

        nearEndFlag[0] = isNearEnd(columns, heights, 0f, pad, scrolled, h.toFloat(), itemCount)
        layout(w, h) {
            placed.forEach { (placeable, pos) -> placeable.place(pos[0], pos[1]) }
        }
    }
}



/** 每个页面 key 记住的滚动位置（进程内，页面销毁重建后还能回到原位）。 */
private object ScrollPositions {
    private val map = HashMap<Any, Float>()
    fun get(key: Any): Float = map[key] ?: 0f
    fun put(key: Any, value: Float) { map[key] = value }
}

/**
 * 瀑布流的滚动状态：`target` 是滚轮直接改的目标值，`current` 是缓动追随值。
 * 分开是为了让连续滚轮事件「叠加位移」而不是「重启动画」。
 */
private class WaterfallScrollState(initial: Float) {
    var target by mutableFloatStateOf(initial)
    var current by mutableFloatStateOf(initial)
    var maxScroll: Float = 0f
}

/**
 * 视口底部是否已经越过最后一条 → 「快到尾部」。
 * 独立成普通函数是为了能在 measure 作用域里调用：那里不是 @Composable，
 * 不能写 state、不能起副作用，所以只算，再由外面的 LaunchedEffect 应用。
 */
private fun isNearEnd(
    columns: WaterfallColumns,
    heights: FloatArray,
    headerH: Float,
    pad: Float,
    scrolled: Float,
    viewportH: Float,
    itemCount: Int,
): Boolean {
    if (itemCount <= 0) return false
    val visibleBottom = scrolled + viewportH
    var lastVisible = -1
    for (i in 0 until itemCount) {
        val top = pad + headerH + columns.tops[i]
        if (top <= visibleBottom && top + heights[i] >= scrolled) lastVisible = i
    }
    return lastVisible >= itemCount - 1
}

/** 滚轮 delta → 像素。
 *
 * 正 delta（手指/滚轮往下）= 内容往上走 = offset 变大。
 * 这里**不照抄原始 delta**：Compose Desktop 在不同机器上给的单位差很多
 * （这台机是 ±273，有的是 ±1 行，有的直接给像素），照抄会一格滚一屏。
 * 统一折算成「一格 ≈ [PIXELS_PER_NOTCH] 像素」，粗粒度按比例给。
 */
private const val PIXELS_PER_NOTCH = 110f

/** 单次滚轮事件的位移上限：再快的滚轮也不该一格滚掉一屏。 */
private const val MAX_WHEEL_PIXELS = 330f

internal fun wheelScrollPixels(delta: Float): Float {
    val sign = if (delta >= 0f) 1f else -1f
    val magnitude = kotlin.math.abs(delta)
    if (magnitude == 0f) return 0f
    val pixels = when {
        magnitude <= 3f -> magnitude * PIXELS_PER_NOTCH            // 行数（常见 ±1..3）
        magnitude <= 200f -> (magnitude / 120f) * PIXELS_PER_NOTCH // 120 一格的标准像素值
        else -> (magnitude / 273f) * PIXELS_PER_NOTCH              // 已经放大过的粗粒度值
    }
    return sign * pixels.coerceIn(1f, MAX_WHEEL_PIXELS)
}

/**
 * 瀑布流列数：跟随窗口宽度与 Ctrl+滚轮缩放。
 * 缩放参与列数是有意为之（用户要的就是「缩放改列数」），
 * 真正的稳定性靠「列分配是 index 的纯函数 + 位置只由列宽决定」来保证。
 */
private fun waterfallColumnCount(widthPx: Float, zoom: Float, requested: Int): Int {
    if (requested in 1..16) return requested
    if (widthPx <= 0f) return 1
    // 和拆掉旧网格之前的算法一致：180dp 一列、上限 12
    return (widthPx * zoom / 180f).toInt().coerceIn(1, 12)
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
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
    batch: BatchSelection? = null,
    onBatchDownload: ((List<Illust>) -> Unit)? = null,
    onDownload: ((Illust) -> Unit)? = null,
    onScrolledChange: ((Boolean) -> Unit)? = null,
) {
    val unique = illusts.uniqueIllusts()
    val ui = LocalDesktopSettings.current
    val chrome = LocalBrowseChrome.current
    val zoomState = LocalFeedZoom.current
    val zoom = zoomState.floatValue
    // 缩放提示条：缩放值一变就显示，停手 1.6 秒后自动收起（回到 100% 也收）
    var zoomHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        if (zoom == 1f) {
            zoomHintVisible = false
        } else {
            zoomHintVisible = true
            kotlinx.coroutines.delay(1600)
            zoomHintVisible = false
        }
    }
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
        val requested = when {
            columnsOverride in 1..16 -> columnsOverride
            ui.lineCount in 1..16 -> ui.lineCount
            else -> 0
        }
        val columns = when {
            requested in 1..16 -> requested
            chrome.split -> (maxWidth.value * zoom / 150f).toInt().coerceIn(1, 4)
            else -> (maxWidth.value * zoom / if (ui.compactUi) 150f else 180f).toInt().coerceIn(1, 12)
        }
        val gridState = rememberLazyStaggeredGridState()
        val density = LocalDensity.current
        val gapPx = remember(density, gap) { with(density) { gap.toPx() } }
        val padPx = remember(density, pad) { with(density) { pad.toPx() } }
        if (ui.useStaggeredLayout) {
            // 自算列位的瀑布流：条目位置只依赖 index，往回滚不会再重排列
            WaterfallViewport(
                itemCount = unique.size,
                // 这三个 lambda 都在「可能已经过期」的时机被调用（列表刷新后会缩水），
                // 所以一律用 getOrNull，不许直接下标访问。
                keyAt = { i -> unique.getOrNull(i)?.id ?: "stale:$i" },
                ratioAt = { i -> unique.getOrNull(i)?.let { illustCardRatio(it, forceSquare = false) } ?: 1f },
                gap = gapPx,
                pad = padPx,
                zoom = zoom,
                requestedColumns = requested,
                invertWheel = ui.invertWheelScroll,
                scrollKey = gridKey,
                header = header,
                footer = if (loadingMore) {
                    {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                } else {
                    null
                },
                onNearEnd = { if (!loadingMore && unique.isNotEmpty()) onLoadMore?.invoke() },
                onScrolledChange = onScrolledChange,
            ) { i ->
                // 必须防越界：刷新会把列表清空，而已经排队的 subcompose 内容
                // 可能在列表缩水之后才被组合 —— 直接用 unique[i] 会
                // IndexOutOfBoundsException（实测点刷新必崩，异常发生在组合阶段会直接卡死）。
                val illust = unique.getOrNull(i) ?: return@WaterfallViewport
                IllustCard(
                    illust, loader, onOpen, compact = chrome.split,
                    onToggleBookmark = onToggleBookmark,
                    onAddFeature = onAddFeature,
                    onHide = onHide,
                    batch = batch,
                    onDownload = onDownload,
                )
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
                        onAddFeature = onAddFeature,
                        onHide = onHide,
                        batch = batch,
                        onDownload = onDownload,
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
        // 批量选择操作条
        AnimatedVisibility(
            visible = batch?.active == true && batch.ids.isNotEmpty() && onBatchDownload != null,
            enter = fadeIn(tween(120)) + slideInVertically(tween(120)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "已选 ${batch?.ids?.size ?: 0} 个",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    TextButton(onClick = {
                        onBatchDownload?.invoke(unique.filter { batch?.ids?.contains(it.id) == true })
                    }) { Text("全部下载") }
                    TextButton(onClick = { batch?.reset() }) { Text("退出选择") }
                }
            }
        }
        // 缩放指示器：百分比 + 加减 + 回到默认
        AnimatedVisibility(
            visible = zoomHintVisible,
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
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
    batch: BatchSelection? = null,
    onDownload: ((Illust) -> Unit)? = null,
) {
    val ratio = illustCardRatio(illust, forceSquare)
    IllustPoster(
        illust = illust,
        loader = loader,
        onOpen = onOpen,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        compact = compact,
        onToggleBookmark = onToggleBookmark,
        onAddFeature = onAddFeature,
        onHide = onHide,
        batch = batch,
        onDownload = onDownload,
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
    onAddFeature: ((Illust) -> Unit)? = null,
    onHide: ((Illust) -> Unit)? = null,
    batch: BatchSelection? = null,
    onDownload: ((Illust) -> Unit)? = null,
) {
    val policy = LocalImagePolicy.current
    val ui = LocalDesktopSettings.current
    val downloadedIds = LocalDownloadedIds.current
    val selected = LocalBrowseChrome.current.selectedId == illust.id
    val radius = if (ui.compactUi || compact) 10.dp else 14.dp
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val title = illust.title?.ifBlank { "#${illust.id}" } ?: "#${illust.id}"
    // 中键（按键可配）快捷下载 + 简易 pop 提示
    val quickToast = LocalQuickToast.current
    val quickDownload = Modifier.mouseButtonClick(
        button = ShaftMouseButton.fromDownloadIndex(ui.downloadMouseButton),
        isEnabled = { ui.middleClickDownload && onDownload != null && batch?.active != true },
        onClick = {
            onDownload?.invoke(illust)
            if (ui.quickDownloadToast) {
                quickToast.show(
                    QuickToast(
                        key = quickToastKey(illust.id),
                        title = title,
                        detail = if (downloadedIds.contains(illust.id)) "已在本地库，重新加入下载队列" else "已加入下载队列",
                        thumbUrl = illust.previewUrl(),
                    ),
                )
            }
        },
    )
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
            .then(quickDownload)
            .clickable {
                if (batch?.active == true) batch.toggle(illust.id) else onOpen(illust)
            },
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
            if (illust.page_count > 1 || illust.isR18() || illust.isAi() || downloadedIds.contains(illust.id)) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (downloadedIds.contains(illust.id)) BadgeChip("已下载")
                    if (illust.page_count > 1) BadgeChip("${illust.page_count}P")
                    if (illust.isR18()) BadgeChip("R-18")
                    if (illust.isAi()) BadgeChip("AI")
                }
            }
            if (batch?.active == true) {
                val picked = batch.ids.contains(illust.id)
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .then(
                            if (picked) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            else Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
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
                    onDownload = onDownload?.let { fn -> ({ fn(illust) }) },
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
        color = Color(0xCC000000),
        contentColor = Color.White,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * 瀑布流页通用操作条：左侧自定义内容，右侧精华列 / 多选 / 列数 / 刷新。
 */
@Composable
fun FeedActionRow(
    graph: AppGraph,
    batch: BatchSelection? = null,
    feature: FeatureColumn? = null,
    onRefresh: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    /** 滚动中：留白收窄、图标变小，给内容让高度。 */
    compact: Boolean = false,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 4.dp, top = if (compact) 0.dp else 8.dp),
    leading: @Composable RowScope.() -> Unit = {},
) {
    val iconSize = if (compact) 20.dp else 24.dp
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        if (feature != null) {
            val added = graph.features.contains(feature)
            IconButton(
                onClick = { graph.features.toggle(feature) },
                modifier = if (compact) Modifier.size(32.dp) else Modifier,
            ) {
                Icon(
                    if (added) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (added) "移出精华列" else "收入精华列",
                    tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        if (onDownloadAll != null) {
            IconButton(
                onClick = onDownloadAll,
                modifier = if (compact) Modifier.size(32.dp) else Modifier,
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "全部加入下载队列",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        if (batch != null) {
            IconButton(
                onClick = {
                    batch.active = !batch.active
                    if (!batch.active) batch.reset()
                },
                modifier = if (compact) Modifier.size(32.dp) else Modifier,
            ) {
                Icon(
                    Icons.Outlined.DoneAll,
                    contentDescription = "批量选择",
                    tint = if (batch.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        ColumnCountButton(graph, compact)
        if (onRefresh != null) {
            IconButton(
                onClick = onRefresh,
                modifier = if (compact) Modifier.size(32.dp) else Modifier,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun ColumnCountButton(graph: AppGraph, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val current = graph.settings.current.lineCount
    val iconSize = if (compact) 20.dp else 24.dp
    Box {
        IconButton(
            onClick = { open = true },
            modifier = if (compact) Modifier.size(32.dp) else Modifier,
        ) {
            Icon(
                Icons.Outlined.ViewColumn,
                contentDescription = "列数",
                tint = if (current > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (current == 0) "✓ 自动" else "自动") },
                onClick = {
                    graph.settings.update { it.copy(lineCount = 0) }
                    open = false
                },
            )
            (1..8).forEach { n ->
                DropdownMenuItem(
                    text = { Text(if (current == n) "✓ $n 列" else "$n 列") },
                    onClick = {
                        graph.settings.update { it.copy(lineCount = n) }
                        open = false
                    },
                )
            }
        }
    }
}
