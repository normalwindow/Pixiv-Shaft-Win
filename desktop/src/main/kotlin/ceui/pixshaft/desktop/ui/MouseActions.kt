package ceui.pixshaft.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * 鼠标按键的抽象。Compose 的 PointerButtons 有 5 个槽位；
 * 左键在浏览里另有语义（点开 / 选中），所以「返回」和「快捷下载」都不开放左键。
 */
enum class ShaftMouseButton(val label: String) {
    Right("右键"),
    Middle("鼠标中键"),
    Back("后侧键"),
    Forward("前侧键"),
    ;

    companion object {
        /** 瀑布流快捷下载可选按键（0 中键 1 后侧键 2 前侧键）。 */
        val downloadOptions: List<ShaftMouseButton> = listOf(Middle, Back, Forward)

        fun fromDownloadIndex(index: Int): ShaftMouseButton = downloadOptions.getOrElse(index) { Middle }

        /** 详情页返回键（0 右键 1 中键 2 后侧键 3 前侧键）。 */
        fun fromBackIndex(index: Int): ShaftMouseButton = when (index) {
            1 -> Middle
            2 -> Back
            3 -> Forward
            else -> Right
        }
    }
}

/** 把设置里的下标翻译成实际要监听的按键。 */
fun ShaftMouseButton.matches(buttons: androidx.compose.ui.input.pointer.PointerButtons): Boolean = when (this) {
    ShaftMouseButton.Right -> buttons.isSecondaryPressed
    ShaftMouseButton.Middle -> buttons.isTertiaryPressed
    ShaftMouseButton.Back -> buttons.isBackPressed
    ShaftMouseButton.Forward -> buttons.isForwardPressed
}

/**
 * 可配置按键的「点一下触发一次」手势。主要用于详情页返回（默认右键）和瀑布流快捷下载（默认中键）。
 *
 * 走默认（Main）pass 而不是 Initial：[IllustPoster] 的右键菜单会用 pointerInput 消费右键，
 * 详情页里的右键返回因此天然让开「卡片自己处理过的右键」，只有没人接手时才回到上一页。
 *
 * [isEnabled] 用 lambda 而不是 Boolean：鼠标 hover 状态一变就只是这里重新判断一次，
 * 不会带着整页（详情页那一大坨）一起重组。
 *
 * 避让评论输入框：鼠标停在输入框上时 [isEnabled] 返回 false，
 * 右键交给 Compose 自带的文本上下文菜单（剪切 / 复制 / 粘贴）处理。
 */
fun Modifier.mouseButtonClick(
    button: ShaftMouseButton,
    isEnabled: () -> Boolean,
    onClick: () -> Unit,
): Modifier = pointerInput(button, onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press) continue
            if (!button.matches(event.buttons)) continue
            if (event.changes.any { it.isConsumed }) continue
            if (!isEnabled()) continue
            onClick()
            // 消费掉，别让这个键继续往下传（也不希望它同时触发卡片的打开动作）
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * 鼠标是否停在评论输入框一类的文本控件上。
 * 由文本控件自己置位，详情页的右键返回据此避让，别把「粘贴」吃掉。
 */
val LocalTextInputHovered = staticCompositionLocalOf { mutableStateOf(false) }

/** 文本输入框上报 hover 的小工具：`Modifier.trackTextInputHover()`。 */
@Composable
fun Modifier.trackTextInputHover(): Modifier {
    val hovered = LocalTextInputHovered.current
    return this.pointerInput(hovered) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> hovered.value = true
                    PointerEventType.Exit -> hovered.value = false
                    else -> Unit
                }
            }
        }
    }
}

/** 简易 pop：一条居中的小卡片，2.4 秒后自己消失。 */
data class QuickToast(
    val key: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String = "",
    val thumbUrl: String? = null,
    val icon: QuickToastIcon = QuickToastIcon.Download,
)

enum class QuickToastIcon { Download, Done, Remove, Info }

class QuickToastHostState {
    var current by mutableStateOf<QuickToast?>(null)
        private set

    /** 每来一条新提示自增：下载完成之类的后续更新靠它认领「还是我这条」。 */
    var generation by mutableStateOf(0)
        private set

    fun show(toast: QuickToast) {
        generation++
        current = toast
    }

    fun dismiss() {
        current = null
    }

    /** 同一个作品的任务状态更新（下载完成 / 失败、进度）就地改内容，不重播动画。 */
    fun updateFor(illustId: Long, transform: (QuickToast) -> QuickToast) {
        val now = current ?: return
        if (now.key.substringBefore('#') != illustId.toString()) return
        current = transform(now)
    }
}

val LocalQuickToast = staticCompositionLocalOf { QuickToastHostState() }

/** 快捷下载用的 pop 内容 id：`key` 前缀带上作品 ID，方便后续原地更新。 */
fun quickToastKey(illustId: Long): String = "$illustId#${UUID.randomUUID()}"

@Composable
fun QuickToastHost(state: QuickToastHostState, loader: ImageLoader, modifier: Modifier = Modifier) {
    val toast = state.current
    val generation = state.generation
    // 新提示 + 内容每次变动都重新计时，不会被下载进度撑到「刚冒出来就消失」
    LaunchedEffect(generation, toast) {
        if (toast == null) return@LaunchedEffect
        delay(2400)
        if (state.current === toast) state.dismiss()
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(120)) + slideInVertically(tween(140)) { it / 3 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 3 },
        modifier = modifier,
    ) {
        val shown = toast ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
                    if (!shown.thumbUrl.isNullOrBlank()) {
                        PixivImage(
                            url = shown.thumbUrl,
                            contentDescription = null,
                            loader = loader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        )
                    }
                }
                Column(Modifier.width(228.dp)) {
                    Text(
                        shown.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shown.detail.isNotBlank()) {
                        Text(
                            shown.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (shown.icon) {
                                QuickToastIcon.Remove -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (shown.icon) {
                                QuickToastIcon.Done -> Color(0xFF2BB673)
                                QuickToastIcon.Remove -> MaterialTheme.colorScheme.error
                                QuickToastIcon.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                                QuickToastIcon.Download -> MaterialTheme.colorScheme.primary
                            },
                        ),
                )
            }
        }
    }
}
