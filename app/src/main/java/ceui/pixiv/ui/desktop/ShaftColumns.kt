package ceui.pixiv.ui.desktop

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings

/**
 * Resolves the waterfall column count. Setting `0` ([Settings.LINE_COUNT_AUTO])
 * picks 2–8 columns from the current pane width (~180dp per column). Explicit
 * 2/3/4 values stay as-is so existing users are unchanged.
 */
object ShaftColumns {
    const val TARGET_COLUMN_DP = 180
    const val MIN = 2
    const val MAX = 8

    @JvmStatic
    fun resolved(paneWidthPx: Int, density: Float, setting: Int): Int {
        if (setting != Settings.LINE_COUNT_AUTO) {
            return setting.coerceIn(1, MAX)
        }
        val paneDp = if (density > 0f) paneWidthPx / density else 0f
        return (paneDp / TARGET_COLUMN_DP).toInt().coerceIn(MIN, MAX)
    }

    @JvmStatic
    fun resolvedFromContext(context: Context): Int {
        val dm = context.resources.displayMetrics
        val paneWidthPx = (context.resources.configuration.screenWidthDp * dm.density).toInt()
        return resolved(paneWidthPx, dm.density, Shaft.sSettings.lineCount)
    }

    @JvmStatic
    fun resolvedFromList(list: RecyclerView): Int {
        val laidOut = list.layoutManager?.width?.takeIf { it > 0 }
            ?: list.width.takeIf { it > 0 }
        if (laidOut != null) {
            return resolved(laidOut, list.resources.displayMetrics.density, Shaft.sSettings.lineCount)
        }
        return resolvedFromContext(list.context)
    }

    /** After the list is measured, snap StaggeredGrid spanCount to the resolved value. */
    @JvmStatic
    fun applyTo(list: RecyclerView) {
        val lm = list.layoutManager as? androidx.recyclerview.widget.StaggeredGridLayoutManager
            ?: return
        val apply = Runnable {
            val span = resolvedFromList(list)
            if (lm.spanCount != span) {
                lm.spanCount = span
            }
        }
        if (list.width <= 0) {
            list.post(apply)
        } else {
            apply.run()
        }
    }
}
