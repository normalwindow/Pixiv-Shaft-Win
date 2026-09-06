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
        square_medium ?: medium ?: large ?: px_170x170 ?: small ?: url

    fun display(): String? =
        large ?: medium ?: square_medium ?: url

    fun originalOrLarge(): String? =
        original ?: large ?: medium ?: square_medium ?: url

    fun hero(): String? = originalOrLarge()
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

    fun previewUrl(large: Boolean = false): String? =
        if (large) image_urls?.display() else image_urls?.thumbnail()

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
        val pages = meta_pages?.mapNotNull { it.image_urls?.originalOrLarge() }.orEmpty()
        return pages.ifEmpty { listOfNotNull(heroUrl()) }
    }

    fun viewUrls(original: Boolean = false): List<String> {
        if (original) return pageUrls()
        if (page_count <= 1) {
            return listOfNotNull(image_urls?.display() ?: heroUrl())
        }
        val pages = meta_pages?.mapNotNull { it.image_urls?.display() }.orEmpty()
        return pages.ifEmpty { listOfNotNull(image_urls?.display() ?: heroUrl()) }
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

data class Novel(
    val id: Long = 0L,
    val title: String? = null,
    val caption: String? = null,
    val text_length: Int = 0,
    val create_date: String? = null,
    val total_bookmarks: Int? = null,
    val total_view: Int? = null,
    val is_bookmarked: Boolean? = null,
    val image_urls: ImageUrls? = null,
    val tags: List<Tag>? = null,
    val user: User? = null,
) {
    val isBookmarked: Boolean get() = is_bookmarked == true
    fun cover(): String? = image_urls?.medium ?: image_urls?.square_medium ?: image_urls?.large
}

data class NovelResponse(
    val novels: List<Novel> = emptyList(),
    val next_url: String? = null,
)

data class SingleNovelResponse(
    val novel: Novel? = null,
)

data class NovelTextResponse(
    val novel_text: String? = null,
    val text: String? = null,
) {
    fun body(): String = novel_text ?: text.orEmpty()
}

data class CommentStamp(
    val stamp_id: Long = 0L,
    val stamp_url: String? = null,
)

data class Comment(
    val id: Long = 0L,
    val comment: String? = null,
    val date: String? = null,
    val user: User? = null,
    val stamp: CommentStamp? = null,
    val has_replies: Boolean = false,
) {
    fun bodyText(): String = comment.orEmpty().ifBlank {
        if (stamp != null) "[stamp]" else ""
    }
}

data class CommentResponse(
    val comments: List<Comment> = emptyList(),
    val next_url: String? = null,
)

data class PostCommentResponse(
    val comment: Comment? = null,
)

data class TrendingTag(
    val tag: String? = null,
    val translated_name: String? = null,
    val illust: Illust? = null,
) {
    fun display(): String = translated_name?.takeIf { it.isNotBlank() } ?: tag.orEmpty()
}

data class TrendingTagResponse(
    val trend_tags: List<TrendingTag> = emptyList(),
)

data class UserPreview(
    val user: User? = null,
    val illusts: List<Illust>? = null,
)

data class UserPreviewResponse(
    val user_previews: List<UserPreview> = emptyList(),
    val next_url: String? = null,
)

data class UgoiraZipUrls(val medium: String? = null)
data class UgoiraFrame(val file: String? = null, val delay: Int = 120)
data class UgoiraMetadata(val zip_urls: UgoiraZipUrls? = null, val frames: List<UgoiraFrame> = emptyList())
data class UgoiraResponse(val ugoira_metadata: UgoiraMetadata? = null)

data class FanboxUser(val userId: String? = null, val name: String? = null, val iconUrl: String? = null)
data class FanboxCover(val url: String? = null)
data class FanboxPost(
    val id: String? = null,
    val title: String? = null,
    val excerpt: String? = null,
    val feeRequired: Int? = null,
    val publishedDatetime: String? = null,
    val creatorId: String? = null,
    val user: FanboxUser? = null,
    val cover: FanboxCover? = null,
    val coverImageUrl: String? = null,
    val type: String? = null,
)
data class FanboxPostList(val items: List<FanboxPost>? = null, val nextUrl: String? = null)
data class FanboxPostListResponse(val body: FanboxPostList? = null)
data class FanboxCreator(val creatorId: String? = null, val user: FanboxUser? = null, val description: String? = null)
data class FanboxCreatorList(val creators: List<FanboxCreator>? = null)
data class FanboxCreatorListResponse(val body: FanboxCreatorList? = null)
data class FanboxPostWrapper(val post: FanboxPost? = null)
data class FanboxPostDetailResponse(val body: FanboxPostWrapper? = null)

data class ComicBanner(val id: Long = 0, val image_url: String? = null, val url: String? = null)
data class ComicWork(
    val id: Long = 0,
    val title: String? = null,
    val author: String? = null,
    val thumbnail_image_url: String? = null,
    val main_image_url: String? = null,
    val stories_count: Int = 0,
)
data class ComicTopData(val banners: List<ComicBanner>? = null, val recent_updated_official_works: List<ComicWork>? = null)
data class ComicTopResponse(val data: ComicTopData? = null)

data class ChatReplyTo(
    val uid: Long = 0,
    val client_msg_id: String? = null,
    val display_name: String? = null,
    val text: String? = null,
)
data class ChatHistoryItem(
    val id: Long = 0,
    val uid: Long = 0,
    val client_msg_id: String? = null,
    val display_name: String? = null,
    val text: String? = null,
    val illust_id: Long? = null,
    val ts: Long = 0,
    val reply_to: ChatReplyTo? = null,
)
data class ChatHistoryResponse(
    val room: String? = null,
    val items: List<ChatHistoryItem> = emptyList(),
)
data class ChatStatsResponse(
    val room: String? = null,
    val online: Int = 0,
    val total_connections: Int? = null,
    val total_messages: Long = 0,
)
