package ceui.pixshaft.desktop.ui

sealed class Dest {
    data object Home : Dest()
    data object Ranking : Dest()
    data object Following : Dest()
    data class Search(val query: String = "") : Dest()
    data object Me : Dest()
    data class Artwork(val id: Long) : Dest()
    data class User(val id: Long) : Dest()
}

enum class RailTab { Home, Ranking, Following, Search, Me }

fun Dest.railTab(): RailTab = when (this) {
    Dest.Home -> RailTab.Home
    Dest.Ranking -> RailTab.Ranking
    Dest.Following -> RailTab.Following
    is Dest.Search -> RailTab.Search
    Dest.Me, is Dest.User -> RailTab.Me
    is Dest.Artwork -> RailTab.Home
}
