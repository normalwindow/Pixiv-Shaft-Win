package ceui.pixshaft.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ceui.pixshaft.shared.model.Illust
import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

data class DesktopSettings(
    // 界面
    val themeMode: Int = 0, // 0 跟随系统 1 浅色 2 深色
    val lineCount: Int = 0, // 0 = 自动
    val useStaggeredLayout: Boolean = true,
    val mainViewR18: Boolean = false,
    val appLanguage: String = "",
    val appLocale: String = "", // 空=跟随系统；zh / en / ja
    val readerFit: Int = 0, // 0 适应宽度 1 适应整页
    val readerRtl: Boolean = false, // 日漫右开：从右往左翻
    val readerDarkBg: Boolean = true,
    val showNovelCardTags: Boolean = true,
    val showNovelCardTagTranslations: Boolean = false,
    val navigationInitPosition: String = "home",
    val accentColor: Int = 0, // 0 blue 1 violet 2 teal 3 rose 4 amber
    val showCardOverlay: Boolean = true,
    val compactUi: Boolean = false,
    val browseLayout: Int = 0, // 0 瀑布流跳转 1 左右分栏
    // 网络
    val directConnect: Boolean = true,
    val useSecureDns: Boolean = true,
    val imageHostMode: Int = 0,
    val customImageHost: String = "",
    val showLargeThumbnailImage: Boolean = false,
    val showOriginalPreviewImage: Boolean = false,
    // 浏览
    val autoRefreshHomeFeed: Boolean = true,
    val saveViewHistory: Boolean = true,
    val filterComment: Boolean = false,
    val r18FilterDefaultEnable: Boolean = false,
    val deleteAIIllust: Boolean = false,
    val aiBlockStrength: Int = 0,
    val filterRankBookmarked: Boolean = true,
    val deleteStarIllust: Boolean = false,
    val searchDefaultSortType: String = "date_desc",
    val searchPopularDefault: Boolean = false,
    val searchTarget: String = "partial_match_for_tags",
    val searchBookmarkMin: Int = 0,
    val searchR18: Int = 0, // 0 全部 1 仅 R-18 2 排除 R-18
    val novelFilterMinTextLength: Int = 0,
    val novelFilterMaxTextLength: Int = 0,
    val novelFilterMaxTagNameLength: Int = 0,
    val synonymDictEnabled: Boolean = false,
    val searchExitConfirm: Boolean = false,
    val feedBackToTopFab: Boolean = false,
    // 看图
    val novelListDirectToReader: Boolean = false,
    val autoPlayUgoira: Boolean = true,
    val ugoiraRifeEnable: Boolean = false,
    val ugoiraSaveFormat: Int = 1, // 0 gif 1 mp4
    val illustDetailKeepScreenOn: Boolean = false,
    val keepStatusBarWhenViewImage: Boolean = false,
    val useArtworkV3: Boolean = false,
    val detailPanelCollapsedByDefault: Boolean = false,
    val detailStyle: Int = 0, // 0 默认模式 1 手机原样模式
    val customTitleBar: Boolean = true, // 自绘标题栏（默认开；切换时重建窗口）
    // 鼠标交互
    val middleClickDownload: Boolean = true, // 瀑布流中键快捷下载
    val downloadMouseButton: Int = 1, // 0 中键 1 后侧键 2 前侧键
    val backOnRightClick: Boolean = true, // 详情页右键返回
    val backMouseButton: Int = 0, // 0 右键 1 中键 2 后侧键 3 前侧键
    val quickDownloadToast: Boolean = true, // 快捷下载后弹简易提示
    // 收藏
    val privateStar: Boolean = false,
    val privateFollow: Boolean = false,
    val showLikeButton: Boolean = true,
    val hideStarButtonAtMyCollection: Boolean = false,
    val filterInvalidBookmarks: Boolean = false,
    val starWithTagSelectAll: Boolean = false,
    val showRelatedWhenStar: Boolean = true,
    val autoFollowAfterStar: Boolean = false,
    val autoDownloadAfterStar: Boolean = false,
    val autoPostLikeWhenDownload: Boolean = false,
    // 下载（Windows 适配）
    val illustPath: String = "",
    val illustR18Path: String = "",
    val novelPath: String = "",
    val gifPath: String = "",
    val backupPath: String = "",
    val logPath: String = "",
    val illustFileName: String = "{illust_id}_p{index}.{ext}",
    val hasP0: Boolean = false,
    val r18DivideSave: Boolean = true,
    val aiDivideSave: Boolean = false,
    val saveForSeparateAuthor: Boolean = false,
    val overwritePolicy: Int = 1, // 0 skip 1 overwrite 2 rename
    val pageIndexStart: Int = 0,
    val writeTagsToImageExif: Boolean = false,
    val defaultNovelFormat: String = "txt",
    val maxConcurrentDownloads: Int = 2,
    val toastDownloadResult: Boolean = true,
    val silentDownload: Boolean = false,
    val illustLongPressDownload: Boolean = false,
    val autoExportIllustCaption: Boolean = false,
    val downloadLimitType: Int = 0,
    val aria2Enabled: Boolean = false,
    val aria2RpcUrl: String = "",
    val aria2RpcSecret: String = "",
    val aria2RemoteDir: String = "",
    // AI（桌面无 ncnn，保留开关与文案）
    val cloudTranslateEnabled: Boolean = true,
    val aiTranslateEnabled: Boolean = false,
    val aiTranslateBaseUrl: String = "",
    val aiTranslateApiKey: String = "",
    val aiTranslateModel: String = "",
    val aiTranslatePrompt: String = "",
    // 数据
    val bookmarkMirrorEnabled: Boolean = true,
    val cachePath: String = "",
    val diskCacheEnabled: Boolean = true,
    val diskCacheLimitMb: Int = 1024,
    val fanboxCookie: String = "",
    // 试验性
    val showChatRoomEntry: Boolean = false,
    val showPlazaEntry: Boolean = false,
    val autoSnapshotOnBookmark: Boolean = false,
    val isFirebaseEnable: Boolean = false,
)

class SettingsStore(private val gson: Gson = Gson()) {
    var current: DesktopSettings by mutableStateOf(load())
        private set

    private val listeners = CopyOnWriteArrayList<(DesktopSettings, DesktopSettings) -> Unit>()

    fun onChange(block: (DesktopSettings, DesktopSettings) -> Unit) {
        listeners += block
    }

    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        val next = transform(current)
        apply(next)
    }

    fun reset() {
        apply(DesktopSettings())
    }

    private fun apply(next: DesktopSettings) {
        val prev = current
        save(next)
        current = next
        Chromium.directConnect = next.directConnect
        ceui.pixshaft.shared.net.ImageHosts.configure(next.imageHostMode, next.customImageHost)
        listeners.forEach { runCatching { it(prev, next) } }
    }

    private fun load(): DesktopSettings {
        if (!Files.exists(AppPaths.settingsFile)) return DesktopSettings()
        return runCatching {
            val text = Files.readString(AppPaths.settingsFile)
            val parsed = gson.fromJson(text, DesktopSettings::class.java) ?: DesktopSettings()
            var next = parsed
            if (!text.contains("\"directConnect\"")) next = next.copy(directConnect = true)
            if (!text.contains("\"customTitleBar\"")) next = next.copy(customTitleBar = true)
            if (!text.contains("\"browseLayout\"")) next = next.copy(browseLayout = 0)
            if (!text.contains("\"detailStyle\"")) next = next.copy(detailStyle = 0)
            // 老 settings.json 没有这几个键时补默认值（Gson 会把缺失的 boolean 反序列化成 false）
            if (!text.contains("\"backOnRightClick\"")) next = next.copy(backOnRightClick = true)
            if (!text.contains("\"middleClickDownload\"")) next = next.copy(middleClickDownload = true)
            if (!text.contains("\"quickDownloadToast\"")) next = next.copy(quickDownloadToast = true)
            if (!text.contains("\"downloadMouseButton\"")) next = next.copy(downloadMouseButton = 1)
            if (!text.contains("\"backMouseButton\"")) next = next.copy(backMouseButton = 0)
            next
        }.getOrDefault(DesktopSettings())
    }

    private fun save(value: DesktopSettings) {
        runCatching {
            Files.createDirectories(AppPaths.settingsFile.parent)
            Files.writeString(AppPaths.settingsFile, gson.toJson(value))
        }
    }
}

object WinPaths {
    private fun home(): Path = Path.of(System.getProperty("user.home"))

    fun defaultIllust(): String = home().resolve("Pictures").resolve("ShaftImages").toString()
    fun defaultR18(): String = home().resolve("Pictures").resolve("ShaftImages-R18").toString()
    fun defaultNovel(): String = home().resolve("Downloads").resolve("ShaftNovels").toString()
    fun defaultGif(): String = home().resolve("Pictures").resolve("ShaftGIFs").toString()
    fun defaultBackup(): String = home().resolve("Downloads").resolve("ShaftBackups").toString()
    fun defaultLog(): String = home().resolve("Downloads").resolve("ShaftFiles").toString()
    fun defaultCache(): String = AppPaths.defaultCache().toString()

    fun pickDirectory(current: String): String? {
        var result: String? = null
        val task = Runnable {
            val chooser = JFileChooser(current.ifBlank { defaultIllust() })
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = "选择保存目录"
            chooser.isAcceptAllFileFilterUsed = false
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result = chooser.selectedFile.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        return result
    }
}

fun DesktopSettings.resolvedIllustDir(illust: Illust): Path {
    val base = when {
        illust.isR18() && r18DivideSave -> Path.of(illustR18Path.ifBlank { WinPaths.defaultR18() })
        illust.isAi() && aiDivideSave -> Path.of(illustPath.ifBlank { WinPaths.defaultIllust() }).resolve("AI")
        else -> Path.of(illustPath.ifBlank { WinPaths.defaultIllust() })
    }
    val author = illust.user?.name?.ifBlank { null } ?: illust.user?.id?.toString()
    return if (saveForSeparateAuthor && !author.isNullOrBlank()) {
        base.resolve(author.replace(Regex("""[\\/:*?"<>|]"""), "_"))
    } else {
        base
    }
}

fun DesktopSettings.illustFileName(illust: Illust, index: Int, ext: String): String {
    val pageIndex = index + pageIndexStart
    val includePage = illust.page_count > 1 || hasP0
    val template = illustFileName.ifBlank { "{illust_id}_p{index}.{ext}" }
    val name = template
        .replace("{illust_id}", illust.id.toString())
        .replace("{title}", (illust.title ?: "").replace(Regex("""[\\/:*?"<>|]"""), "_"))
        .replace("{author}", (illust.user?.name ?: "").replace(Regex("""[\\/:*?"<>|]"""), "_"))
        .replace("{index}", pageIndex.toString())
        .replace("{ext}", ext)
    return if (includePage) {
        name
    } else {
        name.replace("_p$pageIndex", "").replace("_p{index}", "")
    }
}

fun DesktopSettings.shouldHide(illust: Illust): Boolean {
    if (r18FilterDefaultEnable && illust.isR18()) return true
    if (deleteAIIllust && illust.isAi() && aiBlockStrength == 0) return true
    if (deleteStarIllust && illust.isBookmarked) return true
    return false
}
