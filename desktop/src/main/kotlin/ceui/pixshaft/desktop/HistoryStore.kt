package ceui.pixshaft.desktop

import ceui.pixshaft.shared.model.Illust
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files

class HistoryStore(private val gson: Gson = Gson()) {
    private val type = object : TypeToken<MutableList<Illust>>() {}.type

    @Synchronized
    fun list(): List<Illust> {
        if (!Files.exists(AppPaths.historyFile)) return emptyList()
        return runCatching {
            gson.fromJson<MutableList<Illust>>(Files.readString(AppPaths.historyFile), type)
                .orEmpty()
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(illust: Illust) {
        if (illust.id <= 0L) return
        val next = (listOf(illust) + list().filter { it.id != illust.id }).take(200)
        runCatching {
            Files.createDirectories(AppPaths.historyFile.parent)
            Files.writeString(AppPaths.historyFile, gson.toJson(next))
        }
    }
}
