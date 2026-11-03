package com.lingxi.classic.data.model

import com.google.gson.annotations.SerializedName

/** 通用 API 响应包装：code==0 成功，失败时 message 可直接展示 */
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null
) {
    val isSuccess: Boolean get() = code == 0
}

/** 分页数据包装 */
data class PageData<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false
)

/** 仅含 items 的列表响应 */
data class ItemList<T>(
    val items: List<T> = emptyList()
)

/** 拼接资源地址 */
internal fun joinUrl(baseUrl: String, path: String): String {
    if (path.isEmpty()) return ""
    if (path.startsWith("http")) return path
    return if (path.startsWith("/")) baseUrl + path else "$baseUrl/$path"
}

/** 通用操作响应 */
data class ActionResult(
    val ok: Boolean = false,
    val id: Long = 0,
    val message: String = ""
)

// ==================== 视频/稿件 ====================

data class Video(
    val id: Long = 0,
    val uid: Long = 0,
    val title: String = "",
    val description: String = "",
    /** 文章正文（Markdown，kind=article 时才有内容） */
    val content: String = "",
    val cover: String = "",
    val src: String = "",
    val duration: Long = 0,
    val partition: String = "",
    val tags: List<String> = emptyList(),
    val viewCount: Int = 0,
    val likeCount: Int = 0,
    val coinCount: Int = 0,
    val favCount: Int = 0,
    val danmakuCount: Int = 0,
    val commentCount: Int = 0,
    val status: Int = 0,
    val kind: String = "video",
    val watermarkUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val quality: String = "",
    val qualityLabel: String = "",
    val qualityTier: Int = 0,
    val ultraHd: Boolean = false,
    val highFrameRate: Boolean = false,
    val qualityOptions: List<VideoQuality> = emptyList(),
    val qualityBusy: Boolean = false,
    val freeMaxHeight: Int = 1080,
    val previewPercent: Int = 25,
    val previewGated: Boolean = false,
    val contentLabels: List<String> = emptyList(),
    val visibility: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val publishedAt: Long = 0,
    val up: UserInfo? = null,
    val viewer: ViewerInfo? = null
) {
    val isArticle: Boolean get() = kind == "article"
    val isAudio: Boolean get() = kind == "audio"
    val isVideo: Boolean get() = kind == "video"

    fun getFullCoverUrl(baseUrl: String): String = joinUrl(baseUrl, cover)
    fun getFullSrcUrl(baseUrl: String): String = joinUrl(baseUrl, src)
}

data class VideoQuality(
    val key: String = "",
    val label: String = "",
    val height: Int = 0,
    val fps: Int = 0,
    val url: String = ""
) {
    fun getFullUrl(baseUrl: String): String = joinUrl(baseUrl, url)
}

data class UserInfo(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val level: Int = 0,
    val followerCount: Int = 0,
    val badgeType: String = "",
    val badgeLabel: String = "",
    val vip: Boolean = false,
    val vipTier: String = "",
    val vipTierLabel: String = ""
) {
    fun getFullAvatarUrl(baseUrl: String): String = joinUrl(baseUrl, avatar)
}

fun UserInfo?.displayName(fallback: String = "匿名"): String =
    this?.nickname?.takeIf { it.isNotBlank() }
        ?: this?.username?.takeIf { it.isNotBlank() }
        ?: fallback

data class ViewerInfo(
    val liked: Boolean = false,
    val coined: Int = 0,
    val faved: Boolean = false,
    val folderId: Long? = null
)

/** 视频详情响应；progress 登录时是对象而非数字，必须 Any? 兼容 */
data class VideoDetail(
    val video: Video? = null,
    val following: Boolean = false,
    val progress: Any? = null
)

data class LikeResult(val liked: Boolean = false, val likeCount: Int = 0)
data class CoinRequest(val amount: Int)
data class CoinResult(val coined: Int = 0, val coinCount: Int = 0, val coins: Long = 0)
data class FollowResult(val following: Boolean = false)
data class FavoriteRequest(val folderId: Long)
data class FavoriteResult(val faved: Boolean = false, val folderId: Long = 0, val favCount: Int = 0)

data class ViewersResult(val videoId: Long = 0, val count: Int = 0)
data class ViewReportRequest(val progressMs: Long, val durationMs: Long)
data class ViewReportResult(val viewCount: Long = 0)
data class HistoryReportRequest(val videoId: Long, val progressMs: Long, val durationMs: Long)

data class HistoryItem(
    val video: Video? = null,
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val percent: Int = 0,
    val updatedAt: Long = 0
)

// ==================== 分区/弹幕/搜索 ====================

data class Partition(
    val slug: String = "",
    val name: String = "",
    val color: String = "",
    val children: List<String> = emptyList(),
    val videoCount: Int = 0
)

data class Danmaku(
    val id: Long = 0,
    val content: String = "",
    val timeMs: Long = 0,
    val mode: Int = 1,
    val color: String = "#FFFFFF",
    val fontSize: Int = 25,
    val uid: Long = 0,
    val createdAt: Long = 0,
    val avatar: String = "",
    val vip: Boolean = false
) {
    companion object {
        const val MODE_SCROLL = 1
        const val MODE_TOP = 5
        const val MODE_BOTTOM = 4
    }
}

data class DanmakuList(
    val items: List<Danmaku> = emptyList(),
    val segmentMs: Long = 60000,
    val total: Int = 0
)

data class DanmakuSendRequest(
    val videoId: Long,
    val content: String,
    val timeMs: Long,
    val mode: Int = 1,
    val color: String = "#FFFFFF",
    val fontSize: Int = 25
)

data class SearchResult(
    val type: String = "video",
    val items: List<Video> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false
)

// ==================== 评论 ====================

data class Comment(
    val id: Long = 0,
    val content: String = "",
    val likeCount: Int = 0,
    val liked: Boolean = false,
    val upLiked: Boolean = false,
    val pinned: Boolean = false,
    val replyCount: Int = 0,
    val replies: List<Comment> = emptyList(),
    val createdAt: Long = 0,
    @SerializedName("author") val user: UserInfo? = null
)

data class CommentListResult(
    val items: List<Comment> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false
)

data class CommentSendRequest(val content: String, val parentId: Long = 0)
data class CommentLikeResult(val liked: Boolean = false, val likeCount: Int = 0, val upLiked: Boolean = false)
data class PinRequest(val pinned: Boolean)

// ==================== 动态 ====================

data class Dynamic(
    val id: Long = 0,
    val kind: String = "text",
    val content: String = "",
    val createdAt: Long = 0,
    @SerializedName("author") val user: UserInfo? = null,
    val video: Video? = null
)

/** 首页轮播：管理员广告与用户海报的归一化混合列表 */
data class AdItem(
    val id: Long = 0,
    val kind: String = "ad",
    val title: String = "",
    val imageUrl: String = "",
    val linkUrl: String = "",
    val label: String = "",
    val ownerName: String = "",
    val viewCount: Long = 0,
    val clickCount: Long = 0
)

// ==================== 站点状态 ====================

data class SiteStats(val online: Int = 0, val watching: Int = 0)
data class PresenceResult(val online: Int = 0, val watching: Int = 0)
