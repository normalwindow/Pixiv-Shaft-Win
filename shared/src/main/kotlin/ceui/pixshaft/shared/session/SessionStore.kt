package ceui.pixshaft.shared.session

import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.model.User
import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SessionStore(
    private val file: Path,
    private val gson: Gson = Gson(),
) {
    private val lock = ReentrantLock()

    @Volatile
    var session: StoredSession? = load()
        private set

    val isLoggedIn: Boolean get() = !session?.accessToken.isNullOrBlank()
    val accessToken: String get() = session?.accessToken.orEmpty()
    val refreshToken: String get() = session?.refreshToken.orEmpty()
    val user: User? get() = session?.user

    fun save(session: StoredSession) {
        lock.withLock {
            this.session = session
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, gson.toJson(session))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun clear() {
        lock.withLock {
            session = null
            Files.deleteIfExists(file)
        }
    }

    fun bearerOrEmpty(): String {
        val token = session?.accessToken.orEmpty()
        return if (token.isEmpty()) "" else "Bearer $token"
    }

    private fun load(): StoredSession? {
        if (!Files.exists(file)) return null
        return runCatching {
            gson.fromJson(Files.readString(file), StoredSession::class.java)
        }.getOrNull()
    }
}
