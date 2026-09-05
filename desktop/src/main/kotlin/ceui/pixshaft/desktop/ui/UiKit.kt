package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

import ceui.pixshaft.shared.model.Illust

/** 复制文本到系统剪贴板。 */
fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * 横向 chip 行：内容超出宽度时，鼠标滚轮直接横向滚动（也支持触摸拖动）。
 * 用于排行榜单、搜索筛选等一排小栏。
 */
@Composable
fun HorizontalWheelRow(
    modifier: Modifier = Modifier,
    reverseScroll: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier
            .horizontalScroll(scroll, reverseScrolling = reverseScroll)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                scroll.dispatchRawDelta(delta * 1.2f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        content = content,
    )
}

/** 一组卡片 / 详情通用的操作菜单项。 */
data class IllustMenuActions(
    val onBookmark: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    val onAddBatch: (() -> Unit)? = null,
    val onAddFeature: (() -> Unit)? = null,
    val onOpenUser: (() -> Unit)? = null,
    val onSearchTag: ((String) -> Unit)? = null,
    val onOpenIllust: (() -> Unit)? = null,
    val onCopyIllustId: (() -> Unit)? = null,
    val onCopyUserId: (() -> Unit)? = null,
    val onOpenInBrowser: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
)

/** 右键（或点击触发锚点）弹出的作品操作菜单，对齐手机版长按菜单。 */
@Composable
fun IllustContextMenu(
    illust: Illust,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: IllustMenuActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.onBookmark?.let {
            DropdownMenuItem(
                text = { Text(if (illust.isBookmarked) "取消收藏" else "❤ 收藏") },
                onClick = { it(); onDismiss() },
            )
        }
        actions.onDownload?.let {
            DropdownMenuItem(text = { Text("下载") }, onClick = { it(); onDismiss() })
        }
        actions.onAddBatch?.let {
            DropdownMenuItem(text = { Text("加入批量下载") }, onClick = { it(); onDismiss() })
        }
        actions.onAddFeature?.let {
            DropdownMenuItem(text = { Text("收入精华列") }, onClick = { it(); onDismiss() })
        }
        actions.onOpenUser?.let {
            DropdownMenuItem(text = { Text("查看画师") }, onClick = { it(); onDismiss() })
        }
        actions.onOpenIllust?.let {
            DropdownMenuItem(text = { Text("查看详情") }, onClick = { it(); onDismiss() })
        }
        actions.onSearchTag?.let { search ->
            illust.tags.orEmpty().take(3).forEach { tag ->
                val name = tag.name.orEmpty()
                if (name.isNotBlank()) {
                    DropdownMenuItem(text = { Text("#$name") }, onClick = { search(name); onDismiss() })
                }
            }
        }
        actions.onCopyIllustId?.let {
            DropdownMenuItem(text = { Text("复制作品 ID") }, onClick = { it(); onDismiss() })
        }
        actions.onCopyUserId?.let {
            illust.user?.id?.let { uid ->
                DropdownMenuItem(text = { Text("复制作者 ID") }, onClick = { it(); onDismiss() })
            }
        }
        actions.onOpenInBrowser?.let {
            DropdownMenuItem(text = { Text("在浏览器打开") }, onClick = { it(); onDismiss() })
        }
        actions.onHide?.let {
            DropdownMenuItem(text = { Text("不感兴趣（隐藏）") }, onClick = { it(); onDismiss() })
        }
    }
}
