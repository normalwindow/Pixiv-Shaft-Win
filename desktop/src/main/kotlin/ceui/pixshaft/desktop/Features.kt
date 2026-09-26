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
    /** 当前这一页已经落盘的字节数。 */
    val pageBytes: Long = 0L,
    /** 当前这一页的总字节数；HTTP 没给 Content-Length 时是 0。 */
    val pageTotal: Long = 0L,
    /** 已经下完的页面的字节之和（用来算总速率）。 */
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
) {
    /**
     * 0f..1f。页数 + 页内字节双重进度：单图作品只有 1 页，
     * 光看 [finished]/[total] 会一直是 0 然后直接跳到 1（进度条等于没有），
     * 所以当前页按已下载字节再切一刀。
     */
    fun progress(): Float {
        if (total <= 0) return 0f
        val pageFraction = if (pageTotal > 0L) {
            (pageBytes.toDouble() / pageTotal.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
        val done = (finished + pageFraction).coerceIn(0f, total.toFloat())
        return (done / total.toFloat()).coerceIn(0f, 1f)
    }

    val downloadedBytes: Long get() = bytesDone + pageBytes

    /**
     * 本次任务已知的总字节数。[bytesTotal] 在每页开工时就把 Content-Length 累进去了
     * （含正在下的这一页），所以这里**不能再加一次 [pageTotal]**，否则当前页会被算两遍。
     */
    val knownBytes: Long get() = bytesTotal

    /** 下载管理页显示的进行中文案：多页看页数，页内再看字节。 */
    fun progressText(): String {
        if (total <= 0) return ""
        val base = "$finished/$total"
        if (pageTotal <= 0L) return base
        val percent = (pageBytes * 100.0 / pageTotal.toDouble()).toInt().coerceIn(0, 100)
        return "$base · ${percent}%"
    }

    fun bytesText(): String {
        val done = downloadedBytes
        if (done <= 0L) return ""
        val known = knownBytes
        return if (known > 0L) "${humanBytes(done)} / ${humanBytes(known)}" else humanBytes(done)
    }
}

fun humanBytes(n: Long): String = when {
    n < 1024L -> "${n}B"
    n < 1024L * 1024L -> "${n / 1024L}KB"
    n < 1024L * 1024L * 1024L -> String.format("%.1fMB", n / 1024.0 / 1024.0)
    else -> String.format("%.2fGB", n / 1024.0 / 1024.0 / 1024.0)
}

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

    /** 「已加入下载队列」之后，谁在监听这个作品的结果 —— 给快捷下载的 pop 提示用。 */
    var onJobSettled: ((Long, Boolean, String?) -> Unit)? = null

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
        jobs[idx] = job.copy(
            status = "running",
            finished = already,
            total = job.urls.size,
            pageBytes = 0L,
            pageTotal = 0L,
            bytesDone = 0L,
            bytesTotal = 0L,
        )
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
                        // 一页下完：页内计数清零，字节并进总量
                        jobs[i] = jobs[i].copy(
                            finished = done,
                            total = total,
                            pageBytes = 0L,
                            pageTotal = 0L,
                            bytesDone = jobs[i].bytesDone + jobs[i].pageTotal,
                        )
                        persist()
                    }
                },
                onFileSize = { _, expected ->
                    val i = jobs.indexOfFirst { it.id == job.id }
                    if (i >= 0) {
                        val size = expected.coerceAtLeast(0L)
                        // 新的一页开始：页内进度归零，页大小按 Content-Length 记下，
                        // 顺手把它累进「本次任务总字节」——先下完的页在进度条上就不会白占比例。
                        jobs[i] = jobs[i].copy(
                            pageBytes = 0L,
                            pageTotal = size,
                            bytesTotal = jobs[i].bytesTotal + size,
                        )
                        persist()
                    }
                },
                onPageBytes = { _, written, expected ->
                    val i = jobs.indexOfFirst { it.id == job.id }
                    if (i >= 0) {
                        jobs[i] = jobs[i].copy(
                            pageBytes = written,
                            pageTotal = if (expected > 0L) expected else jobs[i].pageTotal,
                        )
                        persist()
                    }
                },
            )
        }.onSuccess {
            graph.downloaded.mark(job.illustId)
            val i = jobs.indexOfFirst { it.id == job.id }
            if (i >= 0) {
                jobs[i] = jobs[i].copy(
                    status = "done",
                    finished = job.urls.size,
                    total = job.urls.size,
                    pageBytes = 0L,
                    pageTotal = 0L,
                )
            }
            runCatching { onJobSettled?.invoke(job.illustId, true, null) }
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
            val canceled = err.message == DOWNLOAD_CANCELLED
            if (i >= 0) {
                if (canceled) {
                    jobs[i] = jobs[i].copy(status = "canceled", error = null)
                } else {
                    jobs[i] = jobs[i].copy(status = "error", error = err.message)
                }
            }
            if (!canceled) runCatching { onJobSettled?.invoke(job.illustId, false, err.message) }
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

/**
 * 逐页下载。进度分三层回调，UI 才能既有页数也有「这一页下到哪了」：
 *
 * - [onFileSize]：一页开始（跳过 / 复用本地文件也会报，expected 取本地已有大小），
 *   给出这页的总字节数（Content-Length 或已存在的 .part）。
 * - [onPageBytes]：流式落盘过程中周期性上报当前页已写字节，几十毫秒一次、按 1% 节流。
 * - [onProgress]：一页完工，报「已完成页数 / 总页数」。
 */
suspend fun downloadUrls(
    graph: AppGraph,
    illust: Illust,
    urls: List<String>,
    isCancelled: () -> Boolean = { false },
    resumeExisting: Boolean = true,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    onFileSize: (Int, Long) -> Unit = { _, _ -> },
    onPageBytes: (Int, Long, Long) -> Unit = { _, _, _ -> },
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
                onFileSize(index + 1, Files.size(dest))
                onPageBytes(index + 1, Files.size(dest), Files.size(dest))
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
            // Content-Length 是「这一段」的长度：续传时加上已经落盘的那部分才是整页大小。
            val expected = body.contentLength().let { if (it > 0) it + existing else existing }
            onFileSize(index + 1, expected)
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
            // 边读边写：每读到一块就报一次进度。不用 byteStream().copyTo(out) 是因为
            // 那样只在整页落盘完才回调一次，进度条看不到过程（就是这次要修的 bug）。
            Files.newOutputStream(part, *options).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = existing
                    var lastReport = existing
                    var lastAt = 0L
                    onPageBytes(index + 1, written, expected)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        // 1% 或 120ms 报一次，避免每个 64KB 块都去写一遍 queue.json
                        val step = if (expected > 0L) expected / 100L else -1L
                        if (now - lastAt >= 120L || (step > 0L && written - lastReport >= step)) {
                            lastReport = written
                            lastAt = now
                            onPageBytes(index + 1, written, expected)
                        }
                    }
                    onPageBytes(index + 1, written, if (expected > 0L) expected else written)
                }
            }
        }
        if (isCancelled()) error(DOWNLOAD_CANCELLED)
        Files.move(dest.resolveSibling(dest.fileName.toString() + ".part"), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (settings.silentDownload) runCatching { dest.toFile().setLastModified(0L) }
        onProgress(index + 1, urls.size)
    }
}

fun pickImageFile(): Path? = AiTools.pickImage()
