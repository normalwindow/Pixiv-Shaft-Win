package ceui.pixiv.ui.desktop

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.widget.EditText
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.VActivity
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * Keyboard shortcuts for a computer / external keyboard.
 *
 * J/K next/prev in [VActivity]; F bookmark; Ctrl+S download; Ctrl+F search;
 * Esc close drawer or finish; 1–5 home tabs. Ignored while typing in a text field
 * (Esc still clears / closes).
 */
object DesktopShortcuts {
    @JvmStatic
    fun handle(activity: Activity, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val focused = activity.currentFocus
        val typing = focused is EditText || focused?.onCheckIsTextEditor() == true
        if (typing && event.keyCode != KeyEvent.KEYCODE_ESCAPE) {
            return false
        }

        val ctrl = event.isCtrlPressed
        return when (event.keyCode) {
            KeyEvent.KEYCODE_J -> {
                if (activity is VActivity) {
                    activity.showAdjacentIllust(1)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_K -> {
                if (activity is VActivity) {
                    activity.showAdjacentIllust(-1)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_F -> {
                if (ctrl) {
                    openSearch(activity)
                    true
                } else if (activity is VActivity) {
                    activity.bookmarkCurrent()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_S -> {
                if (ctrl && activity is VActivity) {
                    activity.downloadCurrent()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (activity is MainActivity) {
                    activity.closeDrawerIfOpen()
                } else {
                    activity.finish()
                    true
                }
            }
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            -> {
                if (!ctrl && activity is MainActivity) {
                    activity.selectTab(event.keyCode - KeyEvent.KEYCODE_1)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun openSearch(activity: Activity) {
        if (activity is MainActivity) {
            activity.openSearch()
            return
        }
        val intent = Intent(activity, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SEARCH.key)
        activity.startActivity(intent)
    }
}
