package ceui.pixshaft.shared.model

/** shaft-plaza-api 的帖子模型，1:1 对齐上游（snake_case）。 */
data class PlazaPost(
    val id: Long = 0,
    val uid: Long = 0,
    val display_name: String? = null,
    val text: String = "",
    val ts: Long = 0,
    val refs: PlazaPostRefs = PlazaPostRefs(),
    val like_count: Int = 0,
    val comment_count: Int = 0,
    val liked_by_viewer: Boolean? = null,
)

data class PlazaPostRefs(
    val illust: List<PlazaIllustRef> = emptyList(),
    val novel: List<PlazaNovelRef> = emptyList(),
    val user: List<PlazaUserRef> = emptyList(),
)

data class PlazaIllustRef(val id: Long = 0, val meta: PlazaIllustMeta? = null)
data class PlazaIllustMeta(
    val target_id: Long = 0,
    val title: String? = null,
    val user_id: Long? = null,
    val user_name: String? = null,
    val thumb_url: String? = null,
)

data class PlazaNovelRef(val id: Long = 0, val meta: PlazaNovelMeta? = null)
data class PlazaNovelMeta(
    val target_id: Long = 0,
    val title: String? = null,
    val user_id: Long? = null,
    val user_name: String? = null,
)

data class PlazaUserRef(val id: Long = 0, val meta: PlazaUserMeta? = null)
data class PlazaUserMeta(
    val target_id: Long = 0,
    val name: String? = null,
    val account: String? = null,
    val avatar_url: String? = null,
)

data class PlazaFeedResponse(
    val items: List<PlazaPost> = emptyList(),
    val next_before: Long? = null,
)

data class PlazaLikeResponse(
    val ok: Boolean = false,
    val added: Boolean? = null,
    val removed: Boolean? = null,
    val like_count: Int = 0,
)
