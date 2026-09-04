package ceui.pixshaft.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ceui.pixshaft.desktop.AppPaths
import com.google.gson.Gson
import java.nio.file.Files

data class WindowSizePrefs(
    var width: Float = 1280f,
    var height: Float = 800f,
) {
    fun save(width: Float, height: Float) {
        this.width = width.coerceAtLeast(900f)
        this.height = height.coerceAtLeast(600f)
        Files.createDirectories(AppPaths.windowFile.parent)
        Files.writeString(AppPaths.windowFile, Gson().toJson(this))
    }
}

@Composable
fun rememberWindowPrefs(): WindowSizePrefs = remember {
    runCatching {
        if (Files.exists(AppPaths.windowFile)) {
            Gson().fromJson(Files.readString(AppPaths.windowFile), WindowSizePrefs::class.java)
        } else {
            WindowSizePrefs()
        }
    }.getOrDefault(WindowSizePrefs())
}
