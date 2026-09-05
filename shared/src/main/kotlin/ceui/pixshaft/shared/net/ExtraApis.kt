package ceui.pixshaft.shared.net

import ceui.pixshaft.shared.model.ChatHistoryResponse
import ceui.pixshaft.shared.model.ChatStatsResponse
import ceui.pixshaft.shared.model.ComicTopResponse
import ceui.pixshaft.shared.model.FanboxCreatorListResponse
import ceui.pixshaft.shared.model.FanboxPostDetailResponse
import ceui.pixshaft.shared.model.FanboxPostListResponse
import ceui.pixshaft.shared.model.PlazaFeedResponse
import ceui.pixshaft.shared.model.PlazaLikeResponse
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface FanboxApi {
    @GET("post.listHome")
    suspend fun postListHome(@Query("limit") limit: Int = 20): FanboxPostListResponse

    @GET
    suspend fun postListHomeByUrl(@Url url: String): FanboxPostListResponse

    @GET("creator.listRecommended")
    suspend fun creatorListRecommended(@Query("limit") limit: Int = 20): FanboxCreatorListResponse

    @GET("post.get")
    suspend fun postGet(@Query("postId") postId: String): FanboxPostDetailResponse
}

interface ComicApi {
    @GET("api/app/top/v8")
    suspend fun getComicTop(): ComicTopResponse
}

interface ShaftChatApi {
    @GET("/api/v1/chat/history")
    suspend fun history(
        @Query("room") room: String = "global",
        @Query("limit") limit: Int = 50,
        @Query("before") before: Long? = null,
    ): ChatHistoryResponse

    /** Debug/observability — current room online count + total message count. */
    @GET("/api/v1/chat/stats")
    suspend fun stats(
        @Query("room") room: String = "global",
    ): ChatStatsResponse
}

/** 探索广场（shaft-plaza-api）。读操作无需签名；带 viewer sig 时才返回 liked_by_viewer。 */
interface PlazaApi {
    @GET("api/v1/plaza/posts")
    suspend fun feed(
        @Query("before") before: Long? = null,
        @Query("limit") limit: Int = 30,
    ): PlazaFeedResponse

    @POST("api/v1/plaza/posts/{id}/like")
    suspend fun like(@Path("id") id: Long): PlazaLikeResponse

    @DELETE("api/v1/plaza/posts/{id}/like")
    suspend fun unlike(@Path("id") id: Long): PlazaLikeResponse
}
