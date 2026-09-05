package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ceui.pixshaft.desktop.DesktopSettings

val LocalDesktopSettings = staticCompositionLocalOf { DesktopSettings() }

/** 瀑布流无极缩放：1f = 默认，Ctrl+滚轮 / 双指捏合调整。 */
val LocalFeedZoom = staticCompositionLocalOf { androidx.compose.runtime.mutableFloatStateOf(1f) }

data class BrowseChrome(
    val selectedId: Long? = null,
    val split: Boolean = false,
)

val LocalBrowseChrome = staticCompositionLocalOf { BrowseChrome() }

fun shaftAccent(index: Int): Color = when (index) {
    1 -> Color(0xFF7C5CFF)
    2 -> Color(0xFF12B5A8)
    3 -> Color(0xFFE85D75)
    4 -> Color(0xFFE8A317)
    5 -> Color(0xFF39C46E)
    6 -> Color(0xFFFF8A3D)
    7 -> Color(0xFFE64A8C)
    else -> Color(0xFF3D7EFF)
}

@Composable
fun ShaftTheme(
    themeMode: Int = 0,
    accentIndex: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val accent = shaftAccent(accentIndex)
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = Color(0xFFF5C842),
            tertiary = Color(0xFF8BE0C8),
            background = Color(0xFF0E0E12),
            surface = Color(0xFF16161C),
            surfaceVariant = Color(0xFF23232C),
            outline = Color(0xFF3A3A46),
            error = Color(0xFFFF6B7A),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = Color(0xFF5AA7B3),
            tertiary = Color(0xFF7C5CFF),
            background = Color(0xFFF4F5F8),
            surface = Color.White,
            surfaceVariant = Color(0xFFEEF0F5),
            outline = Color(0xFFD5D8E0),
            error = Color(0xFFC62840),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun startDestOf(position: String): Dest = when (position) {
    "ranking" -> Dest.Ranking
    "following", "follow" -> Dest.Following
    "search" -> Dest.Search()
    "me" -> Dest.Me
    else -> Dest.Home
}
