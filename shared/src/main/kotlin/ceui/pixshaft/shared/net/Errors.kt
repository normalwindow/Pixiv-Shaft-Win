package ceui.pixshaft.shared.net

import retrofit2.HttpException

fun Throwable.userMessage(): String {
    generateSequence(this) { it.cause }.forEach { current ->
        if (current is HttpException) {
            return when (current.code()) {
                400 -> "请求被拒绝。请重试登录，或稍后再试。"
                401, 403 -> "登录已失效，请重新登录。"
                404 -> "内容不存在。"
                429 -> "请求过于频繁，请稍后再试。"
                else -> "服务器返回 ${current.code()}。"
            }
        }
    }
    val msg = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()
    return when {
        "Failed to fetch" in msg -> "换令牌失败：网络被重置或跨域拦截。请确认代理后重试。"
        msg.contains("timeout", ignoreCase = true) || "超时" in msg ->
            "网络超时，请检查代理后重试。"
        msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Connection refused", ignoreCase = true) ->
            "连不上 Pixiv，请打开代理后重试。"
        "端点不存在" in msg -> "OAuth 中间页异常，请重新点「用 Chromium 登录」。"
        else -> msg.replace('\n', ' ').take(400).ifBlank { javaClass.simpleName }
    }
}
