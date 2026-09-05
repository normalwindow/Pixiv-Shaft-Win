package ceui.pixshaft.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** 应用语言："" 跟随系统，zh / en / ja。 */
val LocalAppLocale = staticCompositionLocalOf { "" }

class L10n private constructor(
    val locale: String,
    val values: Map<String, String>,
) {
    operator fun get(key: String): String = values[key] ?: ZH[key] ?: key

    companion object {
        val ZH = mapOf(
            "home" to "首页", "ranking" to "排行", "following" to "关注", "search" to "搜索",
            "me" to "我的", "download" to "下载", "settings" to "设置", "menu" to "菜单",
            "back" to "返回", "hideSidebar" to "隐藏侧栏", "fullscreen" to "全屏", "exitFullscreen" to "退出全屏",
            "illust" to "插画", "manga" to "漫画", "novel" to "小说", "all" to "全部",
            "public" to "公开", "private" to "私人", "bookmarks" to "关注",
            "illustManga" to "插画 · 漫画",
            "bookmark" to "收藏", "bookmarked" to "已收藏", "downloadAction" to "下载",
            "addToBatch" to "加入批量下载", "addFeature" to "收入精华列", "viewArtist" to "查看画师",
            "viewDetail" to "查看详情", "copyIllustId" to "复制作品 ID", "copyUserId" to "复制作者 ID",
            "openInBrowser" to "在浏览器打开", "hide" to "不感兴趣（隐藏）", "copied" to "已复制",
            "hotTags" to "热门标签", "trending" to "搜索发现", "searchHistory" to "搜索历史",
            "refresh" to "刷新", "follow" to "关注", "followingBtn" to "已关注",
            "filter" to "筛选", "newest" to "最新", "oldest" to "最旧", "popular" to "热度",
            "loginTitle" to "登录 Pixiv",
            "mangaReader" to "漫画阅读器", "prevPage" to "上一页", "nextPage" to "下一页",
            "fitWidth" to "适应宽度", "fitPage" to "适应整页", "rtl" to "日漫右开", "ltr" to "从左到右",
            "darkBg" to "深色背景", "lightBg" to "浅色背景",
            "downloadQueue" to "下载管理", "batchDownload" to "批量下载",
            "usersFollowing" to "关注的用户", "myPixiv" to "好P友",
            "discovery" to "发现", "plaza" to "探索广场",
        )
        val EN = mapOf(
            "home" to "Home", "ranking" to "Ranking", "following" to "Following", "search" to "Search",
            "me" to "Me", "download" to "Downloads", "settings" to "Settings", "menu" to "Menu",
            "back" to "Back", "hideSidebar" to "Hide sidebar", "fullscreen" to "Fullscreen", "exitFullscreen" to "Exit fullscreen",
            "illust" to "Illustrations", "manga" to "Manga", "novel" to "Novels", "all" to "All",
            "public" to "Public", "private" to "Private", "bookmarks" to "Bookmarks",
            "illustManga" to "Illust · Manga",
            "bookmark" to "Bookmark", "bookmarked" to "Bookmarked", "downloadAction" to "Download",
            "addToBatch" to "Add to batch", "addFeature" to "Add to favorites", "viewArtist" to "View artist",
            "viewDetail" to "Details", "copyIllustId" to "Copy artwork ID", "copyUserId" to "Copy artist ID",
            "openInBrowser" to "Open in browser", "hide" to "Not interested", "copied" to "Copied",
            "hotTags" to "Trending tags", "trending" to "Discover", "searchHistory" to "Search history",
            "refresh" to "Refresh", "follow" to "Follow", "followingBtn" to "Following",
            "filter" to "Filter", "newest" to "Newest", "oldest" to "Oldest", "popular" to "Popular",
            "loginTitle" to "Sign in to Pixiv",
            "mangaReader" to "Manga reader", "prevPage" to "Previous", "nextPage" to "Next",
            "fitWidth" to "Fit width", "fitPage" to "Fit page", "rtl" to "Right-to-left", "ltr" to "Left-to-right",
            "darkBg" to "Dark background", "lightBg" to "Light background",
            "downloadQueue" to "Downloads", "batchDownload" to "Batch download",
            "usersFollowing" to "Following users", "myPixiv" to "My Pixiv friends",
            "discovery" to "Discovery", "plaza" to "Plaza",
        )
        val JA = mapOf(
            "home" to "ホーム", "ranking" to "ランキング", "following" to "フォロー新着", "search" to "検索",
            "me" to "マイページ", "download" to "ダウンロード", "settings" to "設定", "menu" to "メニュー",
            "back" to "戻る", "hideSidebar" to "サイドバーを隠す", "fullscreen" to "全画面", "exitFullscreen" to "全画面解除",
            "illust" to "イラスト", "manga" to "漫画", "novel" to "小説", "all" to "すべて",
            "public" to "公開", "private" to "非公開", "bookmarks" to "ブックマーク",
            "illustManga" to "イラスト · 漫画",
            "bookmark" to "ブックマーク", "bookmarked" to "済み", "downloadAction" to "ダウンロード",
            "addToBatch" to "一括DLに追加", "addFeature" to "コレクションへ", "viewArtist" to "作者を見る",
            "viewDetail" to "詳細", "copyIllustId" to "作品IDをコピー", "copyUserId" to "作者IDをコピー",
            "openInBrowser" to "ブラウザで開く", "hide" to "興味なし", "copied" to "コピーしました",
            "hotTags" to "人気タグ", "trending" to "見つける", "searchHistory" to "検索履歴",
            "refresh" to "更新", "follow" to "フォロー", "followingBtn" to "フォロー中",
            "filter" to "フィルタ", "newest" to "新しい", "oldest" to "古い", "popular" to "人気",
            "loginTitle" to "Pixiv にログイン",
            "mangaReader" to "漫画ビューア", "prevPage" to "前へ", "nextPage" to "次へ",
            "fitWidth" to "幅に合わせる", "fitPage" to "全体表示", "rtl" to "右開き", "ltr" to "左開き",
            "darkBg" to "暗い背景", "lightBg" to "明るい背景",
            "downloadQueue" to "ダウンロード管理", "batchDownload" to "一括ダウンロード",
            "usersFollowing" to "フォロー中のユーザー", "myPixiv" to "マイピク",
            "discovery" to "発見", "plaza" to "プラザ",
        )

        fun forLocale(locale: String): L10n {
            val resolved = when (locale) {
                "zh", "en", "ja" -> locale
                "" -> java.util.Locale.getDefault().language.let { if (it in setOf("en", "ja")) it else "zh" }
                else -> "zh"
            }
            return when (resolved) {
                "en" -> L10n("en", EN)
                "ja" -> L10n("ja", JA)
                else -> L10n("zh", ZH)
            }
        }
    }
}

/** 当前语言包。 */
@Composable
fun tr(): L10n {
    val locale = LocalAppLocale.current
    return remember(locale) { L10n.forLocale(locale) }
}
