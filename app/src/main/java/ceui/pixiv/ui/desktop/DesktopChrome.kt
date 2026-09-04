package ceui.pixiv.ui.desktop

import android.content.Context
import android.view.View

/**
 * Home chrome for large screens: NavigationRail instead of BottomNavigation.
 * The rail id lives in the default `activity_cover.xml` (GONE) so DataBinding
 * always generates `navigationRail`; `layout-w840dp` flips visibility.
 */
object DesktopChrome {
    @JvmStatic
    fun isExpanded(context: Context): Boolean = WindowWidth.isExpanded(context)

    @JvmStatic
    fun isRailVisible(rail: View?): Boolean = rail != null && rail.visibility == View.VISIBLE

    @JvmStatic
    fun shouldUseRail(rail: View?): Boolean = isRailVisible(rail)
}
