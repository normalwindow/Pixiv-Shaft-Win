package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.AppPaths
import ceui.pixshaft.desktop.DesktopSettings
import ceui.pixshaft.desktop.WinPaths
import java.awt.Desktop
import java.net.URI

data class SettingsCategory(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
)

val SETTINGS_CATEGORIES = listOf(
    SettingsCategory("account", "账号", "多账号、资料、R18、会员", Icons.Outlined.Person, Color(0xFF3D7EFF)),
    SettingsCategory("network", "网络", "直连、DNS、图片加速、画质", Icons.Outlined.Language, Color(0xFF12B5A8)),
    SettingsCategory("appearance", "界面", "主题、强调色、列数、启动页", Icons.Outlined.Palette, Color(0xFF7C5CFF)),
    SettingsCategory("browsing", "浏览与搜索", "历史、过滤、AI 屏蔽、排序", Icons.Outlined.Search, Color(0xFFE8A317)),
    SettingsCategory("viewing", "看图与详情", "动图、详情栏、小说直达", Icons.Outlined.Photo, Color(0xFFE85D75)),
    SettingsCategory("bookmarks", "收藏与互动", "私密、自动关注、自动下载", Icons.Outlined.Bookmark, Color(0xFFFF6B8A)),
    SettingsCategory("download", "下载", "路径、文件名、并发、覆盖", Icons.Outlined.Download, Color(0xFF2BB673)),
    SettingsCategory("ai", "AI 功能", "超分、抠图、补帧、翻译", Icons.Outlined.AutoAwesome, Color(0xFF8B7CFF)),
    SettingsCategory("data", "备份与缓存", "缓存、还原、FANBOX cookie", Icons.Outlined.Folder, Color(0xFF5AA7B3)),
    SettingsCategory("experimental", "试验性", "聊天室、广场、快照", Icons.Outlined.Science, Color(0xFF8892A4)),
)

private data class SettingsSearchHit(val categoryKey: String, val title: String, val keywords: String)

private val SETTINGS_SEARCH_INDEX = listOf(
    SettingsSearchHit("account", "账号管理", "多账号 切换账号"),
    SettingsSearchHit("account", "R18 / R18G 设置", "成人 限制级"),
    SettingsSearchHit("account", "退出登录", "登出 logout"),
    SettingsSearchHit("network", "开启直连", "代理 vpn sni"),
    SettingsSearchHit("network", "网络测试", "dns quic 直连 诊断 ping"),
    SettingsSearchHit("network", "图片加速", "pixiv.cat pixiv.re"),
    SettingsSearchHit("network", "缩略图显示大图", "预览"),
    SettingsSearchHit("network", "详情页显示原图", "original"),
    SettingsSearchHit("appearance", "主题模式", "浅色 深色"),
    SettingsSearchHit("appearance", "强调色", "配色 accent"),
    SettingsSearchHit("appearance", "启动页", "首页 排行"),
    SettingsSearchHit("appearance", "卡片标题叠层", "overlay"),
    SettingsSearchHit("appearance", "紧凑界面", "compact"),
    SettingsSearchHit("appearance", "浏览模式", "左右分栏 双栏 瀑布流 平板 split"),
    SettingsSearchHit("appearance", "瀑布流列数", "列数 自定义 网格"),
    SettingsSearchHit("appearance", "自绘标题栏", "无边框 标题栏"),
    SettingsSearchHit("browsing", "保存浏览历史", "history"),
    SettingsSearchHit("browsing", "不显示 AI 生成的作品", "屏蔽 AI"),
    SettingsSearchHit("viewing", "详情面板默认折叠", "侧栏"),
    SettingsSearchHit("viewing", "详情页布局", "手机原样 默认模式 单列"),
    SettingsSearchHit("bookmarks", "默认私人收藏", "非公开"),
    SettingsSearchHit("download", "插画保存位置", "路径"),
    SettingsSearchHit("download", "文件名模板", "{illust_id}"),
    SettingsSearchHit("ai", "自定义 AI 翻译", "openai"),
    SettingsSearchHit("data", "缓存 / 临时文件位置", "cache"),
    SettingsSearchHit("data", "恢复默认设置", "reset"),
    SettingsSearchHit("experimental", "聊天室", "广场"),
)

@Composable
fun SettingsHubScreen(onOpenCategory: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("搜索或点分类进入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索设置项") },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (q.isBlank()) {
            SETTINGS_CATEGORIES.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { cat ->
                        SettingsCatCard(Modifier.weight(1f), cat) { onOpenCategory(cat.key) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            val hits = SETTINGS_SEARCH_INDEX.filter {
                it.title.contains(q, true) || it.keywords.contains(q, true)
            }.take(30)
            if (hits.isEmpty()) Text("没有匹配的设置项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            hits.forEach { hit ->
                val cat = SETTINGS_CATEGORIES.first { it.key == hit.categoryKey }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpenCategory(hit.categoryKey) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleMedium)
                        Text(cat.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCatCard(modifier: Modifier, cat: SettingsCategory, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(cat.tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(cat.icon, contentDescription = cat.title, tint = cat.tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(cat.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun SettingsCategoryScreen(
    graph: AppGraph,
    key: String,
    onLogout: () -> Unit,
    onOpen: (Dest) -> Unit = {},
) {
    val store = graph.settings
    val s = store.current
    fun set(next: DesktopSettings) = store.update { next }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(
            SETTINGS_CATEGORIES.firstOrNull { it.key == key }?.title ?: "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        when (key) {
            "account" -> SettingsGroup("Pixiv 账号") {
                LinkRow("账号管理", "打开多账号页，登录会自动入库") { onOpen(Dest.Accounts) }
                LinkRow("编辑账号信息与邮箱绑定", "在 Pixiv 网页修改") {
                    openUrl("https://accounts.pixiv.net/account-settings")
                }
                LinkRow("个人资料设置", "头像、昵称、简介") { openUrl("https://www.pixiv.net/setting_user.php") }
                LinkRow("我的作业环境", "电脑 / 手绘板") { openUrl("https://www.pixiv.net/setting_workspace.php") }
                LinkRow("R18 / R18G 设置", "网页开启后客户端才能拉到限制级") {
                    openUrl("https://www.pixiv.net/setting_user.php")
                }
                LinkRow("高级会员设置", "Pixiv Premium") { openUrl("https://www.pixiv.net/premium") }
                LinkRow("退出登录", "清除本机 token") { onLogout() }
            }
            "network" -> {
                SettingsGroup("连接") {
                    ToggleRow("开启直连", "默认开：API 走 Chromium QUIC，图片无 SNI。切换后立即重建网络栈，Clash 等代理请关掉。", s.directConnect) {
                        set(s.copy(directConnect = it))
                    }
                    ToggleRow("使用安全 DNS", "DoH / Cloudflare 固定 IP。关掉直连且关掉此项时改走系统 DNS。", s.useSecureDns) {
                        set(s.copy(useSecureDns = it))
                    }
                    LinkRow("网络测试", "DNS、QUIC、无 SNI、真实图片下载逐项体检") { onOpen(Dest.NetworkTest) }
                }
                SettingsGroup("图片") {
                    ChoiceRow("图片加速", listOf("官方", "pixiv.cat", "pixiv.re", "pixiv.nl", "自定义"), s.imageHostMode) {
                        set(s.copy(imageHostMode = it))
                    }
                    if (s.imageHostMode == 4) {
                        TextFieldRow("自定义反代", s.customImageHost) { set(s.copy(customImageHost = it)) }
                    }
                    ToggleRow("缩略图显示大图", "列表用 medium/large，更清晰也更费流量", s.showLargeThumbnailImage) {
                        set(s.copy(showLargeThumbnailImage = it))
                    }
                    ToggleRow("详情页显示原图", "关闭则详情用 large（约 1200px）", s.showOriginalPreviewImage) {
                        set(s.copy(showOriginalPreviewImage = it))
                    }
                }
            }
            "appearance" -> {
                SettingsGroup("外观") {
                    ChoiceRow("主题模式", listOf("跟随系统", "浅色", "深色"), s.themeMode) { set(s.copy(themeMode = it)) }
                    ChoiceRow("强调色", listOf("蓝", "紫", "青", "玫红", "琥珀", "绿", "橙", "洋红"), s.accentColor.coerceIn(0, 7)) {
                        set(s.copy(accentColor = it))
                    }
                    ToggleRow("自绘标题栏", "默认开。无边框窗口：拖拽 / 贴靠 / 边缘缩放，切换时重建窗口", s.customTitleBar) {
                        set(s.copy(customTitleBar = it))
                    }
                    ChoiceRow(
                        "语言 / Language",
                        listOf("跟随系统", "简体中文", "English", "日本語"),
                        when (s.appLocale) { "zh" -> 1; "en" -> 2; "ja" -> 3; else -> 0 },
                    ) {
                        set(s.copy(appLocale = listOf("", "zh", "en", "ja")[it]))
                    }
                    ToggleRow("紧凑界面", "更小间距与圆角", s.compactUi) { set(s.copy(compactUi = it)) }
                    ToggleRow("卡片标题叠在图上", "关闭则标题出现在卡片下方", s.showCardOverlay) {
                        set(s.copy(showCardOverlay = it))
                    }
                }
                SettingsGroup("布局") {
                    ChoiceRow(
                        "瀑布流列数",
                        listOf("自动", "1", "2", "3", "4", "5", "6", "7", "8"),
                        s.lineCount.coerceIn(0, 8),
                    ) {
                        set(s.copy(lineCount = it))
                    }
                    ToggleRow("瀑布流布局", "关闭则等宽网格", s.useStaggeredLayout) {
                        set(s.copy(useStaggeredLayout = it))
                    }
                    ChoiceRow(
                        "浏览模式",
                        listOf("瀑布流跳转", "左右分栏"),
                        s.browseLayout.coerceIn(0, 1),
                    ) {
                        set(s.copy(browseLayout = it))
                    }
                    InfoRow(
                        "左右分栏",
                        "对齐平板端：左边小瀑布流，点卡片后右侧直接显示作品详情，不必离开列表。",
                    )
                    ChoiceRow(
                        "启动页",
                        listOf("首页", "排行", "关注", "搜索", "我的"),
                        listOf("home", "ranking", "following", "search", "me").indexOf(s.navigationInitPosition).coerceAtLeast(0),
                    ) {
                        set(s.copy(navigationInitPosition = listOf("home", "ranking", "following", "search", "me")[it]))
                    }
                    ToggleRow("主页显示 R18", "排行榜出现 R18 模式", s.mainViewR18) { set(s.copy(mainViewR18 = it)) }
                    ToggleRow("小说卡片显示标签", "", s.showNovelCardTags) { set(s.copy(showNovelCardTags = it)) }
                    ToggleRow("小说卡片显示标签译文", "", s.showNovelCardTagTranslations) {
                        set(s.copy(showNovelCardTagTranslations = it))
                    }
                }
            }
            "browsing" -> SettingsGroup("过滤与搜索") {
                ToggleRow("冷启动自动刷新首页推荐", "", s.autoRefreshHomeFeed) { set(s.copy(autoRefreshHomeFeed = it)) }
                ToggleRow("保存浏览历史", "%APPDATA%\\PixShaft\\history.json", s.saveViewHistory) {
                    set(s.copy(saveViewHistory = it))
                }
                ToggleRow("过滤垃圾评论", "隐藏带链接 / 广告特征的评论", s.filterComment) { set(s.copy(filterComment = it)) }
                ToggleRow("默认开启 R18 内容过滤", "瀑布流不显示 R18", s.r18FilterDefaultEnable) {
                    set(s.copy(r18FilterDefaultEnable = it))
                }
                ToggleRow("不显示 AI 生成的作品", "", s.deleteAIIllust) { set(s.copy(deleteAIIllust = it)) }
                if (s.deleteAIIllust) {
                    ChoiceRow("AI 屏蔽强度", listOf("完全不显示", "仅标记"), s.aiBlockStrength) {
                        set(s.copy(aiBlockStrength = it))
                    }
                }
                ToggleRow("排行榜过滤已收藏", "", s.filterRankBookmarked) { set(s.copy(filterRankBookmarked = it)) }
                ToggleRow("搜索结果过滤已收藏", "", s.deleteStarIllust) { set(s.copy(deleteStarIllust = it)) }
                ChoiceRow(
                    "搜索默认排序",
                    listOf("时间降序", "时间升序", "热度"),
                    when (s.searchDefaultSortType) {
                        "date_asc" -> 1
                        "popular_desc" -> 2
                        else -> 0
                    },
                ) {
                    set(
                        s.copy(
                            searchDefaultSortType = when (it) {
                                1 -> "date_asc"
                                2 -> "popular_desc"
                                else -> "date_desc"
                            },
                        ),
                    )
                }
                ToggleRow("搜索默认按热度（Premium）", "", s.searchPopularDefault) {
                    set(s.copy(searchPopularDefault = it))
                }
                ToggleRow("同义词词典", "桌面仅保留开关", s.synonymDictEnabled) { set(s.copy(synonymDictEnabled = it)) }
            }
            "viewing" -> SettingsGroup("阅读") {
                ChoiceRow("详情页布局", listOf("默认模式", "手机原样"), s.detailStyle.coerceIn(0, 1)) {
                    set(s.copy(detailStyle = it))
                }
                InfoRow(
                    "详情页布局",
                    "默认模式：左图右资料，点图片可放大预览；手机原样：仿手机 App 的单列排版。",
                )
                ToggleRow("小说列表直接进正文", "略过详情页", s.novelListDirectToReader) {
                    set(s.copy(novelListDirectToReader = it))
                }
                ToggleRow("动图自动播放", "", s.autoPlayUgoira) { set(s.copy(autoPlayUgoira = it)) }
                ToggleRow("动图 RIFE 补帧", "调用本机 rife-ncnn-vulkan", s.ugoiraRifeEnable) {
                    set(s.copy(ugoiraRifeEnable = it))
                }
                ChoiceRow("动图保存格式", listOf("GIF", "MP4"), s.ugoiraSaveFormat) { set(s.copy(ugoiraSaveFormat = it)) }
                ToggleRow("详情面板默认折叠", "作品页先只看图", s.detailPanelCollapsedByDefault) {
                    set(s.copy(detailPanelCollapsedByDefault = it))
                }
                SettingsGroup("漫画阅读器") {
                    ChoiceRow("适应方式", listOf("适应宽度", "适应整页"), s.readerFit.coerceIn(0, 1)) {
                        set(s.copy(readerFit = it))
                    }
                    ToggleRow("日漫右开（从右往左翻页）", "方向键与点击区域随方向翻转", s.readerRtl) {
                        set(s.copy(readerRtl = it))
                    }
                    ToggleRow("深色背景", "阅读区使用纯深色底", s.readerDarkBg) {
                        set(s.copy(readerDarkBg = it))
                    }
                }
            }
            "bookmarks" -> SettingsGroup("互动") {
                ToggleRow("默认私人收藏", "收藏为非公开", s.privateStar) { set(s.copy(privateStar = it)) }
                ToggleRow("默认私人关注", "", s.privateFollow) { set(s.copy(privateFollow = it)) }
                ToggleRow("列表显示收藏按钮", "", s.showLikeButton) { set(s.copy(showLikeButton = it)) }
                ToggleRow("我的收藏列表隐藏收藏按钮", "", s.hideStarButtonAtMyCollection) {
                    set(s.copy(hideStarButtonAtMyCollection = it))
                }
                ToggleRow("收藏夹过滤已失效作品", "", s.filterInvalidBookmarks) {
                    set(s.copy(filterInvalidBookmarks = it))
                }
                ToggleRow("按标签收藏时全选标签", "", s.starWithTagSelectAll) { set(s.copy(starWithTagSelectAll = it)) }
                ToggleRow("收藏时展示相关作品", "", s.showRelatedWhenStar) { set(s.copy(showRelatedWhenStar = it)) }
                ToggleRow("收藏后自动关注作者", "", s.autoFollowAfterStar) { set(s.copy(autoFollowAfterStar = it)) }
                ToggleRow("收藏后自动下载", "", s.autoDownloadAfterStar) { set(s.copy(autoDownloadAfterStar = it)) }
                ToggleRow("下载时自动收藏", "", s.autoPostLikeWhenDownload) { set(s.copy(autoPostLikeWhenDownload = it)) }
            }
            "download" -> {
                SettingsGroup("位置") {
                    PathRow("插画保存位置", s.illustPath.ifBlank { WinPaths.defaultIllust() }) {
                        WinPaths.pickDirectory(s.illustPath.ifBlank { WinPaths.defaultIllust() })?.let { set(s.copy(illustPath = it)) }
                    }
                    PathRow("R18 保存位置", s.illustR18Path.ifBlank { WinPaths.defaultR18() }) {
                        WinPaths.pickDirectory(s.illustR18Path.ifBlank { WinPaths.defaultR18() })?.let { set(s.copy(illustR18Path = it)) }
                    }
                    PathRow("小说保存位置", s.novelPath.ifBlank { WinPaths.defaultNovel() }) {
                        WinPaths.pickDirectory(s.novelPath.ifBlank { WinPaths.defaultNovel() })?.let { set(s.copy(novelPath = it)) }
                    }
                    PathRow("动图保存位置", s.gifPath.ifBlank { WinPaths.defaultGif() }) {
                        WinPaths.pickDirectory(s.gifPath.ifBlank { WinPaths.defaultGif() })?.let { set(s.copy(gifPath = it)) }
                    }
                }
                SettingsGroup("命名与策略") {
                    TextFieldRow("文件名模板", s.illustFileName) { set(s.copy(illustFileName = it)) }
                    InfoRow("模板变量", "{illust_id} {title} {author} {index} {ext}")
                    ToggleRow("单图文件名带 _p0", "", s.hasP0) { set(s.copy(hasP0 = it)) }
                    ToggleRow("R18 下载到单独目录", "", s.r18DivideSave) { set(s.copy(r18DivideSave = it)) }
                    ToggleRow("AI 作品下载到单独目录", "", s.aiDivideSave) { set(s.copy(aiDivideSave = it)) }
                    ToggleRow("按作者分文件夹", "", s.saveForSeparateAuthor) { set(s.copy(saveForSeparateAuthor = it)) }
                    ChoiceRow("覆盖策略", listOf("跳过", "覆盖", "重命名"), s.overwritePolicy) { set(s.copy(overwritePolicy = it)) }
                    ChoiceRow("页码起始", listOf("从 0", "从 1"), s.pageIndexStart) { set(s.copy(pageIndexStart = it)) }
                    ChoiceRow("小说导出格式", listOf("txt", "epub", "pdf", "markdown"), listOf("txt", "epub", "pdf", "markdown").indexOf(s.defaultNovelFormat).coerceAtLeast(0)) {
                        set(s.copy(defaultNovelFormat = listOf("txt", "epub", "pdf", "markdown")[it]))
                    }
                    ChoiceRow("同时下载任务数", listOf("1", "2", "3", "4", "5"), (s.maxConcurrentDownloads - 1).coerceIn(0, 4)) {
                        set(s.copy(maxConcurrentDownloads = it + 1))
                    }
                    ToggleRow("提示下载结果", "", s.toastDownloadResult) { set(s.copy(toastDownloadResult = it)) }
                    ToggleRow("低调下载（回拨文件时间）", "", s.silentDownload) { set(s.copy(silentDownload = it)) }
                    TextFieldRow("aria2 RPC 地址", s.aria2RpcUrl) { set(s.copy(aria2RpcUrl = it, aria2Enabled = it.isNotBlank())) }
                }
            }
            "ai" -> SettingsGroup("端侧与翻译") {
                InfoRow("超分 / 抠图 / 补帧 / OCR", "把 realesrgan-ncnn-vulkan、rembg、rife-ncnn-vulkan、tesseract 放到 PATH 或 %APPDATA%\\PixShaft\\tools")
                ToggleRow("PixShaft 云翻译", "", s.cloudTranslateEnabled) { set(s.copy(cloudTranslateEnabled = it)) }
                ToggleRow("自定义 AI 翻译", "OpenAI 兼容接口", s.aiTranslateEnabled) { set(s.copy(aiTranslateEnabled = it)) }
                if (s.aiTranslateEnabled) {
                    TextFieldRow("Base URL", s.aiTranslateBaseUrl) { set(s.copy(aiTranslateBaseUrl = it)) }
                    TextFieldRow("API Key", s.aiTranslateApiKey) { set(s.copy(aiTranslateApiKey = it)) }
                    TextFieldRow("模型", s.aiTranslateModel) { set(s.copy(aiTranslateModel = it)) }
                    TextFieldRow("系统提示词", s.aiTranslatePrompt) { set(s.copy(aiTranslatePrompt = it)) }
                }
            }
            "data" -> {
                SettingsGroup("文件") {
                    PathRow("备份目录", s.backupPath.ifBlank { WinPaths.defaultBackup() }) {
                        WinPaths.pickDirectory(s.backupPath.ifBlank { WinPaths.defaultBackup() })?.let { set(s.copy(backupPath = it)) }
                    }
                    val cache = s.cachePath.ifBlank { WinPaths.defaultCache() }
                    PathRow(
                        title = "缓存 / 临时文件位置",
                        path = cache,
                        onOpen = { runCatching { Desktop.getDesktop().open(java.io.File(cache)) } },
                        onClear = { runCatching { AppPaths.clearDirectory(java.nio.file.Path.of(cache)) } },
                    ) {
                        WinPaths.pickDirectory(cache)?.let { picked ->
                            set(s.copy(cachePath = picked))
                            System.setProperty("java.io.tmpdir", picked)
                        }
                    }
                    InfoRow("缓存说明", "图片与 Chromium 磁盘缓存。登录 Cookie 在 chromium-login，清空不会登出。")
                    TextFieldRow("FANBOXSESSID cookie", s.fanboxCookie) { set(s.copy(fanboxCookie = it)) }
                    ToggleRow("收藏镜像到本地库", "", s.bookmarkMirrorEnabled) { set(s.copy(bookmarkMirrorEnabled = it)) }
                    ToggleRow("启用图片磁盘缓存", "关闭后图片只进内存缓存，不写盘", s.diskCacheEnabled) {
                        set(s.copy(diskCacheEnabled = it))
                    }
                    if (s.diskCacheEnabled) {
                        val presets = listOf(512, 1024, 2048, 5120, 10240)
                        val isCustom = s.diskCacheLimitMb !in presets
                        ChoiceRow(
                            "缓存大小上限",
                            listOf("512 MB", "1 GB", "2 GB", "5 GB", "10 GB", "自定义"),
                            if (isCustom) 5 else presets.indexOf(s.diskCacheLimitMb),
                        ) {
                            set(s.copy(diskCacheLimitMb = presets.getOrElse(it) { s.diskCacheLimitMb }))
                        }
                        if (isCustom) {
                            TextFieldRow("自定义上限（MB）", s.diskCacheLimitMb.toString()) { v ->
                                v.toIntOrNull()?.let { set(s.copy(diskCacheLimitMb = it.coerceIn(64, 102400))) }
                            }
                        }
                    }
                    LinkRow("打开 settings.json", AppPaths.settingsFile.toString()) {
                        runCatching { Desktop.getDesktop().open(AppPaths.settingsFile.toFile()) }
                    }
                    LinkRow("恢复默认设置", "网络直连、路径和主题会回到出厂值") { store.reset() }
                }
            }
            "experimental" -> SettingsGroup("实验") {
                ToggleRow("显示聊天室入口", "", s.showChatRoomEntry) { set(s.copy(showChatRoomEntry = it)) }
                ToggleRow("显示广场入口", "", s.showPlazaEntry) { set(s.copy(showPlazaEntry = it)) }
                ToggleRow("收藏时自动离线快照", "", s.autoSnapshotOnBookmark) { set(s.copy(autoSnapshotOnBookmark = it)) }
                ToggleRow("Firebase 统计", "桌面默认关闭", s.isFirebaseEnable) { set(s.copy(isFirebaseEnable = it)) }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (desc.isNotBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                FilterChip(selected = selected == index, onClick = { onSelect(index) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun PathRow(
    title: String,
    path: String,
    onOpen: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onPick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onPick) { Text("浏览…") }
            if (onOpen != null) TextButton(onClick = onOpen) { Text("打开") }
            if (onClear != null) TextButton(onClick = onClear) { Text("清空") }
        }
    }
}

@Composable
private fun TextFieldRow(title: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        label = { Text(title) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun LinkRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (desc.isNotBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun InfoRow(title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
