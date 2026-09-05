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
    val historyFile: Path get() = root.resolve("history.json")
    val settingsFile: Path get() = root.resolve("settings.json")
    val accountsFile: Path get() = root.resolve("accounts.json")
    val queueFile: Path get() = root.resolve("download-queue.json")
    val searchHistoryFile: Path get() = root.resolve("search-history.json")

    fun defaultCache(): Path = root.resolve("cache")

    /** Image / Chromium disk cache. Honors settings.json `cachePath` without loading Compose state. */
    fun cacheRoot(override: String? = null): Path {
        val configured = override?.takeIf { it.isNotBlank() } ?: peekCachePath()
        val path = if (!configured.isNullOrBlank()) Path.of(configured) else defaultCache()
        runCatching { Files.createDirectories(path) }
        return path
    }

    fun imageCacheDir(override: String? = null): Path {
        val dir = cacheRoot(override).resolve("images")
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    private fun peekCachePath(): String? {
        if (!Files.exists(settingsFile)) return null
        return runCatching {
            val text = Files.readString(settingsFile)
            val key = "\"cachePath\""
            val start = text.indexOf(key)
            if (start < 0) return null
            val colon = text.indexOf(':', start)
            val firstQuote = text.indexOf('"', colon + 1)
            val secondQuote = text.indexOf('"', firstQuote + 1)
            if (firstQuote < 0 || secondQuote < 0) return null
            text.substring(firstQuote + 1, secondQuote).replace("\\\\", "\\")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clearDirectory(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (path != dir) runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}
