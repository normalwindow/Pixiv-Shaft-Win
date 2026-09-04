package ceui.pixshaft.desktop

import java.nio.file.Files
import java.nio.file.Path

object AppPaths {
    val root: Path by lazy {
        val portableDir = portableDirectory()
        if (portableDir != null) {
            portableDir.resolve("data")
        } else {
            val appData = System.getenv("APPDATA")
            if (!appData.isNullOrBlank()) Path.of(appData, "PixShaft")
            else Path.of(System.getProperty("user.home"), ".pixshaft")
        }
    }

    private fun portableDirectory(): Path? {
        val candidates = LinkedHashSet<Path>()
        ProcessHandle.current().info().command().ifPresent { cmd ->
            runCatching {
                Path.of(cmd).toAbsolutePath().parent?.let { candidates.add(it) }
            }
        }
        runCatching { candidates.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath()) }
        return candidates.firstOrNull { dir ->
            Files.exists(dir.resolve("PixShaft.portable"))
        }
    }

    val sessionFile: Path get() = root.resolve("session.json")
    val windowFile: Path get() = root.resolve("window.json")
    val pkceFile: Path get() = root.resolve("pkce-verifier.txt")
    val instancePidFile: Path get() = root.resolve("instance.pid")
}
