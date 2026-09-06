package ceui.pixshaft.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * Single owner for maximize / fullscreen / restore.
 * Compose [WindowState.placement] is the source of truth; AWT MAXIMIZED_BOTH is
 * applied by the Compose Window itself. Mixing a hand-rolled work-area fill with
 * exclusive fullscreen is what made the caption maximize button look like
 * fullscreen and then white-screen on the next toggle.
 */
object WindowChrome {
    @Volatile
    private var restoreAfterFullscreen: WindowPlacement = WindowPlacement.Floating

    fun hopRequired(from: WindowPlacement, to: WindowPlacement): Boolean =
        (from == WindowPlacement.Maximized && to == WindowPlacement.Fullscreen) ||
            (from == WindowPlacement.Fullscreen && to == WindowPlacement.Maximized)

    fun toggleMaximize(state: WindowState) {
        when (state.placement) {
            WindowPlacement.Fullscreen -> applyPlacement(state, WindowPlacement.Maximized)
            WindowPlacement.Maximized -> applyPlacement(state, WindowPlacement.Floating)
            WindowPlacement.Floating -> applyPlacement(state, WindowPlacement.Maximized)
        }
    }

    fun toggleFullscreen(state: WindowState) {
        if (state.placement == WindowPlacement.Fullscreen) {
            applyPlacement(state, restoreAfterFullscreen)
        } else {
            restoreAfterFullscreen =
                if (state.placement == WindowPlacement.Maximized) WindowPlacement.Maximized
                else WindowPlacement.Floating
            applyPlacement(state, WindowPlacement.Fullscreen)
        }
    }

    fun applyPlacement(state: WindowState, target: WindowPlacement) {
        val from = state.placement
        if (from == target) return
        if (hopRequired(from, target)) {
            state.placement = WindowPlacement.Floating
            SwingUtilities.invokeLater {
                state.placement = target
            }
        } else {
            state.placement = target
        }
    }

    /** Extra pixels of a maximized frame that sit past the work area (DWM overscan). */
    fun overscan(bounds: Rectangle, work: Rectangle, cap: Int = 12): Insets {
        fun clamp(v: Int): Int = max(0, v).coerceAtMost(cap)
        val left = clamp(work.x - bounds.x)
        val top = clamp(work.y - bounds.y)
        val right = clamp(bounds.x + bounds.width - (work.x + work.width))
        val bottom = clamp(bounds.y + bounds.height - (work.y + work.height))
        if (left == 0 && top == 0 && right == 0 && bottom == 0) return Insets(0, 0, 0, 0)
        return Insets(top, left, bottom, right)
    }
}
