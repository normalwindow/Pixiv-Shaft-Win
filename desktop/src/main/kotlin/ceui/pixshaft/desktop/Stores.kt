package ceui.pixshaft.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.net.DesktopClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.file.Files
import java.util.UUID

/** 一个瀑布流页面的加载状态；放进 [FeedStore] 后跨页面切换存活，回来不再重新请求。 */
class FeedState {
    var items by mutableStateOf<List<Illust>?>(null) // null = 从未加载过
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var loadingMore by mutableStateOf(false)
    var reload by mutableStateOf(0)

    fun reset() {
        items = null
        next = null
        error = null
        reload++
    }
}

/**
 * 页面数据缓存：键 = 页面标识。LRU 上限防止无限增长。
 * 缓存只在进程内 —— 重启后自然重新拉取。
 */
class FeedStore(private val cap: Int = 24) {
    private val map = object : LinkedHashMap<String, FeedState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FeedState>?): Boolean =
            size > cap
    }

    fun get(key: String): FeedState = synchronized(map) { map.getOrPut(key) { FeedState() } }

    fun refresh(key: String) {
        synchronized(map) { map[key] }?.reset()
    }
}

/** 搜索历史：本地 JSON，倒序、去重、上限 20。 */
class SearchHistoryStore(private val gson: Gson = Gson()) {
    private val type = object : TypeToken<MutableList<String>>() {}.type
    private val _items = androidx.compose.runtime.mutableStateListOf<String>()

    val items: List<String> get() = _items

    init {
        if (Files.exists(AppPaths.searchHistoryFile)) {
            runCatching {
                gson.fromJson<MutableList<String>>(Files.readString(AppPaths.searchHistoryFile), type)
                    .orEmpty()
                    .forEach { if (it.isNotBlank()) _items += it }
            }
        }
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        _items.removeAll { it.equals(q, ignoreCase = true) }
        _items.add(0, q)
        while (_items.size > 20) _items.removeAt(_items.lastIndex)
        persist()
    }

    fun remove(query: String) {
        _items.removeAll { it == query }
        persist()
    }

    fun clear() {
        _items.clear()
        persist()
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(AppPaths.searchHistoryFile.parent)
            Files.writeString(AppPaths.searchHistoryFile, gson.toJson(_items.toList()))
        }
    }
}

/**
 * 聊天室 WebSocket 客户端（shaft-api-v2 协议，见源项目 docs/ws-chat-integration.md）。
 * 握手需要 HMAC；没有密钥时 connect() 直接回报错误，UI 走只读模式。
 */
class ChatWsClient(
    private val uid: Long,
    private val onFrame: (JsonObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder().pingInterval(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile var globalSendEnabled: Boolean = true
        private set
    @Volatile private var closedByUser = false
    private val ping = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect() {
        closedByUser = false
        if (!ChatProtocol.isSigningReady()) {
            onState("unsigned")
            return
        }
        if (uid <= 0L) {
            onState("failed:not-logged-in")
            return
        }
        val ts = System.currentTimeMillis().toString()
        val sig = ChatProtocol.sign(uid, ts)
        if (sig.isNullOrBlank()) {
            onState("unsigned")
            return
        }
        val base = DesktopClient.CHAT_BASE.removeSuffix("/")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = base + "/api/v1/chat/ws?uid=" + uid + "&ts=" + ts + "&sig=" + sig +
            "&v=" + AppVersion.CHAT_CLIENT_VERSION
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                onState("connecting")
                ping.launch {
                    while (ws === webSocket && !closedByUser) {
                        kotlinx.coroutines.delay(25_000)
                        runCatching { webSocket.send(ChatProtocol.encodePing()) }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
                when (frame.get("kind")?.asString) {
                    "hello" -> {
                        globalSendEnabled = frame.get("global_send_enabled")?.asBoolean ?: true
                        onState(if (globalSendEnabled) "connected" else "send-disabled")
                    }
                    "msg" -> onFrame(frame)
                    "err" -> {
                        val code = frame.get("code")?.asString.orEmpty()
                        val message = frame.get("message")?.takeUnless { it.isJsonNull }?.asString
                        onState("err:" + (message ?: code))
                    }
                    "global_send_state" -> {
                        globalSendEnabled = frame.get("enabled")?.asBoolean ?: globalSendEnabled
                        onState(if (globalSendEnabled) "connected" else "send-disabled")
                    }
                    "pong" -> Unit
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("closed:$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onState("failed:" + t.message.orEmpty())
            }
        })
    }

    /** 发送公共房消息，可选引用回复。 */
    fun sendGlobal(text: String, replyTo: ChatReplyRef? = null, illustId: Long? = null): String? {
        val socket = ws ?: return null
        if (text.isBlank() || text.length > 2048) return null
        if (!globalSendEnabled) return null
        val (id, body) = ChatProtocol.encodeGlobal(text, illustId = illustId, replyTo = replyTo)
        return if (socket.send(body)) id else null
    }

    fun close() {
        closedByUser = true
        runCatching { ws?.close(1000, null) }
        ws = null
    }
}

/** 聊天自动刷新用的公共协程作用域。 */
val ChatPollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 精华列：把一个「栏」收藏下来，对齐源 App 的精华列概念——
 * 每一栏是一条独立瀑布流（某次搜索、某位作者的作品…）。
 */
data class FeatureColumn(
    val kind: String = "", // search / author / following / related / ranking / newest / bookmarks
    val key: String = "", // 搜索词 / 作者 UID / restrict / illust id / ranking mode
    val title: String = "",
) {
    companion object {
        fun search(query: String) = FeatureColumn("search", query, "搜索：$query")
        fun author(uid: Long, name: String) = FeatureColumn("author", uid.toString(), "${name.ifBlank { "user $uid" }} 的作品")
        fun following(restrict: String) = FeatureColumn(
            "following",
            restrict,
            when (restrict) {
                "public" -> "关注（公开）"
                "private" -> "关注（私人）"
                else -> "关注（全部）"
            },
        )
        fun related(id: Long) = FeatureColumn("related", id.toString(), "相关作品 #$id")
        fun ranking(mode: String, label: String) = FeatureColumn("ranking", mode, "排行：$label")
        fun newest() = FeatureColumn("newest", "illust", "最新作品")
        fun bookmarks() = FeatureColumn("bookmarks", "self", "我的收藏")
    }

    fun kindLabel(): String = when (kind) {
        "author" -> "作者作品"
        "search" -> "搜索"
        "following" -> "关注动态"
        "related" -> "相关作品"
        "ranking" -> "排行榜"
        "newest" -> "最新"
        "bookmarks" -> "收藏"
        else -> kind
    }
}

class FeatureColumnStore(
    private val file: java.nio.file.Path,
    private val gson: Gson = Gson(),
) {
    private val type = object : TypeToken<MutableList<FeatureColumn>>() {}.type
    val items = androidx.compose.runtime.mutableStateListOf<FeatureColumn>()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        if (!Files.exists(file)) return
        runCatching {
            gson.fromJson<MutableList<FeatureColumn>>(Files.readString(file), type)
                .orEmpty()
                .filter { it.kind.isNotBlank() && it.key.isNotBlank() }
                .distinctBy { it.kind to it.key }
        }.getOrDefault(emptyList()).forEach { items += it }
    }

    fun contains(column: FeatureColumn): Boolean = items.any { it.kind == column.kind && it.key == column.key }

    fun add(column: FeatureColumn) {
        if (column.kind.isBlank() || column.key.isBlank()) return
        if (contains(column)) return
        items.add(0, column)
        persist()
    }

    fun remove(column: FeatureColumn) {
        items.removeAll { it.kind == column.kind && it.key == column.key }
        persist()
    }

    fun toggle(column: FeatureColumn): Boolean {
        return if (contains(column)) {
            remove(column)
            false
        } else {
            add(column)
            true
        }
    }

    @Synchronized
    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            val snapshot = items.toList().map { FeatureColumn(it.kind, it.key, it.title) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(snapshot))
            Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            // ATOMIC_MOVE 在部分盘符上不可用，退回直接写
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(file, gson.toJson(items.toList().map { FeatureColumn(it.kind, it.key, it.title) }))
            }
        }
    }
}

/** 瀑布流批量选择控制器（批量下载模式）。 */
class BatchSelection {
    var active by androidx.compose.runtime.mutableStateOf(false)
    val ids = androidx.compose.runtime.mutableStateListOf<Long>()

    fun toggle(id: Long) {
        if (ids.contains(id)) ids.remove(id) else ids.add(id)
    }

    fun reset() {
        active = false
        ids.clear()
    }
}

/** 本地已下载作品检测：启动扫描下载目录，下载完成后即时补标。 */
class DownloadedStore(private val graph: AppGraph) {
    var ids by mutableStateOf<Set<Long>>(emptySet())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scan()
    }

    fun scan() {
        scope.launch {
            val found = runCatching { LibraryScanner.scan(graph.settings.current).map { it.id } }
                .getOrDefault(emptyList())
            ids = found.toSet()
        }
    }

    fun mark(id: Long) {
        ids = ids + id
    }
}
