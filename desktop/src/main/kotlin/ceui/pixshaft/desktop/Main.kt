package ceui.pixshaft.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ceui.pixshaft.desktop.ui.ShaftApp
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
        val icon = painterResource("icon.png")
        val state = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        val windowRef = java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null)
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
        ) {
            val awtWindow = window
            windowRef.set(awtWindow)
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
            ShaftApp(graph, incoming, state)
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
