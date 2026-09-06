package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.zIndex
import ceui.pixshaft.desktop.WinNative
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowStateListener

private val EDGE = 6.dp
private val CORNER = 14.dp
/** 左侧留给侧栏恢复条（8dp）+ 一点余量，最大化时整层不绘制。 */
private val LEFT_DEAD = 16.dp

/**
 * 自绘标题栏（实验性）：拖拽 / Win11 贴靠由 WindowDraggableArea 承担，双击切换最大化，
 * 最小化 / 最大化 / 关闭自绘，颜色完全跟随主题。
 */
@Composable
fun FrameWindowScope.CustomTitleBar(
    window: Window,
    title: String,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    DisposableEffect(window, onToggleMaximize) {
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || e.button != MouseEvent.BUTTON1) return
                val scale = window.graphicsConfiguration?.defaultTransform?.scaleY ?: 1.0
                val bar = (34.0 * scale).toInt()
                val buttons = (42.0 * 3.0 * scale).toInt()
                if (e.y in 0..bar && e.x in 0 until (window.width - buttons)) {
                    onToggleMaximize()
                }
            }
        }
        window.addMouseListener(listener)
        onDispose { window.removeMouseListener(listener) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource("icon.xml"),
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
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
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
        CaptionButton(onClick = onToggleMaximize, description = if (maximized) "还原" else "最大化") {
            Icon(
                if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
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
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
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
 *
 * 设计要点：
 * - 最大化 / 全屏 / 铺满工作区时 **整层不进入组合**，避免 pointerHoverIcon 把边缘变成缩放光标，
 *   也避免透明 fillMaxSize 层挡住侧栏 8dp 恢复条。
 * - 左侧 [LEFT_DEAD] 完全不放热区：侧栏收起后的恢复条永远可点。
 * - 每一条热区在按下瞬间再读一次窗口状态，防止监听器和 Compose placement 之间的竞态。
 * - 父 Box 只负责摆放，热区本身才有 pointerInput，空白处不拦截点击。
 */
@Composable
fun FrameWindowScope.ResizeEdges(
    window: Frame,
    enabled: Boolean,
    placement: WindowPlacement,
) {
    var blocked by remember { mutableStateOf(true) }
    fun refresh() {
        blocked = !enabled ||
            placement == WindowPlacement.Maximized ||
            placement == WindowPlacement.Fullscreen ||
            WinNative.shouldBlockEdgeResize(window)
    }
    DisposableEffect(window, enabled, placement) {
        refresh()
        val cl = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = refresh()
            override fun componentMoved(e: ComponentEvent) = refresh()
        }
        val sl = WindowStateListener { refresh() }
        window.addComponentListener(cl)
        window.addWindowStateListener(sl)
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

    Box(Modifier.fillMaxSize().zIndex(8f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(EDGE)
                .align(Alignment.TopCenter)
                .padding(start = LEFT_DEAD),
        ) {
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.TOP, ns))
            Box(Modifier.weight(1f).fillMaxHeight().edge(WinNative.Edge.TOP, ns))
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.TOPRIGHT, nesw))
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(EDGE)
                .fillMaxHeight()
                .padding(vertical = CORNER)
                .edge(WinNative.Edge.RIGHT, ew),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(EDGE)
                .padding(start = LEFT_DEAD),
        ) {
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.BOTTOM, ns))
            Box(Modifier.weight(1f).fillMaxHeight().edge(WinNative.Edge.BOTTOM, ns))
            Box(Modifier.width(CORNER).fillMaxHeight().edge(WinNative.Edge.BOTTOMRIGHT, nwse))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(CORNER)
                .edge(WinNative.Edge.TOPRIGHT, nesw),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(CORNER)
                .edge(WinNative.Edge.BOTTOMRIGHT, nwse),
        )
    }
}
