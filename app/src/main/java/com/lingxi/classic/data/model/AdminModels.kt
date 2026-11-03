package com.lingxi.classic.data.model

// ==================== 管理后台模型 ====================

/** 站务总览：GET /admin/overview */
data class AdminOverview(
    val totals: AdminTotals? = null,
    val recentUsers: List<AdminUser> = emptyList(),
    val recentVideos: List<AdminVideo> = emptyList()
)

data class AdminTotals(
    val users: Int = 0,
    val videos: Int = 0,
    val pending: Int = 0,
    val danmaku: Int = 0,
    val comments: Int = 0,
    val follows: Int = 0,
    val views: Long = 0
)

/** 待办角标汇总：GET /admin/todos（字段动态，用 Map 承接） */
data class AdminTodos(
    val queue: Int = 0,
    val comments: Int = 0,
    val reports: Int = 0,
    val verifications: Int = 0,
    val posters: Int = 0,
    val creator: Int = 0,
    val withdrawals: Int = 0
)

data class AdminQueue(
    val items: List<AdminVideo> = emptyList(),
    val counts: AdminCounts? = null,
    val page: Int = 1,
    val total: Int = 0,
    val hasMore: Boolean = false
)

data class AdminCounts(
    val reviewing: Int = 0,
    val published: Int = 0,
    val rejected: Int = 0
)

data class AdminVideo(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Long = 0,
    val status: Int = 0,
    val visibility: Int = 0,
    val pinned: Boolean = false,
    val kind: String = "video",
    val description: String = "",
    val partition: String = "",
    val qualityLabel: String = "",
    val createdAt: Long = 0,
    val up: UserInfo? = null
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "待审核"
            1 -> "已发布"
            2 -> "未通过"
            else -> "状态 $status"
        }
}

data class AdminComment(
    val id: Long = 0,
    val content: String = "",
    val author: UserInfo? = null,
    val video: AdminVideoRef? = null,
    val isReply: Boolean = false,
    val likeCount: Int = 0,
    val createdAt: Long = 0
)

data class AdminDanmaku(
    val id: Long = 0,
    val content: String = "",
    val author: UserInfo? = null,
    val video: AdminVideoRef? = null,
    val timeMs: Long = 0,
    val createdAt: Long = 0
)

data class AdminVideoRef(val id: Long = 0, val title: String = "")

data class AdminReport(
    val id: Long = 0,
    val reporter: UserInfo? = null,
    val target: AdminReportTarget? = null,
    val targetType: String = "",
    val reason: String = "",
    val detail: String = "",
    val status: String = "",
    val createdAt: Long = 0
)

data class AdminReportTarget(
    val id: Long = 0,
    val videoId: Long = 0,
    val label: String = "",
    val title: String = "",
    val content: String = ""
)

data class AdminUser(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val level: Int = 0,
    val exp: Long = 0,
    val coins: Long = 0,
    val videoCount: Int = 0,
    val followerCount: Int = 0,
    val reportedCount: Int = 0,
    val banned: Boolean = false,
    val isAdmin: Boolean = false,
    val adminScopes: List<String> = emptyList(),
    val scopeAll: Boolean = false
) {
    fun getFullAvatarUrl(baseUrl: String): String = joinUrl(baseUrl, avatar)
}

// ---- 管理请求体 ----
data class ReviewRequest(val approve: Boolean, val reason: String? = null)
data class HandleReportRequest(val action: String)
data class BanRequest(val banned: Boolean)
data class RoleRequest(val isAdmin: Boolean, val scopeAll: Boolean = false, val scopes: List<String>? = null)
data class ExpRequest(val exp: Long? = null, val delta: Long? = null, val reason: String)
data class CoinsRequest(val delta: Long, val reason: String)
data class BadgeRequest(val type: String, val label: String)

// ==================== 认证审核 ====================

data class AdminVerification(
    val id: Long = 0,
    val type: String = "",
    val category: String = "",
    val label: String = "",
    val realName: String = "",
    val contact: String = "",
    val evidence: String = "",
    val status: Int = 0,
    val reason: String = "",
    val createdAt: Long = 0,
    val user: UserInfo? = null
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "待审核"
            1 -> "已通过"
            2 -> "已驳回"
            else -> "状态 $status"
        }
}

// ==================== 海报审核 ====================

data class AdminPoster(
    val id: Long = 0,
    val title: String = "",
    val imageUrl: String = "",
    val linkUrl: String = "",
    val planName: String = "",
    val price: Long = 0,
    val status: Int = 0,
    val reason: String = "",
    val impressions: Int = 0,
    val clicks: Int = 0,
    val createdAt: Long = 0,
    val user: UserInfo? = null
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "待审核"
            1 -> "投放中"
            2 -> "已驳回"
            else -> "状态 $status"
        }
}

data class PosterReviewRequest(val status: Int, val reason: String? = null)

// ==================== 创作者申请 ====================

data class CreatorApplication(
    val id: Long = 0,
    val intro: String = "",
    val status: Int = 0,
    val reason: String = "",
    val createdAt: Long = 0,
    val user: UserInfo? = null,
    val videoCount: Int = 0,
    val totalViews: Long = 0,
    val videos: List<Video> = emptyList()
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "待审核"
            1 -> "已通过"
            2 -> "已驳回"
            else -> "状态 $status"
        }
}

// ==================== 广告（运营管理） ====================

data class AdminAd(
    val id: Long = 0,
    val title: String = "",
    val imageUrl: String = "",
    val linkUrl: String = "",
    val position: String = "",
    val sortOrder: Int = 0,
    val enabled: Boolean = false,
    val impressions: Int = 0,
    val clicks: Int = 0,
    val startAt: Long = 0,
    val endAt: Long = 0
)

// ==================== 商城（运营管理） ====================

data class ShopProduct(
    val id: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val kind: String = "",
    val cover: String = "",
    val price: Long = 0,
    val stock: Int = 0,
    val perUserLimit: Int = 0,
    val sortOrder: Int = 0,
    val enabled: Boolean = true
)

data class ShopOrder(
    val id: Long = 0,
    val productTitle: String = "",
    val quantity: Int = 0,
    val totalPrice: Long = 0,
    val status: Int = 0,
    val receiverName: String = "",
    val receiverPhone: String = "",
    val receiverAddr: String = "",
    val createdAt: Long = 0,
    val user: UserInfo? = null
)

// ==================== 提现处理 ====================

data class AdminWithdrawal(
    val id: Long = 0,
    val mills: Long = 0,
    val channel: String = "",
    val payeeName: String = "",
    val contact: String = "",
    val status: Int = 0,
    val reason: String = "",
    val createdAt: Long = 0,
    val user: UserInfo? = null
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "待审核"
            1 -> "待打款"
            2 -> "已打款"
            3 -> "已驳回"
            else -> "状态 $status"
        }
}

// ==================== 存储 ====================

data class AdminStorage(
    val disks: List<StorageDisk> = emptyList(),
    val writable: Int = 0,
    val total: Int = 0,
    val minFreeBytes: Long = 0,
    val writeRoot: String = "",
    val extraDirs: List<String> = emptyList()
)

data class StorageDisk(
    val root: String = "",
    val label: String = "",
    val kind: String = "",
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val usedBytes: Long = 0,
    val fileCount: Int = 0,
    val writable: Boolean = true,
    val preferred: Boolean = false
)

// ==================== 接入节点（管理视角） ====================

data class AdminGateway(
    val enabled: Boolean = false,
    val defaultQuota: Long = 0,
    val origin: NodeOrigin? = null,
    val nodes: List<GatewayNode> = emptyList(),
    val grants: List<AdminGrant> = emptyList(),
    val totals: AdminGrantTotals? = null
)

data class AdminGrant(
    val ip: String = "",
    val uid: Long = 0,
    val used: Long = 0,
    val quota: Long = 0,
    val blocked: Boolean = false,
    val active: Boolean = false,
    val grantCount: Int = 0
)

data class AdminGrantTotals(
    val todayUsed: Long = 0,
    val whitelist: Int = 0,
    val active: Int = 0,
    val blocked: Int = 0
)

data class GatewaySettingsRequest(val enabled: Boolean, val dailyQuotaBytes: Long)
data class GrantActionRequest(val action: String, val quotaBytes: Long = 0)
data class GrantAddRequest(val ip: String, val nodeId: Long = 0)
data class NodeEditRequest(
    val name: String = "",
    val url: String = "",
    val kind: String = "direct",
    val note: String = "",
    val enabled: Boolean = true
)

// ==================== 上传 ====================

data class UploadResult(
    val url: String = "",
    val key: String = "",
    val size: Long = 0,
    val driver: String = "",
    val faststartQueued: Boolean = false
)

// ==================== 投稿 ====================

data class PublishRequest(
    val kind: String,
    val title: String,
    val src: String = "",
    val partition: String = "",
    val tags: List<String> = emptyList(),
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val contentLabels: List<String> = emptyList(),
    val visibility: Int = 0,
    val description: String = "",
    val cover: String = "",
    val content: String = ""
)

data class PublishResult(val video: Video? = null)
