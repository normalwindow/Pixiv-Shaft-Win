package ceui.pixshaft.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.zIndex
import ceui.pixshaft.desktop.ui.CustomTitleBar
import ceui.pixshaft.desktop.ui.ResizeEdges
import ceui.pixshaft.desktop.ui.ShaftApp
import ceui.pixshaft.desktop.ui.ShaftTheme
import ceui.pixshaft.desktop.ui.rememberWindowPrefs
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

fun main(args: Array<String>) {
    OAuthSchemes.install()
    CrashLog.installDefaultHandler()
    runCatching { java.nio.file.Files.createDirectories(AppPaths.root) }
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
    application {
        val prefs = rememberWindowPrefs()
        val icon = painterResource("icon.xml")
        val state = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        val windowRef = java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null)
        val customTitleBar = graph.settings.current.customTitleBar
        // undecorated 不能在已显示的窗口上改，必须重建 Window，否则会闪退。
        key(customTitleBar) {
        Window(
            onCloseRequest = {
                if (!stopping.compareAndSet(false, true)) return@Window
                runCatching { windowRef.get()?.isVisible = false }
                runCatching { prefs.save(state.size.width.value, state.size.height.value) }
                exitApplication()
                // Never wait for Chromium on the EDT — that is the close-lag / white-frame bug.
                Thread({
                    shutdownAll()
                    Runtime.getRuntime().halt(0)
                }, "pixshaft-halt").start()
            },
            title = "PixShaft-Win",
            icon = icon,
            state = state,
            undecorated = customTitleBar,
        ) {
            val awtWindow = window
            windowRef.set(awtWindow)

            // 方案 A：DWM 深色标题栏 + 圆角，跟随应用主题（原生标题栏模式）
            val dark = when (graph.settings.current.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                WinNative.applyTitleBarTheme(awtWindow, dark)
                onDispose { }
            }

            DisposableEffect(awtWindow) {
                val focus = {
                    EventQueue.invokeLater {
                        runCatching {
                            awtWindow.isVisible = true
                            awtWindow.extendedState = awtWindow.extendedState and Frame.ICONIFIED.inv()
                            awtWindow.toFront()
                            awtWindow.requestFocus()
                        }
                    }
                }
                SingleInstance.onFocus(focus)
                onDispose { SingleInstance.removeFocus(focus) }
            }

            DisposableEffect(awtWindow) {
                val sync = WindowStateListener {
                    val frame = awtWindow
                    val iconified = frame.extendedState and Frame.ICONIFIED != 0
                    if (iconified) return@WindowStateListener
                    if (state.placement == WindowPlacement.Fullscreen) return@WindowStateListener
                    val maximized = (frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
                    val next = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating
                    if (state.placement != next) state.placement = next
                }
                awtWindow.addWindowStateListener(sync)
                onDispose { awtWindow.removeWindowStateListener(sync) }
            }

            if (customTitleBar) {
                ShaftTheme(
                    themeMode = graph.settings.current.themeMode,
                    accentIndex = graph.settings.current.accentColor,
                ) {
                    val density = LocalDensity.current
                    var overscan by remember { mutableStateOf(java.awt.Insets(0, 0, 0, 0)) }
                    DisposableEffect(awtWindow) {
                        fun refresh() {
                            EventQueue.invokeLater {
                                if (state.placement != WindowPlacement.Fullscreen) {
                                    runCatching { awtWindow.maximizedBounds = WinNative.workAreaOf(awtWindow) }
                                    WinNative.constrainMaximizedToWorkArea(awtWindow)
                                }
                                overscan = WinNative.overscanInsets(awtWindow)
                            }
                        }
                        refresh()
                        val cl = object : ComponentAdapter() {
                            override fun componentResized(e: ComponentEvent) = refresh()
                            override fun componentMoved(e: ComponentEvent) = refresh()
                        }
                        val sl = WindowStateListener { refresh() }
                        awtWindow.addComponentListener(cl)
                        awtWindow.addWindowStateListener(sl)
                        onDispose {
                            awtWindow.removeComponentListener(cl)
                            awtWindow.removeWindowStateListener(sl)
                        }
                    }
                    val fullscreen = state.placement == WindowPlacement.Fullscreen
                    val maximized = state.placement == WindowPlacement.Maximized
                    val topHover = remember { MutableInteractionSource() }
                    val barHover = remember { MutableInteractionSource() }
                    val topHovered by topHover.collectIsHoveredAsState()
                    val barHovered by barHover.collectIsHoveredAsState()
                    var titleReveal by remember { mutableStateOf(false) }
                    LaunchedEffect(fullscreen, topHovered, barHovered) {
                        if (!fullscreen) {
                            titleReveal = false
                            return@LaunchedEffect
                        }
                        if (topHovered || barHovered) {
                            titleReveal = true
                        } else {
                            delay(2200)
                            titleReveal = false
                        }
                    }
                    val closeApp: () -> Unit = {
                        runCatching { prefs.save(state.size.width.value, state.size.height.value) }
                        exitApplication()
                        Thread({
                            if (stopping.compareAndSet(false, true)) shutdownAll()
                            Runtime.getRuntime().halt(0)
                        }, "pixshaft-halt").start()
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(
                                start = with(density) { overscan.left.toDp() },
                                top = with(density) { overscan.top.toDp() },
                                end = with(density) { overscan.right.toDp() },
                                bottom = with(density) { overscan.bottom.toDp() },
                            ),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (!fullscreen) {
                                CustomTitleBar(
                                    window = awtWindow,
                                    title = "PixShaft-Win",
                                    maximized = maximized,
                                    onMinimize = { awtWindow.extendedState = awtWindow.extendedState or Frame.ICONIFIED },
                                    onToggleMaximize = { WindowChrome.toggleMaximize(state) },
                                    onClose = closeApp,
                                )
                            }
                            ShaftApp(graph, incoming, state, Modifier.fillMaxSize())
                        }
                        if (fullscreen) {
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .hoverable(topHover)
                                    .zIndex(20f),
                            )
                            AnimatedVisibility(
                                visible = titleReveal,
                                enter = fadeIn() + slideInVertically { -it },
                                exit = fadeOut() + slideOutVertically { -it },
                                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(21f).hoverable(barHover),
                            ) {
                                CustomTitleBar(
                                    window = awtWindow,
                                    title = "PixShaft-Win",
                                    maximized = false,
                                    onMinimize = {
                                        WindowChrome.applyPlacement(state, WindowPlacement.Floating)
                                        awtWindow.extendedState = awtWindow.extendedState or Frame.ICONIFIED
                                    },
                                    onToggleMaximize = { WindowChrome.toggleMaximize(state) },
                                    onClose = closeApp,
                                )
                            }
                        }
                        ResizeEdges(
                            window = awtWindow,
                            enabled = true,
                            placement = state.placement,
                        )
                    }
                }
            } else {
                ShaftApp(graph, incoming, state, Modifier.fillMaxSize())
            }
        }
        }
    }
}

private val shutdownOnce = AtomicBoolean(false)
@Volatile private var activeGraph: AppGraph? = null

private fun shutdownAll() {
    if (!shutdownOnce.compareAndSet(false, true)) return
    runCatching { activeGraph?.queue?.shutdown() }
    runCatching { ChromiumHttp.shutdown() }
    runCatching { Chromium.nukeAll() }
    runCatching { SingleInstance.release() }
}
