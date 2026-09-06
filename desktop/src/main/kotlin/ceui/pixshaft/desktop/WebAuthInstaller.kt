package ceui.pixshaft.desktop

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * PixShaftWebAuth.exe is published as its own GitHub Release asset, not packed
 * into the portable zip / MSI. The login screen downloads it on demand into
 * the writable data directory.
 */
object WebAuthInstaller {
    const val FILE_NAME = "PixShaftWebAuth.exe"

    val managedPath: Path
        get() = AppPaths.root.resolve("bin").resolve(FILE_NAME)

    fun fallbackUrl(): String =
        AppVersion.GITHUB_URL + "/releases/latest/download/" + FILE_NAME

    fun parseAssetUrl(releaseJson: String): String? {
        val parsed = runCatching { JsonParser.parseString(releaseJson) }.getOrNull() ?: return null
        if (parsed.isJsonArray) {
            for (el in parsed.asJsonArray) {
                parseAssetUrlFromObject(el.asJsonObject)?.let { return it }
            }
            return null
        }
        if (parsed.isJsonObject) return parseAssetUrlFromObject(parsed.asJsonObject)
        return null
    }

    private fun parseAssetUrlFromObject(root: com.google.gson.JsonObject): String? {
        val assets = root.getAsJsonArray("assets") ?: return null
        for (el in assets) {
            val obj = el.asJsonObject
            if (obj.get("name")?.asString == FILE_NAME) {
                return obj.get("browser_download_url")?.asString?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun existingCopies(): List<Path> {
        val found = LinkedHashSet<Path>()
        WebAuthHost.findExe()?.let { found.add(it.toAbsolutePath().normalize()) }
        runCatching {
            val managed = managedPath.toAbsolutePath().normalize()
            if (Files.isRegularFile(managed)) found.add(managed)
        }
        return found.toList()
    }

    fun deleteCopies(): Int {
        var n = 0
        for (path in existingCopies()) {
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) n++
        }
        return n
    }

    fun githubClient(proxy: java.net.Proxy): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(proxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Path {
        val errors = ArrayList<String>()
        installFromLocalBuild()?.let { return it }

        val clients = linkedMapOf(
            "direct" to githubClient(java.net.Proxy.NO_PROXY),
            "app-proxy" to githubClient(Chromium.javaProxy()),
        )
        for ((label, client) in clients) {
            try {
                return downloadWith(client, onProgress)
            } catch (e: Exception) {
                errors += label + ": " + (e.message ?: e.javaClass.simpleName)
            }
        }
        throw IOException(humanize(errors))
    }

    internal fun humanize(errors: List<String>): String {
        val blob = errors.joinToString(" | ")
        val missing = blob.contains("404") || blob.contains("Not Found", ignoreCase = true)
        val aborted = blob.contains("aborted", ignoreCase = true) ||
            blob.contains("\u4e2d\u6b62") ||
            blob.contains("Connection reset", ignoreCase = true) ||
            blob.contains("Software caused connection abort", ignoreCase = true)
        return when {
            missing ->
                "GitHub Release \u8fd8\u6ca1\u6709 PixShaftWebAuth.exe\u3002\u53d1\u5e03\u5e26\u8be5\u6587\u4ef6\u7684\u7248\u672c\u540e\u518d\u4e0b\u8f7d\u3002" + blob
            aborted ->
                "\u8fde\u63a5\u88ab\u4e2d\u65ad\u3002\u5e38\u89c1\u539f\u56e0\uff1a\u5c1a\u672a\u53d1\u5e03\u767b\u5f55\u7ec4\u4ef6\u3001GitHub \u88ab\u4ee3\u7406/\u9632\u706b\u5899\u91cd\u7f6e\u3002" + blob
            else -> blob
        }
    }

    private fun installFromLocalBuild(): Path? {
        val cwd = Path.of(System.getProperty("user.dir", "."))
        val candidates = listOf(
            cwd.resolve("desktop").resolve("build").resolve("webauth").resolve(FILE_NAME),
            cwd.resolve("desktop").resolve("build").resolve("compose").resolve("binaries")
                .resolve("main-release").resolve("webauth").resolve(FILE_NAME),
            cwd.resolve("build").resolve("webauth").resolve(FILE_NAME),
        )
        val src = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
        val dest = managedPath
        Files.createDirectories(dest.parent)
        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        dest.toFile().setExecutable(true)
        return dest
    }

    private fun downloadWith(client: OkHttpClient, onProgress: (Long, Long) -> Unit): Path {
        val assetUrl = resolveAssetUrl(client)
        val req = Request.Builder()
            .url(assetUrl)
            .header("User-Agent", "PixShaft-Win")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw IOException("HTTP 404 " + assetUrl)
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " " + assetUrl)
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val dest = managedPath
            Files.createDirectories(dest.parent)
            val tmp = dest.resolveSibling(FILE_NAME + ".part")
            Files.newOutputStream(tmp).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            }
            runCatching { Files.deleteIfExists(dest) }
            Files.move(tmp, dest)
            dest.toFile().setExecutable(true)
            return dest
        }
    }

    private fun resolveAssetUrl(client: OkHttpClient): String {
        val listReq = Request.Builder()
            .url(AppVersion.RELEASES_API.replace("/latest", ""))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PixShaft-Win")
            .build()
        val fromList = runCatching {
            client.newCall(listReq).execute().use { resp ->
                if (!resp.isSuccessful) null
                else parseAssetUrl(resp.body?.string().orEmpty())
            }
        }.getOrNull()
        if (!fromList.isNullOrBlank()) return fromList

        val latestReq = Request.Builder()
            .url(AppVersion.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PixShaft-Win")
            .build()
        val fromLatest = runCatching {
            client.newCall(latestReq).execute().use { resp ->
                if (!resp.isSuccessful) null
                else parseAssetUrl(resp.body?.string().orEmpty())
            }
        }.getOrNull()
        return fromLatest ?: fallbackUrl()
    }
}
