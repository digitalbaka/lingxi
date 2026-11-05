package com.lingxi.classic.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.lingxi.classic.LingXiApp
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.User

/**
 * 认证管理器 - 持久化 Token、用户信息、当前选中节点
 */
object AuthManager {

    private const val PREFS_NAME = "lingxi_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER = "user_json"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_NODE_URL = "node_url"
    private const val KEY_NODE_NAME = "node_name"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_NODE_GRANTED_IP = "node_granted_ip"
    private const val KEY_EXPIRES_AT = "expires_at"

    /** 提前续期窗口：accessToken 到期前 60 秒开始续期 */
    private const val ACCESS_TOKEN_SKEW_MS = 60_000L

    private val prefs: SharedPreferences by lazy {
        LingXiApp.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** accessToken 有效期仅 2 小时，过期后由 ApiClient 用 refreshToken 自动续期 */
    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var user: User?
        get() {
            val json = prefs.getString(KEY_USER, null) ?: return null
            return try { ApiClient.gson.fromJson(json, User::class.java) } catch (e: Exception) { null }
        }
        set(value) {
            val json = value?.let { ApiClient.gson.toJson(it) }
            prefs.edit().putString(KEY_USER, json).apply()
        }

    /** 当前选中的节点 URL（播放地址拼接用），null 表示主站 */
    var nodeUrl: String?
        get() = prefs.getString(KEY_NODE_URL, null)
        set(value) = prefs.edit().putString(KEY_NODE_URL, value).apply()

    var nodeName: String?
        get() = prefs.getString(KEY_NODE_NAME, null)
        set(value) = prefs.edit().putString(KEY_NODE_NAME, value).apply()

    var nodeId: Long
        get() = prefs.getLong(KEY_NODE_ID, 0)
        set(value) = prefs.edit().putLong(KEY_NODE_ID, value).apply()

    /**
     * handoff 授权时服务端记录的本机公网 IP。
     * 直连节点靠 X-Forwarded-For 识别已授权设备，播放时必须带上该头，
     * 否则节点返回 HTTP 200 + {code:40301} 的 JSON（播放器表现为无限加载）
     */
    var nodeGrantedIp: String?
        get() = prefs.getString(KEY_NODE_GRANTED_IP, null)
        set(value) = prefs.edit().putString(KEY_NODE_GRANTED_IP, value).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty()

    /** 稳定设备 ID（首次生成后持久化） */
    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    /** 设备标签（HTTP header 不允许非 ASCII） */
    const val DEVICE_LABEL: String = "LingXiClassic"

    /** 管理员 UID 名单（本地鉴权，与服务端一致） */
    private val ADMIN_UIDS = setOf(1L, 3L, 12L, 62L, 106L)

    val isAdmin: Boolean get() = isLoggedIn && (user?.id ?: 0L) in ADMIN_UIDS

    /**
     * accessToken 过期时间（毫秒 epoch）。null 表示未知 —— 此时退化为「被动 401 续期」，
     * 不做过期推断，也绝不清会话。
     */
    var expiresAt: Long?
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L }
        set(value) {
            if (value == null || value <= 0L) prefs.edit().remove(KEY_EXPIRES_AT).apply()
            else prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()
        }

    /** 是否已进入提前续期窗口（默认提前 60 秒）；过期时间未知时返回 false，走 401 兜底 */
    fun isExpiringSoon(skewMs: Long = ACCESS_TOKEN_SKEW_MS, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = expiresAt ?: return false
        return nowMs >= at - skewMs
    }

    fun saveLogin(
        newToken: String,
        newUser: User,
        newRefreshToken: String? = null,
        expiresAtMs: Long? = null
    ) {
        token = newToken
        // refresh 是轮换令牌，且并非每个登录/改密响应都会返回它；
        // 传 null 时保留已有值，否则会把可用的续期凭证抹掉，下一次 401 就只能重登
        if (!newRefreshToken.isNullOrBlank()) refreshToken = newRefreshToken
        user = newUser
        // 过期时间随 accessToken 一起更新；响应没给就置空，避免拿着旧时间戳误触发续期
        expiresAt = expiresAtMs
    }

    fun logout() = prefs.edit().clear().apply()
}
