package ceui.pixshaft.desktop.ui

sealed class Dest {
    data object Home : Dest()
    data object Ranking : Dest()
    data object Following : Dest()
    data class Search(val query: String = "") : Dest()
    data object Me : Dest()
    data class Artwork(val id: Long) : Dest()
    data class Related(val id: Long) : Dest()
    data class User(val id: Long) : Dest()
    data object Bookmarks : Dest()
    data object Newest : Dest()
    data object History : Dest()
    data object Novels : Dest()
    data class Novel(val id: Long) : Dest()
    data object FollowingUsers : Dest()
    data object Settings : Dest()
    data class SettingsCategory(val key: String) : Dest()
    data object Fanbox : Dest()
    data class FanboxPost(val id: String) : Dest()
    data object Comic : Dest()
    data class MangaReader(val id: Long) : Dest()
    data object Chat : Dest()
    data object ReverseSearch : Dest()
    data object Library : Dest()
    data object Queue : Dest()
    data object Ai : Dest()
    data object Accounts : Dest()
    data object NovelBookmarks : Dest()
    data object WatchLater : Dest()
    data object Pinned : Dest()
    data object Feature : Dest()
    data object Watchlist : Dest()
    data object NovelMarkers : Dest()
    data object Fans : Dest()
    data object Usage : Dest()
    data object Snapshots : Dest()
    data object Notifications : Dest()
    data object Muted : Dest()
    data object EventHistory : Dest()
    data object About : Dest()
    data object Discovery : Dest()
    data object LocalNovels : Dest()
    data object Plaza : Dest()
    data object BulkDebug : Dest()
    data object SafTest : Dest()
    data object NetworkTest : Dest()
    data object WebHome : Dest()
}

enum class RailTab { Home, Ranking, Following, Search, Me, Download, Settings }

fun Dest.railTab(): RailTab = when (this) {
    Dest.Home -> RailTab.Home
    Dest.Ranking -> RailTab.Ranking
    Dest.Following -> RailTab.Following
    is Dest.Search -> RailTab.Search
    Dest.Settings, is Dest.SettingsCategory -> RailTab.Settings
    Dest.Queue -> RailTab.Download
    Dest.Me, is Dest.User, Dest.Bookmarks, Dest.History, Dest.Novels,
    Dest.Newest, Dest.FollowingUsers, is Dest.Novel,
    Dest.Fanbox, is Dest.FanboxPost, Dest.Comic, is Dest.MangaReader, Dest.Chat,
    Dest.ReverseSearch, Dest.Library, Dest.Ai, Dest.Accounts,
    Dest.NovelBookmarks, Dest.WatchLater, Dest.Pinned, Dest.Feature, Dest.Watchlist,
    Dest.NovelMarkers, Dest.Fans, Dest.Usage, Dest.Snapshots, Dest.Notifications,
    Dest.Muted, Dest.EventHistory, Dest.About, Dest.Discovery, Dest.LocalNovels,
    Dest.Plaza, Dest.BulkDebug, Dest.SafTest, Dest.NetworkTest, Dest.WebHome -> RailTab.Me
    is Dest.Artwork, is Dest.Related -> RailTab.Home
}

fun Dest.supportsSplitBrowse(): Boolean = when (this) {
    Dest.Home, Dest.Ranking, Dest.Following, Dest.Bookmarks, Dest.Newest, Dest.History, Dest.Library -> true
    is Dest.Search, is Dest.User, is Dest.Related -> true
    else -> false
}

/**
 * 有"页面数据 + 滚动位置"需要跨导航保留的页面返回缓存键；null 表示不保留。
 * 命中键的页面通过 FeedStore（数据）+ SaveableStateHolder（滚动位置）在切页/返回时保持原样。
 */
fun Dest.keepAliveKey(): String? = when (this) {
    Dest.Home -> "home"
    Dest.Ranking -> "ranking"
    Dest.Following -> "following"
    is Dest.Search -> "search:${query}"
    Dest.Bookmarks -> "bookmarks"
    Dest.Newest -> "newest"
    Dest.History -> "history"
    Dest.Novels -> "novels"
    Dest.NovelBookmarks -> "novel-bookmarks"
    Dest.Library -> "library"
    is Dest.Related -> "related:$id"
    is Dest.User -> "user:$id"
    else -> null
}
