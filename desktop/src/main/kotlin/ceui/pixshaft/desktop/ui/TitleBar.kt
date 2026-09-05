package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.FrameWindowScope
import ceui.pixshaft.desktop.WinNative
import java.awt.Window

private val EDGE = 6.dp
private val CORNER = 14.dp

/**
 * 自绘标题栏（实验性）：拖拽 / Win11 贴靠由 WindowDraggableArea 承担，双击切换最大化，
 * 最小化 / 最大化 / 关闭自绘，颜色完全跟随主题。
 */
@Composable
fun FrameWindowScope.CustomTitleBar(
    window: Window,
    title: String,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource("icon.xml"),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(16.dp),
            tint = Color.Unspecified,
        )
        WindowDraggableArea(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        CaptionButton(onClick = onMinimize, description = "最小化") {
            Icon(Icons.Outlined.HorizontalRule, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        CaptionButton(onClick = onToggleMaximize, description = "最大化 / 还原") {
            Icon(Icons.Outlined.CropSquare, contentDescription = null, modifier = Modifier.size(12.dp))
        }
        CaptionButton(
            onClick = onClose,
            description = "关闭",
            hoverColor = Color(0xFFC42B1C),
            contentColor = Color.White,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun FrameWindowScope.CaptionButton(
    onClick: () -> Unit,
    description: String,
    hoverColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(42.dp)
            .fillMaxHeight()
            .background(if (hovered) hoverColor else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            content()
        }
        // 供辅助功能识别
        Text(
            description,
            color = Color.Transparent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.size(0.dp),
        )
    }
}

/**
 * 无边框窗口边缘缩放。
 * - 最大化 / 全屏 / 贴满工作区时整层不参与命中，避免挡住侧栏恢复条。
 * - 左侧不做整条热区（侧栏 8dp 恢复条在左缘），只保留左上 / 左下角。
 */
@Composable
fun FrameWindowScope.ResizeEdges(window: java.awt.Frame, enabled: Boolean) {
    var blocked by remember { mutableStateOf(WinNative.shouldBlockEdgeResize(window)) }
    DisposableEffect(window) {
        fun refresh() {
            blocked = WinNative.shouldBlockEdgeResize(window)
        }
        val cl = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = refresh()
            override fun componentMoved(e: java.awt.event.ComponentEvent) = refresh()
        }
        val sl = java.awt.event.WindowStateListener { refresh() }
        window.addComponentListener(cl)
        window.addWindowStateListener(sl)
        refresh()
        onDispose {
            window.removeComponentListener(cl)
            window.removeWindowStateListener(sl)
        }
    }
    if (!enabled || blocked) return

    fun Modifier.edge(edge: WinNative.Edge, icon: PointerIcon): Modifier =
        pointerHoverIcon(icon)
            .pointerInput(window to edge) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!WinNative.shouldBlockEdgeResize(window)) {
                        WinNative.resize(window, edge)
                    }
                }
            }

    val ns = PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR))
    val ew = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))
    val nwse = PointerIcon(java.awt.Cursor(java.awt.Cursor.NW_RESIZE_CURSOR))
    val nesw = PointerIcon(java.awt.Cursor(java.awt.Cursor.NE_RESIZE_CURSOR))

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(EDGE).align(Alignment.TopCenter)) {
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.TOPLEFT, nwse))
            Box(Modifier.weight(1f).fillMaxHeight().edge(WinNative.Edge.TOP, ns))
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.TOPRIGHT, nesw))
        }
        // 左侧只留上下角，中间让给侧栏恢复条 / 交互
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = EDGE)
                .width(CORNER)
                .height(CORNER)
                .edge(WinNative.Edge.TOPLEFT, nwse),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = EDGE)
                .width(CORNER)
                .height(CORNER)
                .edge(WinNative.Edge.BOTTOMLEFT, nesw),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(EDGE)
                .fillMaxHeight()
                .edge(WinNative.Edge.RIGHT, ew),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(EDGE),
        ) {
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.BOTTOMLEFT, nesw))
            Box(Modifier.weight(1f).fillMaxHeight().edge(WinNative.Edge.BOTTOM, ns))
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.BOTTOMRIGHT, nwse))
        }
    }
}
