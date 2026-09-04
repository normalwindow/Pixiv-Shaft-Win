package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.model.HomeIllustResponse
import ceui.pixshaft.shared.model.IllustResponse
import ceui.pixshaft.shared.model.SingleIllustResponse
import ceui.pixshaft.shared.model.UserDetail
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
}
