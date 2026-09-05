package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader

data class ImagePreviewRequest(
    val urls: List<String>,
    val index: Int,
    val title: String? = null,
)

class ImagePreviewHost {
    var request by mutableStateOf<ImagePreviewRequest?>(null)
        private set

    fun open(urls: List<String>, index: Int = 0, title: String? = null) {
        val valid = urls.filter { it.isNotBlank() }
        if (valid.isEmpty()) return
        request = ImagePreviewRequest(valid, index.coerceIn(0, valid.lastIndex), title)
    }

    fun close() {
        request = null
    }

    fun step(delta: Int) {
        val req = request ?: return
        val next = (req.index + delta).coerceIn(0, req.urls.lastIndex)
        if (next != req.index) request = req.copy(index = next)
    }
}

val LocalImagePreview = staticCompositionLocalOf { ImagePreviewHost() }

private const val MAX_SCALE = 8f

@Composable
fun ImagePreviewOverlay(
    request: ImagePreviewRequest,
    loader: ImageLoader,
    onClose: () -> Unit,
) {
    var index by remember(request) { mutableIntStateOf(request.index) }
    var scale by remember(request) { mutableStateOf(1f) }
    var offset by remember(request) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var hintVisible by remember(request) { mutableStateOf(true) }
    var hintTick by remember(request) { mutableIntStateOf(0) }
    LaunchedEffect(hintTick) {
        if (hintTick > 0) {
            kotlinx.coroutines.delay(4000)
            hintVisible = false
        }
    }

    fun poke() {
        hintVisible = true
        hintTick++
    }

    fun zoomTo(centroid: Offset, ratio: Float) {
        poke()
        val newScale = (scale * ratio).coerceIn(1f, MAX_SCALE)
        if (newScale == 1f) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        val r = newScale / scale
        val c = Offset(boxSize.width / 2f, boxSize.height / 2f)
        val u = centroid - c
        // 以指针为中心缩放：保持指针下的内容点在屏幕上不动
        offset = u - (u - offset) * r
        scale = newScale
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEE0B0B10)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(request) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        zoomTo(centroid, zoom)
                        if (scale > 1f) offset += pan
                    }
                }
                .pointerInput(request) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    zoomTo(
                                        Offset(boxSize.width / 2f, boxSize.height / 2f),
                                        if (delta > 0f) 1f / 1.12f else 1.12f,
                                    )
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .clipToBounds(),
        ) {
            PixivImage(
                url = request.urls.getOrNull(index),
                contentDescription = request.title,
                contentScale = ContentScale.Fit,
                loader = loader,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (!request.title.isNullOrBlank()) {
                    Text(
                        request.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
                if (request.urls.size > 1) {
                    Text(
                        "${index + 1} / ${request.urls.size}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
        }

        if (request.urls.size > 1) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(14.dp)
                    .size(44.dp),
            ) {
                IconButton(onClick = { poke(); index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = "上一张",
                        tint = if (index > 0) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(14.dp)
                    .size(44.dp),
            ) {
                IconButton(
                    onClick = { poke(); index = (index + 1).coerceAtMost(request.urls.lastIndex) },
                    enabled = index < request.urls.lastIndex,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "下一张",
                        tint = if (index < request.urls.lastIndex) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Spacer(Modifier.size(10.dp))
                request.urls.forEachIndexed { i, url ->
                    val selected = i == index
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .size(46.dp)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { poke(); index = i },
                    ) {
                        PixivImage(
                            url = url,
                            contentDescription = "第 ${i + 1} 页",
                            contentScale = ContentScale.Crop,
                            loader = loader,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = hintVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
        Text(
            "滚轮缩放 · 拖动平移 · Esc 关闭",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        )
        }
    }
}
