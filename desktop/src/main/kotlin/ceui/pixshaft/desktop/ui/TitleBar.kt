package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 自绘标题栏高度。必须和 `ChromeWindow` 里给 JBR 的 `TITLE_BAR_HEIGHT` 一致。 */
val TitleBarHeight = 34.dp

/**
 * 自绘标题栏。
 *
 * 这条内容**就是**系统标题栏：JBR 把窗口顶部这 [TitleBarHeight] 划成非客户区，
 * 拖动 / 双击最大化 / Win11 贴靠 / 标题栏右键系统菜单由系统完成，三大键也是系统画在右端
 * （`ChromeWindow` 已经按 `rightInset` 留好位置，所以这里**不再自绘三大键**）。
 *
 * 底色用主题的 surface（和推进窗口的那份是同一个值）：ComposePanel 自己会铺一层白底，
 * 透明的话标题栏就是白的。右侧让给三大键的那一段由 Swing 侧同色补上。
 *
 * [onCaptionPointer] 见 [captionHitTest]。
 */
@Composable
fun CustomTitleBar(
    title: String,
    onCaptionPointer: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(MaterialTheme.colorScheme.surface)
            .captionHitTest(onCaptionPointer),
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
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
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
}

/**
 * 把标题栏里的鼠标事件交回系统。
 *
 * Compose 的 SkiaLayer 带鼠标监听器，JBR 默认会因此把标题栏这条判成普通客户区
 * （`HTCLIENT`）—— 拖动、双击最大化、贴靠就全没了。JBR 要求**每个**鼠标事件都重新声明一次
 * （没声明就退回默认判定），所以这里持续把「有没有被 Compose 消费」报回去：
 * 消费了 = 应用自己处理，没消费 = 交给系统当标题栏。
 */
private fun Modifier.captionHitTest(onCaptionPointer: (Boolean) -> Unit): Modifier =
    pointerInput(onCaptionPointer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                onCaptionPointer(event.changes.any { it.isConsumed })
            }
        }
    }
