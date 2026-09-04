package ceui.pixshaft.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Loopback lock so a second process started by `pixiv://` / `shaft://`
 * forwards the URI to the already-running window instead of opening another.
 *
 * Must [release] on shutdown. If this socket stays open, the next
 * double-click is forwarded to a headless zombie.
 */
object SingleInstance {
    private const val PORT = 17831
    private const val FOCUS = "__FOCUS__"
    private const val ACK = "OK"
    private val uriListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val focusListeners = CopyOnWriteArrayList<() -> Unit>()
    private val claimed = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun claimOrForward(incoming: String?): Boolean {
        if (becomeOwner(incoming)) return true
        val payload = if (incoming.isNullOrBlank()) FOCUS else incoming
        if (forward(payload)) return false
        CrashLog.write("端口 $PORT 无 ACK，尝试回收僵尸实例")
        reclaimStaleOwner()
        Thread.sleep(300)
        if (becomeOwner(incoming)) return true
        CrashLog.write("无法占用单实例端口 $PORT，启动中止")
        return false
    }

    private fun becomeOwner(incoming: String?): Boolean {
        if (!tryBind()) return false
        writePid()
        startAcceptLoop()
        if (!incoming.isNullOrBlank()) {
            thread(name = "pixshaft-initial-uri", isDaemon = true) {
                Thread.sleep(400)
                dispatch(incoming)
            }
        }
        return true
    }

    fun release() {
        if (!claimed.compareAndSet(true, false)) {
            runCatching { server?.close() }
            server = null
            return
        }
        runCatching { server?.close() }
        server = null
        runCatching { Files.deleteIfExists(AppPaths.instancePidFile) }
    }

    fun onUri(listener: (String) -> Unit) {
        uriListeners += listener
    }

    fun removeUri(listener: (String) -> Unit) {
        uriListeners.remove(listener)
    }

    fun onFocus(listener: () -> Unit) {
        focusListeners += listener
    }

    fun removeFocus(listener: () -> Unit) {
        focusListeners.remove(listener)
    }

    private fun tryBind(): Boolean {
        return try {
            val socket = ServerSocket()
            // Do not set reuseAddress: on Windows it allows two listeners on 17831.
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
            server = socket
            claimed.set(true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startAcceptLoop() {
        val socket = server ?: return
        thread(name = "pixshaft-instance", isDaemon = true) {
            while (claimed.get() && !socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 2_000
                        val line = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                            .readLine()
                            ?.trim()
                            .orEmpty()
                        client.getOutputStream().write((ACK + "\n").toByteArray(StandardCharsets.UTF_8))
                        client.getOutputStream().flush()
                        dispatch(line)
                    }
                } catch (_: Exception) {
                    if (!claimed.get() || socket.isClosed) break
                }
            }
        }
    }

    private fun dispatch(line: String) {
        when {
            line.isEmpty() || line == FOCUS -> focusListeners.forEach { runCatching { it() } }
            else -> {
                uriListeners.forEach { runCatching { it(line) } }
                focusListeners.forEach { runCatching { it() } }
            }
        }
    }

    private fun forward(payload: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 800)
                socket.soTimeout = 800
                socket.getOutputStream().write((payload + "\n").toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                val ack = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine()
                    ?.trim()
                ack == ACK
            }
        }.getOrDefault(false)
    }

    private fun reclaimStaleOwner() {
        val pid = readPid() ?: return
        if (pid == ProcessHandle.current().pid()) return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        val cmd = handle.info().command().orElse("")
        if (!cmd.contains("PixShaft", ignoreCase = true)) return
        runCatching { handle.destroy() }
        Thread.sleep(250)
        if (handle.isAlive) runCatching { handle.destroyForcibly() }
        Thread.sleep(250)
    }

    private fun readPid(): Long? {
        return runCatching {
            if (!Files.exists(AppPaths.instancePidFile)) return null
            Files.readString(AppPaths.instancePidFile).trim().toLong()
        }.getOrNull()
    }

    private fun writePid() {
        runCatching {
            Files.createDirectories(AppPaths.root)
            Files.writeString(
                AppPaths.instancePidFile,
                ProcessHandle.current().pid().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }

}
