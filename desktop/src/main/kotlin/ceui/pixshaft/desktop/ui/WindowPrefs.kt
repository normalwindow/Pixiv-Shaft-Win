package ceui.pixshaft.desktop.ui

import ceui.pixshaft.desktop.AppPaths
import com.google.gson.Gson
import java.nio.file.Files

/** 窗口尺寸偏好（`%APPDATA%\PixShaft\window.json`）。位置不存：启动时居中，切换形态重建时沿用。 */
data class WindowSizePrefs(
    var width: Float = 1200f,
    var height: Float = 760f,
) {
    fun clamp(): WindowSizePrefs = apply {
        // 启动尺寸保持优雅：既不许小于可读下限，也不许接近全屏。
        width = width.coerceIn(900f, 1600f)
        height = height.coerceIn(600f, 1000f)
    }

    fun save(width: Float, height: Float) {
        this.width = width
        this.height = height
        clamp()
        Files.createDirectories(AppPaths.windowFile.parent)
        Files.writeString(AppPaths.windowFile, Gson().toJson(this))
    }
}
