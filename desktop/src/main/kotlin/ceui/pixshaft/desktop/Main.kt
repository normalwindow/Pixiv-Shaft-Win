package ceui.pixshaft.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ceui.pixshaft.desktop.ui.CustomTitleBar
import ceui.pixshaft.desktop.ui.ResizeEdges
import ceui.pixshaft.desktop.ui.ShaftApp
import ceui.pixshaft.desktop.ui.ShaftTheme
import ceui.pixshaft.desktop.ui.rememberWindowPrefs
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.atomic.AtomicBoolean

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

            if (customTitleBar) {
                // 方案 B：自绘标题栏（实验性），主题色完全跟随
                var maximized by remember { mutableStateOf(WinNative.isMaximized(awtWindow)) }
                DisposableEffect(awtWindow) {
                    fun sync() {
                        maximized = WinNative.isMaximized(awtWindow) ||
                            state.placement == WindowPlacement.Maximized ||
                            state.placement == WindowPlacement.Fullscreen
                    }
                    val sl = java.awt.event.WindowStateListener { sync() }
                    val cl = object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent) = sync()
                    }
                    awtWindow.addWindowStateListener(sl)
                    awtWindow.addComponentListener(cl)
                    sync()
                    onDispose {
                        awtWindow.removeWindowStateListener(sl)
                        awtWindow.removeComponentListener(cl)
                    }
                }
                ShaftTheme(
                    themeMode = graph.settings.current.themeMode,
                    accentIndex = graph.settings.current.accentColor,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        CustomTitleBar(
                            window = awtWindow,
                            title = "PixShaft-Win",
                            onMinimize = { awtWindow.extendedState = awtWindow.extendedState or Frame.ICONIFIED },
                            onToggleMaximize = {
                                WinNative.toggleMaximize(awtWindow)
                                maximized = WinNative.isMaximized(awtWindow)
                            },
                            onClose = {
                                runCatching { prefs.save(state.size.width.value, state.size.height.value) }
                                exitApplication()
                                Thread({
                                    if (stopping.compareAndSet(false, true)) shutdownAll()
                                    Runtime.getRuntime().halt(0)
                                }, "pixshaft-halt").start()
                            },
                        )
                        ShaftApp(graph, incoming, state)
                    }
                }
                ResizeEdges(
                    awtWindow,
                    enabled = !maximized &&
                        state.placement != WindowPlacement.Maximized &&
                        state.placement != WindowPlacement.Fullscreen,
                )
            } else {
                ShaftApp(graph, incoming, state)
            }
        }
        }
    }
}

private val shutdownOnce = AtomicBoolean(false)

private fun shutdownAll() {
    if (!shutdownOnce.compareAndSet(false, true)) return
    runCatching { ChromiumHttp.shutdown() }
    runCatching { Chromium.nukeAll() }
    runCatching { SingleInstance.release() }
}
