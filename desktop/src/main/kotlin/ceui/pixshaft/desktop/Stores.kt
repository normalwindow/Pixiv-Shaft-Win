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
 * 握手需要 SHAFT_EVENTS_HMAC；没有密钥时 connect() 直接回报错误，UI 走只读模式。
 */
class ChatWsClient(
    private val uid: Long,
    private val onFrame: (JsonObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder().pingInterval(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    fun connect() {
        val secret = System.getenv("SHAFT_EVENTS_HMAC")
        if (secret.isNullOrBlank()) {
            onState("未配置 SHAFT_EVENTS_HMAC，发送已禁用（fork 构建只读）")
            return
        }
        val ts = System.currentTimeMillis().toString()
        val sig = ShaftSign.hmac(uid, ts)
        if (sig.isNullOrBlank()) {
            onState("签名失败")
            return
        }
        val url = "${DesktopClient.CHAT_BASE.removeSuffix("/")}/api/v1/chat/ws?uid=$uid&ts=$ts&sig=$sig&v=1"
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                onState("connecting")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
                when (frame.get("kind")?.asString) {
                    "hello" -> onState("connected")
                    "msg" -> onFrame(frame)
                    "err" -> onState("err:${frame.get("code")?.asString.orEmpty()}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("closed:$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onState("failed:${t.message.orEmpty()}")
            }
        })
    }

    /** 发送公共房消息；帧格式 {kind:"msg", room:"global", client_msg_id, text}。 */
    fun sendGlobal(text: String): String? {
        val socket = ws ?: return null
        if (text.isBlank() || text.length > 2048) return null
        val clientMsgId = UUID.randomUUID().toString()
        val frame = JsonObject().apply {
            addProperty("kind", "msg")
            addProperty("room", "global")
            addProperty("client_msg_id", clientMsgId)
            addProperty("text", text)
        }
        return if (socket.send(gson.toJson(frame))) clientMsgId else null
    }

    fun close() {
        runCatching { ws?.close(1000, null) }
        ws = null
    }
}

/** 聊天自动刷新用的公共协程作用域。 */
val ChatPollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 精华列：本地收藏的作品清单（与 Pixiv 收藏无关）。 */
class IllustListStore(
    private val file: java.nio.file.Path,
    private val gson: Gson = Gson(),
) {
    private val type = object : TypeToken<MutableList<Illust>>() {}.type
    val items = androidx.compose.runtime.mutableStateListOf<Illust>()

    init {
        if (Files.exists(file)) {
            runCatching {
                gson.fromJson<MutableList<Illust>>(Files.readString(file), type).orEmpty().forEach { items += it }
            }
        }
    }

    fun contains(id: Long): Boolean = items.any { it.id == id }

    fun add(illust: Illust) {
        if (contains(illust.id)) return
        items.add(0, illust)
        persist()
    }

    fun remove(id: Long) {
        items.removeAll { it.id == id }
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(items.toList()))
        }
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
