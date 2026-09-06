package ceui.pixshaft.desktop

import ceui.pixshaft.shared.auth.OAuthException
import ceui.pixshaft.shared.auth.PixivOAuth
import ceui.pixshaft.shared.model.TokenResponse
import ceui.pixshaft.shared.net.PixivClientIdentity
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebAuthResult(
    val tokenJson: String? = null,
    val callbackUri: String? = null,
    val error: String? = null,
)

/**
 * Pixeval-style OAuth: native WebView2 dialog intercepts `pixiv://account/login`,
 * then exchanges the code with .NET HttpClient (pinned Cloudflare IP). Never CDP.
 */
object WebAuthHost {
    private val live = AtomicReference<Process?>(null)
    private val gson = Gson()

    fun available(): Boolean = findExe() != null

    fun cancel() {
        live.getAndSet(null)?.let { runCatching { it.destroyForcibly() } }
    }

    fun authenticate(startUrl: String, verifier: String, onStatus: (String) -> Unit): WebAuthResult {
        val exe = findExe()
            ?: throw OAuthException("找不到 WebView2 登录窗 PixShaftWebAuth.exe")
        val result = Files.createTempFile("pixshaft-auth-", ".txt")
        val userData = Files.createTempDirectory("PixShaftWebAuth-")
        onStatus("正在打开 WebView2 登录窗…")
        val process = ProcessBuilder(
            exe.toString(),
            "--start", startUrl,
            "--result", result.toAbsolutePath().toString(),
            "--user-data", userData.toAbsolutePath().toString(),
            "--ua", PixivClientIdentity.ANDROID_CHROME_UA,
            "--verifier", verifier,
        ).directory(exe.parent.toFile()).start()
        live.set(process)
        try {
            val finished = process.waitFor(8, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                throw OAuthException("登录超时：请点刷新重试")
            }
            val code = process.exitValue()
            val text = runCatching { Files.readString(result) }.getOrDefault("").trim()
            if (code != 0 && text.startsWith("ERROR")) {
                throw OAuthException(text.removePrefix("ERROR").trim().trimStart('\t'))
            }
            if (code != 0) {
                throw CancellationException("已取消登录")
            }
            return parseResult(text)
        } finally {
            live.compareAndSet(process, null)
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { Files.deleteIfExists(result) }
            runCatching { userData.toFile().deleteRecursively() }
        }
    }

    fun parseToken(json: String): TokenResponse {
        val parsed = gson.fromJson(json, TokenResponse::class.java)
        if (parsed.access_token.isNullOrBlank() || parsed.refresh_token.isNullOrBlank()) {
            throw OAuthException("Token response missing tokens: ${json.take(400)}")
        }
        return parsed
    }

    internal fun parseResult(text: String): WebAuthResult {
        var tokenJson: String? = null
        var callbackUri: String? = null
        var error: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TOKEN") ->
                    tokenJson = trimmed.removePrefix("TOKEN").trim().trimStart('\t')
                trimmed.startsWith("CALLBACK") ->
                    callbackUri = trimmed.removePrefix("CALLBACK").trim().trimStart('\t')
                trimmed.startsWith("ERROR") ->
                    error = trimmed.removePrefix("ERROR").trim().trimStart('\t')
            }
        }
        if (tokenJson.isNullOrBlank() && callbackUri != null && !PixivOAuth.isCallbackUri(callbackUri)) {
            throw OAuthException(PixivOAuth.missingCodeMessage(callbackUri))
        }
        if (tokenJson.isNullOrBlank() && callbackUri.isNullOrBlank()) {
            throw OAuthException(error ?: "登录窗没有返回授权码")
        }
        return WebAuthResult(tokenJson = tokenJson, callbackUri = callbackUri, error = error)
    }

    internal fun findExe(): Path? {
        System.getProperty("pixshaft.webauth")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }?.let { return it }
        val names = listOf("PixShaftWebAuth.exe")
        val dirs = buildList {
            add(AppPaths.root.resolve("bin"))
            System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it)) }
            runCatching {
                ProcessHandle.current().info().command().ifPresent { cmd ->
                    add(Path.of(cmd).parent)
                }
            }
            add(Path.of(System.getProperty("user.dir", ".")))
            add(Path.of(System.getProperty("user.dir", ".")).resolve("desktop").resolve("build").resolve("webauth"))
            add(Path.of(System.getProperty("user.dir", ".")).resolve("desktop").resolve("distribute").resolve("windows"))
            add(Path.of(System.getProperty("user.dir", ".")).resolve("distribute").resolve("windows"))
        }.filterNotNull()
        for (dir in dirs) {
            for (name in names) {
                val file = dir.resolve(name)
                if (Files.isRegularFile(file)) return file
            }
        }
        return null
    }
}
