package ceui.pixshaft.desktop

import com.google.gson.JsonObject
import java.nio.file.Files
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ChatReplyRef(
    val uid: Long,
    val clientMsgId: String,
    val displayName: String? = null,
    val text: String? = null,
)

/**
 * shaft-api-v2 chat WS helpers, aligned with the Android ChatFrameEncoder /
 * ShaftHmacAuthProvider (see docs/ws-chat-integration.md).
 *
 * HMAC is resolved from SHAFT_EVENTS_HMAC or %APPDATA%/PixShaft/hmac.key.
 * Official Android builds keep the key in native code; fork / desktop builds
 * that don't ship it stay read-only.
 */
object ChatProtocol {
    fun hmacSecret(): String? {
        System.getenv("SHAFT_EVENTS_HMAC")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            Files.readString(AppPaths.hmacFile).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun isSigningReady(): Boolean = !hmacSecret().isNullOrBlank()

    fun sign(uid: Long, ts: String): String? {
        val secret = hmacSecret() ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((uid.toString() + "|" + ts).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun encodeGlobal(
        text: String,
        clientMsgId: String = UUID.randomUUID().toString(),
        illustId: Long? = null,
        replyTo: ChatReplyRef? = null,
    ): Pair<String, String> {
        val body = buildString {
            append("""{"kind":"msg","room":"global","client_msg_id":"""")
            append(escapeJson(clientMsgId))
            append("""","text":"""")
            append(escapeJson(text))
            append('"')
            if (illustId != null) append(""","illust_id":""").append(illustId)
            appendReplyTo(replyTo)
            append('}')
        }
        return clientMsgId to body
    }

    fun encodePing(): String = """{"kind":"ping"}"""

    fun decodeReplyTo(obj: JsonObject?): ChatReplyRef? {
        if (obj == null || !obj.has("uid") || !obj.has("client_msg_id")) return null
        val uid = runCatching { obj.get("uid").asLong }.getOrNull() ?: return null
        val id = obj.get("client_msg_id")?.asString?.takeIf { it.isNotBlank() } ?: return null
        return ChatReplyRef(
            uid = uid,
            clientMsgId = id,
            displayName = obj.get("display_name")?.takeUnless { it.isJsonNull }?.asString,
            text = obj.get("text")?.takeUnless { it.isJsonNull }?.asString,
        )
    }

    private fun StringBuilder.appendReplyTo(ref: ChatReplyRef?) {
        if (ref == null) return
        append(""","reply_to":{"uid":""")
        append(ref.uid)
        append(""","client_msg_id":"""")
        append(escapeJson(ref.clientMsgId))
        append(""""}""")
    }

    fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        val slash = 0x5C.toChar()
        for (c in s) {
            when (c.code) {
                0x5C -> { sb.append(slash); sb.append(slash) }
                0x22 -> { sb.append(slash); sb.append('"') }
                0x0A -> { sb.append(slash); sb.append('n') }
                0x0D -> { sb.append(slash); sb.append('r') }
                0x09 -> { sb.append(slash); sb.append('t') }
                0x08 -> { sb.append(slash); sb.append('b') }
                0x0C -> { sb.append(slash); sb.append('f') }
                else -> if (c.code < 0x20) {
                    sb.append(slash); sb.append('u'); sb.append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
