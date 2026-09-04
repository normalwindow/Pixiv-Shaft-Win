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
    val incoming = args.firstOrNull { it.contains("://") }
    if (!SingleInstance.claimOrForward(incoming)) return
    Runtime.getRuntime().addShutdownHook(Thread({ shutdownAll() }, "pixshaft-shutdown"))
    runCatching { ProtocolRegistrar.registerCurrentProcess() }

    val graph = AppGraph()
    val stopping = AtomicBoolean(false)
    application {
        val prefs = rememberWindowPrefs()
        val icon = painterResource("icon.png")
        val state = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        Window(
            onCloseRequest = {
                if (!stopping.compareAndSet(false, true)) return@Window
                runCatching { prefs.save(state.size.width.value, state.size.height.value) }
                shutdownAll()
                exitApplication()
                // Chromium / Compose can leave non-daemon threads; halt after a short delay.
                Thread({
                    Thread.sleep(400)
                    Runtime.getRuntime().halt(0)
                }, "pixshaft-halt").apply { isDaemon = true }.start()
            },
            title = "PixShaft",
            icon = icon,
            state = state,
        ) {
            val awtWindow = window
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
            ShaftApp(graph, incoming)
        }
    }
}

private val shutdownOnce = AtomicBoolean(false)

private fun shutdownAll() {
    if (!shutdownOnce.compareAndSet(false, true)) return
    runCatching { ChromiumHttp.shutdown() }
    runCatching { SingleInstance.release() }
}
