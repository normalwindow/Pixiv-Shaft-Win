package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ShaftBlue = Color(0xFF3D7EFF)
private val ShaftAmber = Color(0xFFF5C842)

@Composable
fun ShaftTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = ShaftBlue,
            secondary = ShaftAmber,
            background = Color(0xFF121212),
            surface = Color(0xFF1C1C1E),
        )
    } else {
        lightColorScheme(
            primary = ShaftBlue,
            secondary = Color(0xFF8AC6D1),
            background = Color(0xFFF7F7F8),
            surface = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
