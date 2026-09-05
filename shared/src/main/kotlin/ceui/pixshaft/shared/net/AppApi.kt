package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.model.CommentResponse
import ceui.pixshaft.shared.model.HomeIllustResponse
import ceui.pixshaft.shared.model.IllustResponse
import ceui.pixshaft.shared.model.NovelResponse
import ceui.pixshaft.shared.model.NovelTextResponse
import ceui.pixshaft.shared.model.SingleIllustResponse
import ceui.pixshaft.shared.model.SingleNovelResponse
import ceui.pixshaft.shared.model.TrendingTagResponse
import ceui.pixshaft.shared.model.UserDetail
import ceui.pixshaft.shared.model.UserPreviewResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface AppApi {
    @GET("/v1/{type}/recommended?include_ranking_illusts=true&include_privacy_policy=true&filter=for_ios")
    suspend fun recommended(
        @Path("type") type: String,
    ): HomeIllustResponse

    @GET("/v1/illust/ranking?filter=for_ios")
    suspend fun ranking(
        @Query("mode") mode: String,
        @Query("date") date: String? = null,
    ): IllustResponse

    @GET("/v1/search/illust")
    suspend fun searchIllust(
        @Query("word") word: String,
        @Query("sort") sort: String = "date_desc",
        @Query("search_target") searchTarget: String? = "partial_match_for_tags",
        @Query("merge_plain_keyword_results") mergePlain: Boolean = true,
        @Query("include_translated_tag_results") includeTranslated: Boolean = true,
        @Query("filter") filter: String = "for_ios",
    ): IllustResponse

    @GET("/v1/illust/detail")
    suspend fun illustDetail(
        @Query("illust_id") illustId: Long,
    ): SingleIllustResponse

    @GET("/v2/user/detail?filter=for_ios")
    suspend fun userDetail(
        @Query("user_id") userId: Long,
    ): UserDetail

    @GET("/v1/user/illusts?filter=for_ios")
    suspend fun userIllusts(
        @Query("user_id") userId: Long,
        @Query("type") type: String = "illust",
    ): IllustResponse

    @GET("/v2/illust/follow")
    suspend fun following(
        @Query("restrict") restrict: String = "all",
    ): IllustResponse

    @GET("/v2/illust/related")
    suspend fun related(
        @Query("illust_id") illustId: Long,
    ): IllustResponse

    @FormUrlEncoded
    @POST("/v2/illust/bookmark/add")
    suspend fun addBookmark(
        @Field("illust_id") illustId: Long,
        @Field("restrict") restrict: String = "public",
    )

    @FormUrlEncoded
    @POST("/v1/illust/bookmark/delete")
    suspend fun removeBookmark(
        @Field("illust_id") illustId: Long,
    )

    @FormUrlEncoded
    @POST("/v1/user/follow/add")
    suspend fun followUser(
        @Field("user_id") userId: Long,
        @Field("restrict") restrict: String = "public",
    )

    @FormUrlEncoded
    @POST("/v1/user/follow/delete")
    suspend fun unfollowUser(
        @Field("user_id") userId: Long,
    )

    @GET
    suspend fun nextIllusts(@Url url: String): IllustResponse

    @GET("/v1/user/bookmarks/illust?filter=for_ios")
    suspend fun userBookmarks(
        @Query("user_id") userId: Long,
        @Query("restrict") restrict: String = "public",
    ): IllustResponse

    @GET("/v1/trending-tags/{type}?filter=for_ios&include_translated_tag_results=true")
    suspend fun trendingTags(
        @Path("type") type: String,
    ): TrendingTagResponse

    @GET("/v1/illust/new?filter=for_ios")
    suspend fun newWorks(
        @Query("content_type") contentType: String = "illust",
    ): IllustResponse

    @GET("/v2/illust/comments")
    suspend fun comments(
        @Query("illust_id") illustId: Long,
    ): CommentResponse

    @GET("/v1/novel/recommended?include_ranking_novels=true&filter=for_ios")
    suspend fun recommendedNovels(): NovelResponse

    @GET("/v1/novel/follow")
    suspend fun novelFollowing(
        @Query("restrict") restrict: String = "all",
    ): NovelResponse

    @GET("/v2/novel/detail")
    suspend fun novelDetail(
        @Query("novel_id") novelId: Long,
    ): SingleNovelResponse

    @GET("/v1/novel/text")
    suspend fun novelText(
        @Query("novel_id") novelId: Long,
    ): NovelTextResponse

    @GET("/v1/user/novels")
    suspend fun userNovels(
        @Query("user_id") userId: Long,
    ): NovelResponse

    @GET("/v1/search/novel?filter=for_ios&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun searchNovel(
        @Query("word") word: String,
        @Query("sort") sort: String = "date_desc",
        @Query("search_target") searchTarget: String? = "partial_match_for_tags",
    ): NovelResponse

    @GET("/v1/user/following?filter=for_ios")
    suspend fun followingUsers(
        @Query("user_id") userId: Long,
        @Query("restrict") restrict: String = "public",
    ): UserPreviewResponse

    /** 某位用户的好 P 友（互关好友）。 */
    @GET("/v1/user/mypixiv?filter=for_android")
    suspend fun userMyPixiv(
        @Query("user_id") userId: Long,
    ): UserPreviewResponse

    /** 好 P 友（互关好友）的最新插画 / 漫画。 */
    @GET("/v2/illust/mypixiv")
    suspend fun myPixivWorks(): IllustResponse

    @GET("/v1/user/follower?filter=for_ios")
    suspend fun userFollowers(
        @Query("user_id") userId: Long,
    ): UserPreviewResponse

    @GET("/v1/user/bookmarks/novel")
    suspend fun userNovelBookmarks(
        @Query("user_id") userId: Long,
        @Query("restrict") restrict: String = "public",
    ): NovelResponse

    @GET("/v1/user/recommended?filter=for_ios")
    suspend fun recommendedUsers(): UserPreviewResponse

    @GET
    suspend fun nextNovels(@Url url: String): NovelResponse

    @GET
    suspend fun nextUsers(@Url url: String): UserPreviewResponse

    @GET
    suspend fun nextComments(@Url url: String): CommentResponse

    @GET("/v1/ugoira/metadata")
    suspend fun ugoiraMetadata(
        @Query("illust_id") illustId: Long,
    ): ceui.pixshaft.shared.model.UgoiraResponse
}
