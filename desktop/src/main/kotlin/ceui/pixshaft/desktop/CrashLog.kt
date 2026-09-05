package ceui.pixshaft.desktop

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant

object CrashLog {
    fun installDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            write(error)
        }
    }

    fun write(error: Throwable) {
        runCatching {
            Files.createDirectories(AppPaths.root)
            val text = buildString {
                append(Instant.now()).append('\n')
                append(error.stackTraceToString()).append('\n')
            }
            Files.writeString(
                AppPaths.root.resolve("crash.log"),
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun write(message: String) {
        write(IllegalStateException(message))
    }
}
