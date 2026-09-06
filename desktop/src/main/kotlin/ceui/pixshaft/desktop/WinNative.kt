package ceui.pixshaft.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.W32APIOptions
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.abs

/**
 * Windows 原生窗口小工具（JNA，仅在 Windows 生效，其它平台静默忽略）：
 * - [applyTitleBarTheme]：DWM 深色标题栏 + 圆角（方案 A，跟随应用主题）
 * - [captionDrag] / [captionDoubleClick] / [resize]：无边框窗口的原生拖拽 / 双击最大化 / 边缘缩放（方案 B）
 */
object WinNative {
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntArray, size: Int): Int
    }

    private interface User32 : Library {
        fun SendMessageW(hWnd: Pointer, msg: Int, wParam: Pointer, lParam: Pointer): Int
    }

    private val dwm: DwmApi? by lazy {
        runCatching { Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    }
    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    private const val WM_NCLBUTTONDOWN = 0x00A1
    private const val WM_NCLBUTTONDBLCLK = 0x00A3
    private const val HTCAPTION = 2L

    private fun hwnd(window: Window): Pointer? = runCatching {
        Native.getWindowPointer(window)
    }.getOrNull()

    private fun send(window: Window, msg: Int, wParam: Long) {
        val h = hwnd(window) ?: return
        val u = user32 ?: return
        runCatching { u.SendMessageW(h, msg, Pointer.createConstant(wParam), Pointer.createConstant(0)) }
    }

    /** 跟随应用主题：深 / 浅色系统标题栏 + Win11 圆角。 */
    fun applyTitleBarTheme(window: Window, dark: Boolean) {
        val h = hwnd(window) ?: return
        val d = dwm ?: return
        val v = intArrayOf(if (dark) 1 else 0)
        runCatching { d.DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, v, 4) }
            .onFailure {
                // 部分老版本 Windows 10 属性索引是 19
                runCatching { d.DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, v, 4) }
            }
        runCatching { d.DwmSetWindowAttribute(h, DWMWA_WINDOW_CORNER_PREFERENCE, intArrayOf(DWMWCP_ROUND), 4) }
    }

    /** 让窗口进入系统级拖动（等价于按住原生标题栏，支持 Win11 贴靠）。 */
    fun captionDrag(window: Window) = send(window, WM_NCLBUTTONDOWN, HTCAPTION)

    /** 标题栏双击：最大化 / 还原。 */
    fun captionDoubleClick(window: Window) = send(window, WM_NCLBUTTONDBLCLK, HTCAPTION)

    /** 无边框窗口边缘缩放（把按下转成原生 HT 缩放消息）。最大化 / 全屏时调用是 no-op。 */
    fun resize(window: Window, edge: Edge) {
        val frame = window as? Frame ?: return
        if (shouldBlockEdgeResize(frame)) return
        send(window, WM_NCLBUTTONDOWN, edge.ht.toLong())
    }

    enum class Edge(val ht: Int) {
        LEFT(10), RIGHT(11), TOP(12), TOPLEFT(13), TOPRIGHT(14), BOTTOM(15), BOTTOMLEFT(16), BOTTOMRIGHT(17)
    }

    fun isMaximized(window: Frame): Boolean = isEffectivelyMaximized(window)

    fun isExclusiveFullscreen(window: Window): Boolean =
        runCatching { window.graphicsConfiguration?.device?.fullScreenWindow === window }.getOrDefault(false)

    /**
     * 系统最大化、独占全屏、或窗口已经铺满工作区 / 屏幕时禁止边缘缩放。
     * 半屏贴靠不会命中 [coversDisplay]。
     */
    fun isEffectivelyMaximized(window: Frame): Boolean {
        if (isExclusiveFullscreen(window)) return true
        if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) return true
        return coversDisplay(window.bounds, workAreaOf(window), screenOf(window))
    }

    fun shouldBlockEdgeResize(window: Frame): Boolean =
        isEffectivelyMaximized(window) || (window.extendedState and Frame.ICONIFIED) != 0

    /**
     * Undecorated MAXIMIZED_BOTH on Windows often covers the taskbar. Pin the
     * frame to the monitor work area so maximize behaves like a normal app.
     */
    fun constrainMaximizedToWorkArea(window: Frame) {
        if (isExclusiveFullscreen(window)) return
        if ((window.extendedState and Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) return
        val work = workAreaOf(window)
        runCatching { window.maximizedBounds = work }
        val b = window.bounds
        val drifted =
            abs(b.x - work.x) > 2 ||
                abs(b.y - work.y) > 2 ||
                abs(b.width - work.width) > 2 ||
                abs(b.height - work.height) > 2
        if (drifted) {
            window.bounds = Rectangle(work.x, work.y, work.width, work.height)
        }
    }

    /**
     * Only the ~8px DWM resize border. Taskbar-sized gaps mean the frame covered
     * the taskbar and must be clamped, not painted as a white strip.
     */
    fun overscanInsets(window: Window): java.awt.Insets {
        val frame = window as? Frame ?: return java.awt.Insets(0, 0, 0, 0)
        if (isExclusiveFullscreen(window)) return java.awt.Insets(0, 0, 0, 0)
        if ((frame.extendedState and Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) {
            return java.awt.Insets(0, 0, 0, 0)
        }
        return WindowChrome.overscan(window.bounds, workAreaOf(window), cap = 12)
    }

    /** Prefer [WindowChrome.toggleMaximize] so Compose WindowPlacement stays in sync. */
    fun toggleMaximize(window: Frame) {
        if (isExclusiveFullscreen(window)) {
            runCatching { window.graphicsConfiguration?.device?.fullScreenWindow = null }
        }
        val iconified = window.extendedState and Frame.ICONIFIED
        if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
            window.extendedState = iconified or Frame.NORMAL
        } else {
            window.extendedState = iconified or Frame.MAXIMIZED_BOTH
        }
    }

    fun workAreaOf(window: Window): Rectangle {
        val gc = window.graphicsConfiguration
        if (gc != null) {
            val screen = gc.bounds
            val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
            if (insets != null) {
                return Rectangle(
                    screen.x + insets.left,
                    screen.y + insets.top,
                    screen.width - insets.left - insets.right,
                    screen.height - insets.top - insets.bottom,
                )
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }

    fun screenOf(window: Window): Rectangle =
        window.graphicsConfiguration?.bounds
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds

    /**
     * 窗口是否铺满工作区或整块屏幕（允许数像素的 DWM / DPI 误差）。
     * 半屏贴靠不会命中——宽或高会差出一大截。
     */
    fun coversDisplay(bounds: Rectangle, work: Rectangle, screen: Rectangle, slop: Int = 12): Boolean {
        fun near(a: Rectangle): Boolean =
            abs(bounds.x - a.x) <= slop &&
                abs(bounds.y - a.y) <= slop &&
                abs(bounds.width - a.width) <= slop &&
                abs(bounds.height - a.height) <= slop
        return near(work) || near(screen)
    }
}
