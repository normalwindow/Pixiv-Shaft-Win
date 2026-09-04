package ceui.pixshaft.shared.model

/**
 * JVM-safe subset of the Android `models` / `ceui.pixiv.api.model` types.
 * Field names match app-api JSON so Gson can parse the same payloads.
 * Do not convert `:models` in place — port more types here as desktop screens need them.
 */

data class ImageUrls(
    val url: String? = null,
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val square_medium: String? = null,
    val small: String? = null,
    val px_16x16: String? = null,
    val px_50x50: String? = null,
    val px_170x170: String? = null,
) {
    fun thumbnail(): String? =
        square_medium ?: medium ?: large ?: px_170x170 ?: small ?: original ?: url

    fun hero(): String? =
        original ?: large ?: medium ?: square_medium ?: url
}

data class User(
    val id: Long = 0L,
    val name: String? = null,
    val account: String? = null,
    val comment: String? = null,
    val is_followed: Boolean? = null,
    val is_premium: Boolean? = null,
    val profile_image_urls: ImageUrls? = null,
    val x_restrict: Int? = null,
    val mail_address: String? = null,
) {
    fun exist(): Boolean = !name.isNullOrEmpty() || !account.isNullOrEmpty() || id != 0L
    fun avatar(): String? = profile_image_urls?.medium
        ?: profile_image_urls?.px_170x170
        ?: profile_image_urls?.square_medium
}

data class Tag(
    val name: String? = null,
    val translated_name: String? = null,
) {
    fun display(): String = translated_name?.takeIf { it.isNotBlank() } ?: name.orEmpty()
}

data class Series(
    val id: Long = 0L,
    val title: String? = null,
)

data class MetaPage(
    val image_urls: ImageUrls? = null,
)

data class MetaSinglePage(
    val original_image_url: String? = null,
)

data class Illust(
    val id: Long = 0L,
    val title: String? = null,
    val caption: String? = null,
    val type: String? = null,
    val create_date: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val page_count: Int = 0,
    val restrict: Int? = null,
    val x_restrict: Int? = null,
    val sanity_level: Int? = null,
    val illust_ai_type: Int = 0,
    val total_view: Int? = null,
    val total_bookmarks: Int? = null,
    val is_bookmarked: Boolean? = null,
    val is_muted: Boolean? = null,
    val visible: Boolean? = null,
    val image_urls: ImageUrls? = null,
    val meta_single_page: MetaSinglePage? = null,
    val meta_pages: List<MetaPage>? = null,
    val tags: List<Tag>? = null,
    val tools: List<String>? = null,
    val user: User? = null,
    val series: Series? = null,
) {
    fun isR18(): Boolean = (x_restrict ?: 0) > 0
    fun isAi(): Boolean = illust_ai_type == 2
    fun isGif(): Boolean = type == "ugoira"
    fun isManga(): Boolean = type == "manga" || page_count > 1
    val isBookmarked: Boolean get() = is_bookmarked == true

    fun previewUrl(): String? = image_urls?.thumbnail()

    fun heroUrl(): String? {
        if (page_count <= 1) {
            return meta_single_page?.original_image_url ?: image_urls?.hero()
        }
        return meta_pages?.firstOrNull()?.image_urls?.hero() ?: image_urls?.hero()
    }

    fun pageUrls(): List<String> {
        if (page_count <= 1) {
            return listOfNotNull(heroUrl())
        }
        val pages = meta_pages?.mapNotNull { it.image_urls?.hero() }.orEmpty()
        return pages.ifEmpty { listOfNotNull(heroUrl()) }
    }
}

data class IllustResponse(
    val illusts: List<Illust> = emptyList(),
    val next_url: String? = null,
)

data class HomeIllustResponse(
    val illusts: List<Illust> = emptyList(),
    val ranking_illusts: List<Illust> = emptyList(),
    val next_url: String? = null,
)

data class SingleIllustResponse(
    val illust: Illust? = null,
)

data class Profile(
    val webpage: String? = null,
    val gender: String? = null,
    val birth: String? = null,
    val region: String? = null,
    val job: String? = null,
    val total_follow_users: Int? = null,
    val total_mypixiv_users: Int? = null,
    val total_illusts: Int = 0,
    val total_manga: Int = 0,
    val total_novels: Int? = null,
    val total_illust_bookmarks_public: Int = 0,
    val background_image_url: String? = null,
    val twitter_account: String? = null,
    val is_premium: Boolean? = null,
)

data class UserDetail(
    val user: User? = null,
    val profile: Profile? = null,
)

data class TokenUser(
    val id: Long = 0L,
    val name: String? = null,
    val account: String? = null,
    val mail_address: String? = null,
    val is_premium: Boolean? = null,
    val x_restrict: Int? = null,
    val profile_image_urls: ImageUrls? = null,
) {
    fun toUser(): User = User(
        id = id,
        name = name,
        account = account,
        is_premium = is_premium,
        x_restrict = x_restrict,
        mail_address = mail_address,
        profile_image_urls = profile_image_urls,
    )
}

data class TokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val expires_in: Int? = null,
    val token_type: String? = null,
    val scope: String? = null,
    val user: TokenUser? = null,
)

data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val user: User?,
)
