package ceui.pixshaft.desktop.window

import ceui.pixshaft.desktop.CrashLog
import com.formdev.flatlaf.FlatLaf
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 应用窗口宿主，支持两种标题栏形态（由设置里的「自绘标题栏」开关决定）。
 *
 * ## 自绘标题栏走 JBR 的 `WindowDecorations`
 *
 * JetBrains Runtime 原生支持「把系统标题栏换成自绘标题栏」，做法是**把标题栏并进客户区**：
 *
 * ```java
 * var bar = JBR.getWindowDecorations().createCustomTitleBar();
 * bar.setHeight(34);
 * JBR.getWindowDecorations().setCustomTitleBar(frame, bar);
 * ```
 *
 * 高度 = 窗口客户区顶部那一条；三大键由系统画在这一条的右端（`controls.visible`），
 * 我们不自绘，只按 `rightInset` 给 Compose 内容留白。拖动、缩放、Aero Snap、Win11 贴靠、
 * 双击最大化、系统菜单、最小化 / 最大化动画全部是原生的 —— 这正是之前几条自绘路线做不到的：
 *
 * - **无边框 + 自己接管窗口过程**：几何由我们和系统同时写，抖动 / 越拖越小 / 飞出屏幕。
 * - **FlatLaf 原生窗口边框 + `fullWindowContent`**：判定拖动要遍历控件树，只在带鼠标监听器
 *   的组件上读 `JComponent.titleBarCaption`；Compose 里唯一带监听器的是 Skiko 的
 *   `SkiaLayer$1`（继承 `java.awt.Canvas`，非 `JComponent`），**无法被标记**，永远判成
 *   普通内容 → `HTCLIENT` → 拖不动。
 *
 * ⚠️ 自绘模式下**必须**关掉 FlatLaf 自己的窗口装饰（[FlatLaf.setUseNativeWindowDecorations]）：
 * 它会再插一条自己的标题栏并把内容区往下顶，和 Compose 这条叠成上下两条。
 * 另外 Compose 的 SkiaLayer 带鼠标监听器，系统默认会把标题栏那条判成普通客户区 ——
 * 所以 Compose 侧每个鼠标事件都要调一次 [forceCaptionHitTest]，拖动才生效。
 *
 * `org.jetbrains.runtime:jbr-api` 提供 `com.jetbrains.JBR` 桥接类（`implementation`，51 KB）；
 * 在非 JBR 的 JRE 上 `JBR.isWindowDecorationsSupported()` 返回 false，自动退回系统标题栏。
 */
class ChromeWindow(
    private val title: String,
    private val width: Int,
    private val height: Int,
    /** 重建窗口时沿用原位置；null 表示居中。 */
    private val location: java.awt.Point? = null,
    /** 启动时的明暗偏好；Compose 首帧后会连主题色一起校正（见 [applyTitleBarTheme]）。 */
    private var dark: Boolean,
    /** 是否自绘标题栏（设置项；为 false 时使用系统标题栏）。 */
    private val customTitleBar: Boolean,
    private val onClose: () -> Unit,
    private val content: (ChromeContent) -> Unit,
) {
    class ChromeContent(
        /** 标题栏容器。自绘模式下由 Compose 填充；系统标题栏模式下高度为 0。 */
        val titleBar: JPanel,
        /** 标题栏以下的全部内容区。 */
        val body: JPanel,
    )

    private lateinit var frame: JFrame
    private var jbrBar: WindowDecorations.CustomTitleBar? = null
    private var titleBarPanel: JPanel? = null
    private var bodyPanel: JPanel? = null

    /** 标题栏右侧让给原生三大键的区域。 */
    private val titleBarInsets = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
    }

    /** 自绘模式是否真正生效（JBR 可用且成功挂上）。 */
    private var customChromeActive: Boolean = false

    /** 全屏（铺满整块屏幕，含任务栏）状态。 */
    var isFullscreen: Boolean = false
        private set

    /** 全屏开 / 关时回调，给 Compose 侧同步 `WindowState.placement`。 */
    var onFullscreenChanged: ((Boolean) -> Unit)? = null

    private var windowedBounds: java.awt.Rectangle? = null
    private var windowedState: Int = Frame.NORMAL
    private var savedRootBorder: javax.swing.border.Border? = null

    /**
     * 全屏 / 退出全屏。
     *
     * 走 AWT 的独占全屏（`GraphicsDevice.fullScreenWindow`）：系统会把窗口铺满整块屏幕
     * （盖住任务栏），并记住原来的位置，退出时自己还原。老的实现是 Compose 的
     * `WindowPlacement.Fullscreen`，换成 Swing 宿主之后这条链断了，所以在这里补上。
     */
    fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        val device = frame.graphicsConfiguration?.device ?: return
        windowedBounds = frame.bounds
        windowedState = frame.extendedState
        frame.extendedState = Frame.NORMAL
        runCatching {
            device.fullScreenWindow = frame
            // Java 只让系统把窗口铺满，AWT 这边的 bounds / insets 还停留在窗口态：
            // 不补这一步，Compose 内容还是原来那块，右边和下面会露出窗口底色（灰条）。
            frame.bounds = device.defaultConfiguration.bounds
            frame.validate()
            fillClientArea()
            frame.repaint()
        }.onFailure { CrashLog.note("ChromeWindow.enterFullscreen", it) }
        isFullscreen = true
        onFullscreenChanged?.invoke(true)
        scheduleBarRefresh()
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        runCatching { frame.graphicsConfiguration?.device?.fullScreenWindow = null }
            .onFailure { CrashLog.note("ChromeWindow.exitFullscreen", it) }
        windowedBounds?.let { frame.bounds = it }
        if (windowedState and Frame.MAXIMIZED_BOTH != 0) frame.extendedState = windowedState
        runCatching {
            savedRootBorder?.let { frame.rootPane.border = it }
            savedRootBorder = null
            // fillClientArea 直接摆过内容面板的 bounds，此时组件算「已布局」，光调 validate()
            // 不会重排 —— 结果窗口缩回去了、内容还留在全屏那块（看起来就像「没恢复尺寸」）。
            frame.contentPane.invalidate()
            frame.rootPane.invalidate()
            frame.validate()
            frame.repaint()
        }
        isFullscreen = false
        onFullscreenChanged?.invoke(false)
        scheduleBarRefresh()
    }

    /**
     * 重挂一次 JBR 标题栏。
     *
     * 三大键的位置是 JBR 按挂载时的窗口尺寸 / insets 算的，窗口尺寸大变（进出全屏）之后
     * 它不会自己重算：按钮会停在旧位置（右侧留白），要 hover 才“跳”回该在的地方。
     * 挂完还要等 AWT 处理完这次 resize，所以丢回 EDT 队列尾部。
     */
    private fun scheduleBarRefresh() {
        EventQueue.invokeLater {
            if (!customChromeActive) return@invokeLater
            runCatching { attachCustomBar(titleBarPanel?.background ?: titleBarColor, dark) }
                .onFailure { CrashLog.note("ChromeWindow.scheduleBarRefresh", it) }
        }
    }

    /** 最大化只许铺到工作区（任务栏留着）。 */
    private fun pinMaximizedBounds() {
        runCatching { frame.maximizedBounds = workAreaOf(frame) }
            .onFailure { CrashLog.note("ChromeWindow.pinMaximizedBounds", it) }
    }

    /**
     * 把 rootPane 和内容面板铺满客户区。
     *
     * 全屏时系统已经让窗口无边框了，但 Java 这边 `frame.insets` 还是窗口态的 7px，
     * `RootPaneLayout` 会按 insets 把内容面板再往里缩一圈 —— 右边 / 下面因此露出一条底色。
     * 这里三件事一起做：清掉 rootPane 边框（insets 归零）、底色对齐主题、bounds 摆平。
     */
    private fun fillClientArea() {
        val root = frame.rootPane
        if (root.border !is javax.swing.border.EmptyBorder) {
            savedRootBorder = root.border
            root.border = javax.swing.BorderFactory.createEmptyBorder()
        }
        root.background = frame.background
        root.setBounds(0, 0, frame.width, frame.height)
        val content = frame.contentPane ?: return
        content.setBounds(0, 0, frame.width, frame.height)
        content.doLayout()
        content.repaint()
    }

    private fun workAreaOf(window: java.awt.Window): java.awt.Rectangle {
        val gc = window.graphicsConfiguration
        val screen = gc?.bounds
        val insets = gc?.let { runCatching { java.awt.Toolkit.getDefaultToolkit().getScreenInsets(it) }.getOrNull() }
        if (screen != null && insets != null) {
            return java.awt.Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom,
            )
        }
        return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }

    fun floatingSize(): Pair<Int, Int>? = when {
        // 全屏 / 最大化时窗口尺寸不是用户想要的尺寸，别写进偏好（否则下次启动铺满屏）。
        isFullscreen -> windowedBounds?.let { it.width to it.height }
        (frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH -> null
        else -> frame.width to frame.height
    }

    fun frame(): JFrame = frame

    fun show() {
        check(SwingUtilities.isEventDispatchThread()) { "ChromeWindow.show() 必须在 EDT 调用" }
        // FlatLaf 只有在用系统标题栏时才该给自己的窗口画标题栏。自绘模式下必须关掉：它会在窗口顶部
        // 再插一条自己的标题栏（还把内容区往下顶），和 Compose 那条叠成上下两条。
        runCatching { FlatLaf.setUseNativeWindowDecorations(!customTitleBar) }
            .onFailure { CrashLog.note("ChromeWindow.setUseNativeWindowDecorations", it) }

        frame = JFrame(title)
        frame.layout = BorderLayout()
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        frame.isResizable = true
        applyWindowIcon()
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) = onClose()
        })

        val wantCustom = customTitleBar && JBR.isWindowDecorationsSupported()
        val titleBar = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = titleBarColor
            preferredSize = Dimension(0, if (wantCustom) TITLE_BAR_HEIGHT else 0)
        }
        titleBarPanel = titleBar
        // 原生三大键画在客户区之上，这里留出它们占用的宽度，Compose 内容才不会被压住。
        titleBar.add(titleBarInsets, BorderLayout.EAST)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = titleBarColor
        }
        bodyPanel = body
        frame.contentPane.add(titleBar, BorderLayout.NORTH)
        frame.contentPane.add(body, BorderLayout.CENTER)
        content(ChromeContent(titleBar, body))

        frame.setSize(width, height)
        location?.let { frame.location = it } ?: frame.setLocationRelativeTo(null)
        // 最大化铺满**工作区**（别盖住任务栏）。跑分屏 / 改缩放时窗口会换 GraphicsConfiguration，
        // 所以每次移动 / 缩放都重新钉一遍。
        pinMaximizedBounds()
        frame.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent?) = pinMaximizedBounds()
            // 全屏期间窗口尺寸还可能变（换屏 / 系统改分辨率），内容面板要重新铺满。
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                if (isFullscreen) fillClientArea()
            }
        })
        // 顺序很重要：**先让窗口可见**（原生 peer 建好、拿到真实 window 句柄），
        // 再挂自绘标题栏。反过来调用时系统标题栏不会被移除，就会出现上下两条标题栏。
        frame.isVisible = true
        // peer 有了才能设 DWM 属性（没 peer 时句柄是空的，调了也不生效）。
        applyImmersiveDarkMode()

        if (wantCustom) {
            runCatching {
                attachCustomBar(titleBarColor, dark)
                diag(
                    "chrome custom=$customTitleBar dark=$dark height=$TITLE_BAR_HEIGHT" +
                        " insets=" + frame.insets +
                        " leftInset=" + runCatching { jbrBar?.leftInset }.getOrDefault(-1f) +
                        " rightInset=" + runCatching { jbrBar?.rightInset }.getOrDefault(-1f) +
                        " style=0x" + java.lang.Long.toHexString(styleOf()),
                )
            }.onFailure { CrashLog.note("ChromeWindow.setCustomTitleBar", it) }
        }

        frame.toFront()
        frame.requestFocus()
    }

    /**
     * 挂上 JBR 的自绘标题栏。
     *
     * **JBR 只在挂载这一刻读 height / 属性**（实测：挂完之后再 `putProperty` 不会重画），
     * 所以换主题、换底色都得重新建一个 bar 挂上去，不能改旧的。
     */
    private fun attachCustomBar(background: java.awt.Color, dark: Boolean) {
        val bar = JBR.getWindowDecorations().createCustomTitleBar()
        // 属性键取自 JBR 文档：`controls.dark` 决定三大键图标的明暗。
        bar.putProperty("controls.dark", dark)
        // 系统会自己画三大键（含 Win11 贴靠），我们不自绘，避免重叠与命中冲突。
        bar.putProperty("controls.visible", true)
        // 三大键那一块的底色也要显式写：只靠 `controls.dark` 时 JBR 会按系统主题猜，
        // 猜错就是深色标题栏右端一块浅色（实测）。
        applyControlColors(bar, background, dark)
        // 高度必须在挂到窗口之前设置（JBR 文档明确要求）。
        bar.setHeight(TITLE_BAR_HEIGHT.toFloat())
        JBR.getWindowDecorations().setCustomTitleBar(frame, bar)
        jbrBar = bar
        customChromeActive = true
        // 把 Compose 标题栏让开系统按钮占用的宽度，否则内容会被按钮压住。
        applyControlInsets(bar)
    }

    /**
     * 任务栏 / Alt-Tab 的窗口图标。
     *
     * Compose 的 `Window(icon = …)` 会自己把图标交给窗口；换成裸 `JFrame` 之后没人设，
     * 任务栏就一直显示 JBR 自带的 JetBrains 图标。这里用打包进 resources 的 `icon.png`
     * 出一组尺寸（Windows 会挑最接近的）。
     */
    private fun applyWindowIcon() {
        runCatching {
            val source = javaClass.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) }
                ?: return
            frame.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
                .map { size -> source.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH) }
        }.onFailure { CrashLog.note("ChromeWindow.applyWindowIcon", it) }
    }

    private fun applyControlInsets(bar: WindowDecorations.CustomTitleBar) {
        val right = runCatching { bar.rightInset }.getOrDefault(0f).toInt()
        if (right <= 0) return
        titleBarInsets.removeAll()
        titleBarInsets.add(javax.swing.Box.createRigidArea(Dimension(right, 0)))
        titleBarInsets.revalidate()
        titleBarInsets.repaint()
    }

    /**
     * 三大键那一块的底色与图标色（JBR 的 `controls.<layer>.<state>` 属性）。
     *
     * `foreground` 的四种状态都写死：只写 `normal` 时，悬停 / 按下的图标色由 JBR 按它自己
     * 判断的明暗去取 —— 取反了就是「悬停时按钮消失」（深底深图标）。
     * 底色只写 `normal` / `inactive`，`hovered` / `pressed` 交给系统，
     * 关闭键的红色悬停高亮才是原生的。
     */
    private fun applyControlColors(
        bar: WindowDecorations.CustomTitleBar,
        background: java.awt.Color,
        dark: Boolean,
    ) {
        val foreground = if (dark) java.awt.Color(0xEC, 0xEC, 0xF0) else java.awt.Color(0x1B, 0x1B, 0x20)
        val inactive = if (dark) java.awt.Color(0x8A, 0x8A, 0x96) else java.awt.Color(0x77, 0x77, 0x80)
        bar.putProperty("controls.background.normal", background)
        bar.putProperty("controls.background.inactive", background)
        bar.putProperty("controls.foreground.normal", foreground)
        bar.putProperty("controls.foreground.hovered", foreground)
        bar.putProperty("controls.foreground.pressed", foreground)
        bar.putProperty("controls.foreground.inactive", inactive)
    }

    fun dispose() {
        frame.isVisible = false
        frame.dispose()
    }

    fun toFront() {
        frame.toFront()
        frame.requestFocus()
    }

    /**
     * Compose 侧每帧把自己看到的主题推过来：标题栏条底色（ARGB）+ 三大键的明暗。
     *
     * 换主题时要**重新挂一次** bar：JBR 只在挂载那一刻读属性（见 [attachCustomBar]）。
     */
    fun applyTitleBarTheme(dark: Boolean, argb: Int) {
        val panel = titleBarPanel ?: return
        val background = java.awt.Color(argb, true)
        // 底色每次都刷：窗口 / 内容面板 / 正文容器的默认底色是 FlatLaf 的灰（#3C3F41），
        // 全屏、贴边这类情况下露出来就是一条灰边。跟着主题色走才不会再看到它。
        val changed = this.dark != dark || panel.background != background
        this.dark = dark
        panel.background = background
        frame.background = background
        frame.contentPane?.background = background
        frame.rootPane?.background = background
        bodyPanel?.background = background
        if (!changed) return
        panel.repaint()
        applyImmersiveDarkMode()
        if (!customChromeActive) return
        runCatching { attachCustomBar(background, dark) }
            .onFailure { CrashLog.note("ChromeWindow.applyTitleBarTheme", it) }
    }
    /**
     * 把标题栏里的鼠标事件交回系统，拖动 / 双击最大化 / Win11 贴靠才生效。
     *
     * 见 JBR 文档：带鼠标监听器的组件（Compose 的 SkiaLayer 就是）默认会被判定成普通客户区，
     * 必须在**每个**鼠标事件里重新声明一次。`client=true` = 应用自己处理，`false` = 交给系统。
     */
    fun forceCaptionHitTest(client: Boolean) {
        runCatching { jbrBar?.forceHitTest(client) }
    }

    /** 标题栏底色：和 Compose 主题的 surface 对齐。 */
    private val titleBarColor: java.awt.Color
        get() = if (dark) DARK_TITLE_BG else LIGHT_TITLE_BG

    /**
     * 告诉 DWM 这是深色窗口（`DWMWA_USE_IMMERSIVE_DARK_MODE`）。
     *
     * FlatLaf 的窗口装饰原本会顺手设这个；自绘模式下装饰关掉了，得自己设 —— 否则窗口边框
     * 和系统画的那部分（三大键的悬停底色就是它）会按浅色主题走，深色标题栏上冒白块。
     */
    private fun applyImmersiveDarkMode() {
        // 没 peer 时 `getWindowPointer` 会抛「Component must be displayable」，这不是错误。
        if (!frame.isDisplayable) return
        val handle = runCatching { com.sun.jna.Native.getWindowPointer(frame) }.getOrNull() ?: return
        val value = com.sun.jna.ptr.IntByReference(if (dark) 1 else 0)
        // 20 = Win10 20H1+，19 = 更老的预览版；旧系统上会返回错误，忽略即可。
        runCatching { dwmapi.DwmSetWindowAttribute(handle, 20, value, 4) }
        runCatching { dwmapi.DwmSetWindowAttribute(handle, 19, value, 4) }
    }

    private interface Dwmapi : com.sun.jna.win32.StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: com.sun.jna.Pointer,
            attribute: Int,
            value: com.sun.jna.ptr.IntByReference,
            size: Int,
        ): Int
    }

    private val dwmapi: Dwmapi by lazy {
        com.sun.jna.Native.load("dwmapi", Dwmapi::class.java)
    }

    private interface User32 : com.sun.jna.win32.StdCallLibrary {
        fun GetWindowLongPtrW(hWnd: com.sun.jna.Pointer, nIndex: Int): Long
    }

    private val user32: User32 by lazy {
        com.sun.jna.Native.load("user32", User32::class.java)
    }

    private fun styleOf(): Long = runCatching {
        user32.GetWindowLongPtrW(com.sun.jna.Native.getWindowPointer(frame), GWL_STYLE)
    }.getOrDefault(0L)

    /** 一行启动诊断（`%APPDATA%\PixShaft\chrome.log`）：JBR 在不同机器上的表现差别很大。 */
    private fun diag(line: String) {
        runCatching {
            java.nio.file.Files.writeString(
                ceui.pixshaft.desktop.AppPaths.root.resolve("chrome.log"),
                System.currentTimeMillis().toString() + " " + line + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    companion object {
        /** 自绘标题栏高度（逻辑像素），和 Compose 侧 `TitleBarHeight` 保持一致。 */
        const val TITLE_BAR_HEIGHT = 34

        private const val MIN_WIDTH = 900
        private const val MIN_HEIGHT = 600

        /** `GetWindowLongPtrW` 的 GWL_STYLE（只为诊断行读窗口样式）。 */
        private const val GWL_STYLE = -16

        /**
         * 首帧之前的兜底底色 —— Compose 首帧会把主题 surface 推过来（见 [applyTitleBarTheme]），
         * 但 JBR 只在挂载那一刻读它，所以这里必须**和 `ui/Theme.kt` 的 surface 一模一样**。
         */
        private val DARK_TITLE_BG: java.awt.Color = java.awt.Color(0x16, 0x16, 0x1C)
        private val LIGHT_TITLE_BG: java.awt.Color = java.awt.Color(0xFF, 0xFF, 0xFF)
    }
}
