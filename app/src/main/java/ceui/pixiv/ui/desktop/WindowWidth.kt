package ceui.pixiv.ui.desktop

import android.content.Context
import android.view.View

/**
 * Material window-size classes, measured from the **current pane** rather than
 * the physical display. Activity Embedding / split-screen must not use
 * [android.util.DisplayMetrics.widthPixels].
 */
object WindowWidth {
    const val COMPACT_MAX_DP = 599
    const val MEDIUM_MAX_DP = 839

    @JvmStatic
    fun currentDp(context: Context): Int = context.resources.configuration.screenWidthDp

    @JvmStatic
    fun currentDp(view: View): Int {
        val width = view.width
        if (width > 0) {
            val density = view.resources.displayMetrics.density
            if (density > 0f) {
                return (width / density).toInt()
            }
        }
        return currentDp(view.context)
    }

    @JvmStatic
    fun isExpanded(context: Context): Boolean = currentDp(context) >= MEDIUM_MAX_DP + 1

    @JvmStatic
    fun isExpanded(view: View): Boolean = currentDp(view) >= MEDIUM_MAX_DP + 1
}
