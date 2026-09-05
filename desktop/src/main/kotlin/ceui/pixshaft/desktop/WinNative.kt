package ceui.pixshaft.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.W32APIOptions
import java.awt.Frame
import java.awt.Window

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

    /** 无边框窗口边缘缩放（把按下转成原生 HT 缩放消息）。 */
    fun resize(window: Window, edge: Edge) = send(window, WM_NCLBUTTONDOWN, edge.ht.toLong())

    enum class Edge(val ht: Int) {
        LEFT(10), RIGHT(11), TOP(12), TOPLEFT(13), TOPRIGHT(14), BOTTOM(15), BOTTOMLEFT(16), BOTTOMRIGHT(17)
    }

    private val savedBounds = java.util.WeakHashMap<Frame, java.awt.Rectangle>()

    fun isMaximized(window: Frame): Boolean = isEffectivelyMaximized(window)

    /**
     * 真正的最大化 / 贴靠 / 铺满工作区 / 全屏：这些状态下禁止边缘缩放。
     * 不能只看内部 savedBounds——双击标题栏、Win+↑、拖到顶边都会走系统 MAXIMIZED_BOTH。
     */
    fun isEffectivelyMaximized(window: Frame): Boolean {
        if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) return true
        if (savedBounds.containsKey(window)) return true
        val screen = window.graphicsConfiguration?.bounds
            ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val work = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val b = window.bounds
        fun near(a: java.awt.Rectangle): Boolean =
            kotlin.math.abs(b.x - a.x) <= 2 &&
                kotlin.math.abs(b.y - a.y) <= 2 &&
                kotlin.math.abs(b.width - a.width) <= 6 &&
                kotlin.math.abs(b.height - a.height) <= 6
        return near(work) || near(screen)
    }

    fun shouldBlockEdgeResize(window: Frame): Boolean = isEffectivelyMaximized(window)

    /** 最大化到工作区（不遮挡任务栏）；再点一次还原。优先走系统 MAXIMIZED_BOTH。 */
    fun toggleMaximize(window: Frame) {
        if (isEffectivelyMaximized(window)) {
            window.extendedState = Frame.NORMAL
            savedBounds.remove(window)?.let { window.bounds = it }
        } else {
            savedBounds[window] = window.bounds
            window.extendedState = window.extendedState or Frame.MAXIMIZED_BOTH
        }
    }
}
