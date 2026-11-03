package com.lingxi.classic.data.model

/** 图形验证码（SVG 字符串，用 SvgCaptchaView 自绘渲染，不依赖 WebView） */
data class Captcha(
    val id: String = "",
    val svg: String = ""
)

data class LoginRequest(val username: String, val password: String, val deviceToken: String = "")

/**
 * 登录响应。站点强制两步验证：
 * 成功 → accessToken + refreshToken + user
 * 需 2FA → mfaRequired=true + mfaTicket + emailMasked
 */
data class LoginResult(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val deviceToken: String? = null,
    val user: User? = null,
    val mfaRequired: Boolean = false,
    val mfaTicket: String? = null,
    val emailMasked: String? = null,
    val canUseRecovery: Boolean = false
)

/** 发送邮箱验证码：purpose=login 时 ticket 为 mfaTicket；register/bind 时 email 必填 */
data class SendCodeRequest(
    val purpose: String,
    val email: String = "",
    val ticket: String = "",
    val captchaId: String,
    val captchaCode: String
)

/** 二次验证：字段名是 ticket 不是 mfaTicket */
data class Verify2faRequest(
    val ticket: String,
    val code: String,
    val useRecovery: Boolean = false,
    val rememberDevice: Boolean
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    val emailCode: String,
    val captchaId: String,
    val captchaCode: String
)

data class SendCodeResult(
    val delivered: Boolean = false,
    val expiresIn: Int = 0,
    val resendAfter: Int = 60
)

data class User(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val email: String = "",
    val level: Int = 0,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val vip: Boolean = false
) {
    fun getFullAvatarUrl(baseUrl: String): String = joinUrl(baseUrl, avatar)
}

// ==================== 我的资料 ====================

data class MeResponse(
    val user: MeUser? = null,
    val level: MeLevel? = null,
    val checkedInToday: Boolean = false
)

data class MeUser(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val banner: String = "",
    val bio: String = "",
    val gender: String = "",
    val level: Int = 0,
    val exp: Long = 0,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val likeReceived: Long = 0,
    val viewReceived: Long = 0,
    val isAdmin: Boolean = false,
    val badgeType: String = "",
    val badgeLabel: String = "",
    val banned: Boolean = false,
    val emailBound: Boolean = false,
    val vip: Boolean = false,
    val vipTierLabel: String = "",
    val checkedInToday: Boolean = false,
    val createdAt: Long = 0
) {
    fun getFullAvatarUrl(baseUrl: String): String = joinUrl(baseUrl, avatar)
}

data class MeLevel(
    val level: Int = 0,
    val exp: Long = 0,
    val current: Long = 0,
    val next: Long = 0,
    val maxed: Boolean = false,
    val percent: Int = 0
)

/** GET /users/{id} 空间信息 */
data class SpaceInfo(
    val user: MeUser? = null,
    val level: MeLevel? = null,
    val videoCount: Int = 0,
    val totalViews: Long = 0,
    val joinedAt: String = "",
    val isFollowing: Boolean = false,
    val isSelf: Boolean = false
)

data class CheckinResult(
    val checkedIn: Boolean = false,
    val reward: Long = 0,
    val streak: Int = 0,
    val coins: Long = 0
)

/** 小鱼干：GET /users/me/coins → { coins, level, recent } */
data class CoinsOverview(
    val coins: Long = 0,
    val recent: List<CoinLog> = emptyList()
)

data class CoinLog(
    val reason: String = "",
    val delta: Long = 0,
    val amount: Long = 0,
    val balanceAfter: Long = 0,
    val createdAt: Long = 0
) {
    val change: Long get() = if (delta != 0L) delta else amount
}

/** 未读通知概览：{ unread, messages, lobby, byType } */
data class NotificationSummary(
    val unread: Int = 0,
    val messages: Int = 0,
    val lobby: Int = 0
)

/** 大会员状态：GET /vip/me → { history, uploadMaxBytes } */
data class VipMe(
    val uploadMaxBytes: Long = 0,
    val history: List<VipRedeemRecord> = emptyList()
)

data class VipRedeemRecord(
    val code: String = "",
    val plan: String = "",
    val days: Int = 0,
    val createdAt: Long = 0
)

data class VipRedeemRequest(val code: String)

/** 播放票据：?lx_vip=<ticket> 拼在播放地址后（App 无法用 Cookie 场景） */
data class VipMediaTicket(
    val ticket: String = "",
    val param: String = ""
)

// ==================== 收藏夹 ====================

data class FavoriteFolder(
    val id: Long = 0,
    val name: String = "",
    val isDefault: Boolean = false,
    val isPublic: Boolean = false,
    val count: Int = 0
)

// ==================== 消息 ====================

data class Conversation(
    val user: UserInfo? = null,
    val updatedAt: Long = 0,
    val last: ConversationLast? = null,
    val unread: Int = 0
)

data class ConversationLast(
    val id: Long = 0,
    val mine: Boolean = false,
    val content: String = "",
    val createdAt: Long = 0
)

data class ChatMessage(
    val id: Long = 0,
    val fromUid: Long = 0,
    val toUid: Long = 0,
    val content: String = "",
    val mine: Boolean = false,
    val createdAt: Long = 0
)

data class ChatHistory(
    val peer: UserInfo? = null,
    val items: List<ChatMessage> = emptyList(),
    val hasMore: Boolean = false
)

data class ConversationList(
    val items: List<Conversation> = emptyList(),
    val totalUnread: Int = 0
)

data class Notification(
    val id: Long = 0,
    val type: String = "",
    val title: String = "",
    val content: String = "",
    val link: String = "",
    val read: Boolean = false,
    val createdAt: Long = 0,
    val from: UserInfo? = null
)

// ==================== 网关节点 ====================

data class GatewayInfo(
    val enabled: Boolean = false,
    val origin: NodeOrigin? = null,
    val current: NodeCurrent? = null,
    val granted: Boolean = false,
    val defaultQuota: Long = 0,
    val nodes: List<GatewayNode> = emptyList(),
    val quota: Quota? = null
)

data class GatewayNode(
    val id: Long = 0,
    val name: String = "",
    val kind: String = "",
    val note: String = "",
    val url: String = "",
    val host: String = "",
    val port: Int = 0,
    val multiplier: Double = 1.0,
    val enabled: Boolean = true
) {
    val isDirect: Boolean get() = kind == "direct"
}

data class NodeOrigin(val name: String = "", val url: String = "")
data class NodeCurrent(val direct: Boolean = false, val ip: String = "", val nodeId: Long = 0, val via: String = "")
data class HandoffRequest(val nodeId: Long)

/** handoff 响应：url 带 lx_ticket；ip 为服务端记录的本机公网 IP（节点校验 X-Forwarded-For 用） */
data class HandoffResult(
    val nodeId: Long = 0,
    val url: String = "",
    val ip: String = ""
)

data class Quota(val used: Long = 0, val limit: Long = 0, val resetAt: Long = 0) {
    val remain: Long get() = (limit - used).coerceAtLeast(0)
}
