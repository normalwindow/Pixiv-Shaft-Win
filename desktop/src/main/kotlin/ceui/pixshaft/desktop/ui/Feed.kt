package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ceui.pixshaft.shared.model.Illust
import coil3.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IllustWaterfall(
    illusts: List<Illust>,
    loading: Boolean,
    error: String?,
    loader: ImageLoader,
    onOpen: (Illust) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth.value / 180f).toInt().coerceIn(2, 8)
        val state = rememberLazyStaggeredGridState()
        LaunchedEffect(state, illusts.size) {
            if (onLoadMore == null) return@LaunchedEffect
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .collect { last ->
                    if (illusts.isNotEmpty() && last >= illusts.lastIndex - columns) {
                        onLoadMore()
                    }
                }
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
                    header()
                }
            }
            items(illusts, key = { it.id }) { illust ->
                IllustCard(illust, loader, onOpen)
            }
        }
        when {
            loading && illusts.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            !error.isNullOrBlank() && illusts.isEmpty() -> {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
) {
    val ratio = if (illust.width > 0 && illust.height > 0) {
        (illust.width.toFloat() / illust.height.toFloat()).coerceIn(0.45f, 1.8f)
    } else {
        1f
    }
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen(illust) },
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
            ) {
                PixivImage(
                    url = illust.previewUrl(),
                    contentDescription = illust.title,
                    contentScale = ContentScale.Crop,
                    loader = loader,
                    modifier = Modifier.fillMaxSize(),
                )
                if (illust.page_count > 1 || illust.isR18() || illust.isAi()) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (illust.page_count > 1) BadgeChip("${illust.page_count}P")
                        if (illust.isR18()) BadgeChip("R-18")
                        if (illust.isAi()) BadgeChip("AI")
                    }
                }
            }
            Text(
                illust.title?.ifBlank { "#${illust.id}" } ?: "#${illust.id}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
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
