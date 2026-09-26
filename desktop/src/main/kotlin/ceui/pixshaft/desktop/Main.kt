package ceui.pixshaft.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import ceui.pixshaft.desktop.ui.CustomTitleBar
import ceui.pixshaft.desktop.ui.ShaftApp
import ceui.pixshaft.desktop.ui.ShaftTheme
import ceui.pixshaft.desktop.ui.WindowSizePrefs
import ceui.pixshaft.desktop.ui.isShaftDark
import ceui.pixshaft.desktop.window.ChromeWindow
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowStateListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) {
    OAuthSchemes.install()
    CrashLog.installDefaultHandler()
    runCatching { java.nio.file.Files.createDirectories(AppPaths.root) }
    runCatching { Chromium.purgeLegacyProfiles() }
    runCatching {
        val cache = AppPaths.cacheRoot()
        java.nio.file.Files.createDirectories(cache)
        System.setProperty("java.io.tmpdir", cache.toAbsolutePath().toString())
    }
    val incoming = args.firstOrNull { it.contains("://") }
    if (!SingleInstance.claimOrForward(incoming)) return
    Runtime.getRuntime().addShutdownHook(Thread({ shutdownAll() }, "pixshaft-shutdown"))
    runCatching { ProtocolRegistrar.registerCurrentProcess() }

    val graph = AppGraph()
    activeGraph = graph
    if (graph.sessionStore.isLoggedIn) ChromiumHttp.warmAsync()
    val stopping = AtomicBoolean(false)

    // 窗口宿主是普通的 Swing `JFrame`：正文用 Compose 画，窗口顶部那一条是 Compose 标题栏
    // （JBR 的非客户区，见 [ChromeWindow]）；系统标题栏模式下则交给 FlatLaf 装饰。
    // 拖动 / 缩放 / 贴靠 / 最大化动画全部是 Windows 原生能力。
    //
    // 顺序很关键：FlatLaf **必须**在构造任何 Swing 组件之前装好 —— `JFrame` 的构造函数会当场
    // 给 rootPane 装上当时有效的 UI 委托；先 new 窗口再装 FlatLaf，rootPane 会永远是默认的
    // Metal UI，装饰就失效了。
    setupLookAndFeel(
        dark = effectiveDark(graph.settings.current.themeMode),
        windowDecorations = !graph.settings.current.customTitleBar,
    )
    EventQueue.invokeLater {
        runCatching { showWindow(graph, incoming, stopping) }
            .onFailure {
                CrashLog.note("Main.showWindow", it)
                CrashLog.write("启动窗口失败：" + it)
            }
    }
}

/** 装 FlatLaf（系统标题栏模式的外观基础）。 */
internal fun setupLookAndFeel(dark: Boolean, windowDecorations: Boolean) {
    // 自绘标题栏模式下必须关掉 FlatLaf 的窗口装饰：它会插一条自己的标题栏，和 Compose 那条叠起来。
    System.setProperty("flatlaf.useWindowDecorations", windowDecorations.toString())
    runCatching { if (dark) com.formdev.flatlaf.FlatDarkLaf.setup() else com.formdev.flatlaf.FlatLightLaf.setup() }
        .onFailure { CrashLog.note("Main.setupLookAndFeel", it) }
}

/**
 * 有效深色主题：`themeMode` 1=浅色、2=深色，其余跟随系统。
 *
 * 系统主题问 Skiko（`isSystemInDarkTheme()` 背后就是它），保证 Swing 这边的窗口装饰
 * （FlatLaf 外观、JBR 三大键明暗）和 Compose 那边的配色是同一个答案。
 * `skiko-awt` 只是运行时依赖、编译期看不到，所以按名字反射；拿不到就按浅色。
 */
internal fun effectiveDark(themeMode: Int): Boolean = when (themeMode) {
    1 -> false
    2 -> true
    else -> runCatching {
        Class.forName("org.jetbrains.skiko.SystemTheme_awtKt")
            .getMethod("getCurrentSystemTheme")
            .invoke(null)
            ?.toString() == "DARK"
    }.getOrDefault(false)
}

private fun showWindow(graph: AppGraph, incoming: String?, stopping: AtomicBoolean) {
    val prefs = loadPrefs()
    // 内容 lambda 在构造期就要拿到窗口引用（它要读 AWT 的 extendedState / 主动全屏），
    // 所以用 AtomicReference 先建引用、show() 之前填上。
    val windowRef = AtomicReference<ChromeWindow?>(null)
    val onClose: () -> Unit = {
        if (stopping.compareAndSet(false, true)) {
            runCatching {
                windowRef.get()?.floatingSize()?.let { size ->
                    prefs.save(size.first.toFloat(), size.second.toFloat())
                }
            }
            runCatching { windowRef.get()?.dispose() }
            // Never wait for Chromium on the EDT — that is the close-lag / white-frame bug.
            Thread({
                shutdownAll()
                Runtime.getRuntime().halt(0)
            }, "pixshaft-halt").start()
        }
    }

    /**
     * 建窗口并挂 Compose 面板。`deepLink` 只在启动那一次带（重建时不要重复消费）。
     */
    fun build(
        width: Int,
        height: Int,
        location: java.awt.Point?,
        deepLink: String?,
    ): ChromeWindow {
        val window = ChromeWindow(
            title = "PixShaft-Win",
            width = width,
            height = height,
            location = location,
            dark = effectiveDark(graph.settings.current.themeMode),
            customTitleBar = graph.settings.current.customTitleBar,
            onClose = onClose,
        ) { chrome ->
            // 自绘标题栏时，标题栏是一个独立的高度固定的 Compose 面板；
            // 用系统标题栏时它高度为 0，这里加了也看不见。
            chrome.titleBar.add(
                ComposePanel().apply { setContent { TitleBarRoot(graph, windowRef) } },
                BorderLayout.CENTER,
            )
            chrome.body.add(
                ComposePanel().apply { setContent { BodyRoot(graph, deepLink, windowRef) } },
                BorderLayout.CENTER,
            )
        }
        windowRef.set(window)
        return window
    }

    // 「自绘标题栏」开关要换窗口宿主（JBR 的非客户区 / FlatLaf 的窗口装饰都在建窗口时定），
    // 所以设置页改完直接重建：尺寸和位置沿用，正文回到初始页。
    graph.rebuildWindow = {
        // 这个回调是从 Compose 的事件处理里进来的，当场 dispose 掉自己正在派发的组件会踩到
        // Swing 重入；先让当前事件走完再动窗口。
        EventQueue.invokeLater {
            if (stopping.get()) return@invokeLater
            val old = windowRef.get()
            val size = old?.floatingSize()
            val location = runCatching { old?.frame()?.location }.getOrNull()
            runCatching { old?.dispose() }
            build(
                width = size?.first ?: prefs.width.toInt(),
                height = size?.second ?: prefs.height.toInt(),
                location = location,
                deepLink = null,
            ).show()
        }
    }

    build(prefs.width.toInt(), prefs.height.toInt(), location = null, deepLink = incoming).show()
}

/**
 * 标题栏内容。
 *
 * 这条 Compose 内容**就是**系统标题栏（JBR 把客户区顶部这一条划成非客户区）：
 * - 三大键由系统画在右端，我们不自绘；[ChromeWindow] 已经按 `rightInset` 给它留了位置。
 * - 主题色和三大键明暗每帧推给窗口，切主题 / 跟随系统变化都能跟上。
 * - 鼠标事件交回系统（[ceui.pixshaft.desktop.window.ChromeWindow.forceCaptionHitTest]），
 *   否则拖动 / 双击最大化 / 贴靠会被 Compose 的 SkiaLayer 挡成普通客户区。
 */
@Composable
private fun TitleBarRoot(graph: AppGraph, windowRef: AtomicReference<ChromeWindow?>) {
    val window = windowRef.get() ?: return
    ShaftTheme(
        themeMode = graph.settings.current.themeMode,
        accentIndex = graph.settings.current.accentColor,
    ) {
        val dark = isShaftDark(graph.settings.current.themeMode)
        val background = MaterialTheme.colorScheme.surface.toArgb()
        SideEffect { window.applyTitleBarTheme(dark, background) }
        CustomTitleBar(title = "PixShaft-Win", onCaptionPointer = window::forceCaptionHitTest)
    }
}

/** 主体内容。全屏、最大化都由窗口实例驱动；`state` 是给 [ShaftApp] 看的镜像。 */
@Composable
private fun BodyRoot(
    graph: AppGraph,
    incoming: String?,
    windowRef: AtomicReference<ChromeWindow?>,
) {
    val state = rememberWindowState(size = DpSize(1200.dp, 760.dp))
    val window = windowRef.get()
    val frame = window?.frame()
    DisposableEffect(window, frame) {
        if (window == null || frame == null) return@DisposableEffect onDispose { }
        // 最大化 / 还原：只有窗口实例说了算。全屏时别覆盖 placement（那是另一条路）。
        fun syncPlacement() {
            if (state.placement == WindowPlacement.Fullscreen) return
            val now = (frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
            state.placement = if (now) WindowPlacement.Maximized else WindowPlacement.Floating
        }
        val listener = WindowStateListener { syncPlacement() }
        window.onFullscreenChanged = { on ->
            state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
            if (!on) syncPlacement()
        }
        frame.addWindowStateListener(listener)
        onDispose {
            window.onFullscreenChanged = null
            frame.removeWindowStateListener(listener)
        }
    }
    ShaftTheme(
        themeMode = graph.settings.current.themeMode,
        accentIndex = graph.settings.current.accentColor,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ShaftApp(
                graph = graph,
                initialUri = incoming,
                windowState = state,
                modifier = Modifier.fillMaxSize(),
                onToggleFullscreen = { window?.toggleFullscreen() },
            )
        }
    }
}

private fun loadPrefs(): WindowSizePrefs =
    runCatching {
        java.nio.file.Files.readString(AppPaths.windowFile).let {
            com.google.gson.Gson().fromJson(it, WindowSizePrefs::class.java) ?: WindowSizePrefs()
        }
    }.getOrElse { WindowSizePrefs() }.clamp()

private val shutdownOnce = AtomicBoolean(false)
@Volatile private var activeGraph: AppGraph? = null

private fun shutdownAll() {
    if (!shutdownOnce.compareAndSet(false, true)) return
    runCatching { activeGraph?.queue?.shutdown() }
    runCatching { ChromiumHttp.shutdown() }
    runCatching { Chromium.nukeAll() }
    runCatching { SingleInstance.release() }
}
