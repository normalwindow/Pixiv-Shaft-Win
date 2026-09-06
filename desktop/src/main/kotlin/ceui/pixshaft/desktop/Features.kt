package ceui.pixshaft.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import ceui.pixshaft.shared.model.Illust
import ceui.pixshaft.shared.model.StoredSession
import ceui.pixshaft.shared.model.UgoiraFrame
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

data class QueueJob(
    val id: String,
    val illustId: Long,
    val title: String,
    val urls: List<String>,
    val status: String,
    val error: String? = null,
    val finished: Int = 0,
    val total: Int = 0,
    val thumbUrl: String? = null,
)

/** downloadUrls 在协作取消时抛出的标记文案。 */
const val DOWNLOAD_CANCELLED = "已取消"

class DownloadQueue(
    private val graph: AppGraph,
    private val gson: Gson = Gson(),
) {
    val jobs: SnapshotStateList<QueueJob> = mutableStateListOf()
    var paused by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val cancelRequested = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val type = object : TypeToken<MutableList<QueueJob>>() {}.type

    @Volatile private var gate = Semaphore(graph.settings.current.maxConcurrentDownloads.coerceIn(1, 8))

    init {
        if (Files.exists(AppPaths.queueFile)) {
            runCatching {
                gson.fromJson<MutableList<QueueJob>>(Files.readString(AppPaths.queueFile), type)
                    .orEmpty()
                    .forEach { raw ->
                        val status = if (raw.status == "running") "pending" else raw.status
                        jobs += raw.copy(status = status)
                    }
            }
        }
        jobs.filter { it.status == "pending" }.forEach { start(it, resume = true) }
        graph.settings.onChange { prev, next ->
            if (prev.maxConcurrentDownloads != next.maxConcurrentDownloads) {
                gate = Semaphore(next.maxConcurrentDownloads.coerceIn(1, 8))
            }
        }
    }

    fun enqueue(illust: Illust) {
        val urls = illust.pageUrls()
        if (urls.isEmpty()) return
        val job = QueueJob(
            id = UUID.randomUUID().toString(),
            illustId = illust.id,
            title = illust.title ?: "#${illust.id}",
            urls = urls,
            status = "pending",
            total = urls.size,
            thumbUrl = illust.previewUrl(),
        )
        jobs += job
        persist()
        start(job, resume = false)
    }

    fun clearFinished() {
        jobs.removeAll { it.status == "done" || it.status == "error" || it.status == "canceled" }
        persist()
    }

    fun cancel(job: QueueJob) {
        if (job.status == "pending") {
            started.remove(job.id)
            val i = jobs.indexOfFirst { it.id == job.id }
            if (i >= 0) jobs[i] = job.copy(status = "canceled")
            persist()
        } else if (job.status == "running") {
            cancelRequested.add(job.id)
        }
    }

    fun pause(value: Boolean) {
        paused = value
        if (!value) jobs.filter { it.status == "pending" }.forEach { start(it, resume = true) }
    }

    fun retry(job: QueueJob) {
        started.remove(job.id)
        cancelRequested.remove(job.id)
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i < 0) return
        jobs[i] = job.copy(status = "pending", error = null)
        persist()
        if (!paused) start(jobs[i], resume = true)
    }

    fun retryAllFailed() {
        jobs.filter { it.status == "error" || it.status == "canceled" }.forEach { retry(it) }
    }

    fun remove(job: QueueJob) {
        started.remove(job.id)
        cancelRequested.remove(job.id)
        jobs.removeAll { it.id == job.id }
        persist()
    }

    /** Close-time snapshot: running jobs become pending so the next launch resumes them. */
    @Synchronized
    fun shutdown() {
        for (i in jobs.indices) {
            if (jobs[i].status == "running") jobs[i] = jobs[i].copy(status = "pending")
        }
        persist()
    }

    @Synchronized
    private fun persist() {
        runCatching {
            val file = AppPaths.queueFile
            Files.createDirectories(file.parent)
            val snapshot = jobs.toList()
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(snapshot))
            try {
                Files.move(
                    tmp,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun start(job: QueueJob, resume: Boolean) {
        if (paused) return
        if (!started.add(job.id)) return
        scope.launch { gate.withPermit { runJob(job, resume) } }
    }

    private suspend fun runJob(job: QueueJob, resume: Boolean) {
        val idx = jobs.indexOfFirst { it.id == job.id }
        if (idx < 0) return
        val dummy = Illust(id = job.illustId, title = job.title, page_count = job.urls.size)
        val already = if (resume) countExistingPages(graph, dummy, job.urls) else 0
        jobs[idx] = job.copy(status = "running", finished = already, total = job.urls.size)
        persist()
        runCatching {
            downloadUrls(
                graph,
                dummy,
                job.urls,
                isCancelled = { job.id in cancelRequested },
                resumeExisting = resume,
                onProgress = { done, total ->
                    val i = jobs.indexOfFirst { it.id == job.id }
                    if (i >= 0) {
                        jobs[i] = jobs[i].copy(finished = done, total = total)
                        persist()
                    }
                },
            )
        }.onSuccess {
            graph.downloaded.mark(job.illustId)
            val i = jobs.indexOfFirst { it.id == job.id }
            if (i >= 0) jobs[i] = jobs[i].copy(status = "done", finished = job.urls.size, total = job.urls.size)
            if (graph.settings.current.autoPostLikeWhenDownload) {
                runCatching {
                    graph.client.api.addBookmark(
                        job.illustId,
                        if (graph.settings.current.privateStar) "private" else "public",
                    )
                }
            }
        }.onFailure { err ->
            val i = jobs.indexOfFirst { it.id == job.id }
            if (i >= 0) {
                if (err.message == DOWNLOAD_CANCELLED) {
                    jobs[i] = jobs[i].copy(status = "canceled", error = null)
                } else {
                    jobs[i] = jobs[i].copy(status = "error", error = err.message)
                }
            }
        }
        cancelRequested.remove(job.id)
        persist()
    }
}

class AccountStore(private val gson: Gson = Gson()) {
    val accounts: SnapshotStateList<StoredSession> = mutableStateListOf()
    private val type = object : TypeToken<MutableList<StoredSession>>() {}.type

    init {
        if (Files.exists(AppPaths.accountsFile)) {
            runCatching {
                gson.fromJson<MutableList<StoredSession>>(Files.readString(AppPaths.accountsFile), type)
                    .orEmpty()
                    .forEach { accounts += it }
            }
        }
    }

    fun upsert(session: StoredSession) {
        val uid = session.user?.id ?: return
        val i = accounts.indexOfFirst { it.user?.id == uid }
        if (i >= 0) accounts[i] = session else accounts += session
        persist()
    }

    fun remove(uid: Long) {
        accounts.removeAll { it.user?.id == uid }
        persist()
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(AppPaths.accountsFile.parent)
            Files.writeString(AppPaths.accountsFile, gson.toJson(accounts.toList()))
        }
    }
}

data class UgoiraClip(val frames: List<Path>, val delays: List<Int>)

object UgoiraLoader {
    suspend fun load(graph: AppGraph, illustId: Long): UgoiraClip = withContext(Dispatchers.IO) {
        val meta = graph.client.api.ugoiraMetadata(illustId).ugoira_metadata
            ?: error("没有 ugoira 元数据")
        val zipUrl = meta.zip_urls?.medium ?: error("没有 zip")
        val dir = AppPaths.cacheRoot().resolve("ugoira").resolve(illustId.toString())
        Files.createDirectories(dir)
        val zipFile = dir.resolve("pack.zip")
        if (!Files.exists(zipFile) || Files.size(zipFile) < 32) {
            val req = Request.Builder().url(zipUrl).build()
            graph.imageHttp.newCall(req).execute().use { resp ->
                val body = resp.body ?: error("zip 空")
                Files.write(zipFile, body.bytes())
            }
        }
        ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val target = dir.resolve(entry.name.substringAfterLast('/').ifBlank { entry.name })
                Files.write(target, zis.readBytes())
            }
        }
        val frames = meta.frames.ifEmpty { listOf(UgoiraFrame("000000.jpg", 120)) }
        val files = frames.map { dir.resolve(it.file ?: "000000.jpg") }.filter { Files.exists(it) }
        val delays = frames.map { it.delay.coerceAtLeast(20) }
        UgoiraClip(files, delays)
    }
}

object LibraryScanner {
    fun scan(settings: DesktopSettings): List<LibraryItem> {
        val dirs = listOfNotNull(
            settings.illustPath.ifBlank { WinPaths.defaultIllust() },
            settings.illustR18Path.ifBlank { WinPaths.defaultR18() },
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
        val idRe = Regex("""(\d{4,})""")
        val seen = LinkedHashSet<Long>()
        val out = mutableListOf<LibraryItem>()
        dirs.forEach { dir ->
            runCatching {
                Files.walk(dir, 3).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val m = idRe.find(file.fileName.toString()) ?: return@forEach
                        val id = m.groupValues[1].toLongOrNull() ?: return@forEach
                        if (seen.add(id)) out += LibraryItem(id, file.fileName.toString(), file)
                    }
                }
            }
        }
        return out.sortedByDescending { it.id }
    }
}

data class LibraryItem(val id: Long, val name: String, val path: Path)

object AiTools {
    data class Tool(val id: String, val title: String, val exeNames: List<String>, val hint: String)

    val tools = listOf(
        Tool("upscale", "超分 Real-ESRGAN", listOf("realesrgan-ncnn-vulkan.exe", "realesrgan-ncnn-vulkan"), "nihui/realesrgan-ncnn-vulkan Windows 包"),
        Tool("rembg", "抠图 rembg", listOf("rembg.exe", "rembg"), "pip install rembg"),
        Tool("rife", "补帧 RIFE", listOf("rife-ncnn-vulkan.exe", "rife-ncnn-vulkan"), "nihui/rife-ncnn-vulkan"),
        Tool("ocr", "OCR Tesseract", listOf("tesseract.exe", "tesseract"), "UB-Mannheim Tesseract"),
    )

    fun find(tool: Tool): Path? {
        val extra = listOf(AppPaths.root.resolve("tools"), Path.of(System.getProperty("user.dir"), "tools"))
        val pathDirs = System.getenv("PATH").orEmpty().split(Regex("[;:]")).map { Path.of(it) }
        (extra + pathDirs).forEach { dir ->
            tool.exeNames.forEach { name ->
                val p = dir.resolve(name)
                if (Files.isRegularFile(p)) return p
            }
        }
        return null
    }

    fun pickImage(): Path? {
        var result: Path? = null
        val task = Runnable {
            val chooser = JFileChooser()
            chooser.fileFilter = FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "webp")
            chooser.dialogTitle = "选择要处理的图片"
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result = chooser.selectedFile.toPath()
            }
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run()
        else javax.swing.SwingUtilities.invokeAndWait(task)
        return result
    }

    fun run(tool: Tool, input: Path): String {
        val exe = find(tool) ?: return "未找到 ${tool.title}。请把可执行文件放到 %APPDATA%\\PixShaft\\tools 或加入 PATH。${tool.hint}"
        val out = input.resolveSibling("${input.fileName}_${tool.id}${input.fileName.toString().substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }}")
        val cmd = when (tool.id) {
            "upscale" -> listOf(exe.toString(), "-i", input.toString(), "-o", out.toString(), "-n", "realesrgan-x4plus")
            "rembg" -> listOf(exe.toString(), "i", input.toString(), out.toString())
            "rife" -> listOf(exe.toString(), "-i", input.parent.toString(), "-o", out.parent.toString())
            "ocr" -> listOf(exe.toString(), input.toString(), "stdout", "-l", "jpn+eng")
            else -> listOf(exe.toString(), input.toString())
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return if (code == 0) "完成 → $out\n$log".trim() else "退出码 $code\n$log"
    }
}

object ShaftSign {
    fun hmac(uid: Long, ts: String): String? = ChatProtocol.sign(uid, ts)
}

fun countExistingPages(graph: AppGraph, illust: Illust, urls: List<String>): Int {
    val settings = graph.settings.current
    val dir = settings.resolvedIllustDir(illust)
    if (!Files.isDirectory(dir)) return 0
    return urls.indices.count { index ->
        val ext = urls[index].substringAfterLast('.', "jpg").substringBefore('?').ifBlank { "jpg" }
        val file = dir.resolve(settings.illustFileName(illust, index, ext))
        Files.exists(file) && Files.size(file) > 32
    }
}

suspend fun downloadUrls(
    graph: AppGraph,
    illust: Illust,
    urls: List<String>,
    isCancelled: () -> Boolean = { false },
    resumeExisting: Boolean = true,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
) {
    val settings = graph.settings.current
    val dir = settings.resolvedIllustDir(illust)
    Files.createDirectories(dir)
    urls.forEachIndexed { index, url ->
        if (isCancelled()) error(DOWNLOAD_CANCELLED)
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').ifBlank { "jpg" }
        var dest = dir.resolve(settings.illustFileName(illust, index, ext))
        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        if (Files.exists(dest) && Files.size(dest) > 32) {
            val skip = resumeExisting || settings.overwritePolicy == 0
            if (skip) {
                onProgress(index + 1, urls.size)
                return@forEachIndexed
            }
            if (settings.overwritePolicy == 2) {
                var n = 1
                while (Files.exists(dest)) {
                    dest = dir.resolve(settings.illustFileName(illust, index, ext).replace(".$ext", "_$n.$ext"))
                    n++
                }
            }
        }
        var existing = if (Files.exists(part)) Files.size(part) else 0L
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        graph.imageHttp.newCall(request).execute().use { response ->
            val body = response.body ?: return@use
            val append = response.code == 206 && existing > 0
            if (!append && existing > 0) {
                runCatching { Files.deleteIfExists(part) }
                existing = 0
            }
            val options = if (append) {
                arrayOf(
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            } else {
                arrayOf(
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                )
            }
            Files.newOutputStream(part, *options).use { out ->
                body.byteStream().copyTo(out)
            }
        }
        if (isCancelled()) error(DOWNLOAD_CANCELLED)
        Files.move(dest.resolveSibling(dest.fileName.toString() + ".part"), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (settings.silentDownload) runCatching { dest.toFile().setLastModified(0L) }
        onProgress(index + 1, urls.size)
    }
}

fun pickImageFile(): Path? = AiTools.pickImage()
