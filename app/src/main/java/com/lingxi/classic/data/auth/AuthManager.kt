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

    fun getPlaybackBaseUrl(): String {
        val url = nodeUrl
        return if (url.isNullOrEmpty()) ApiClient.BASE_URL else url.trimEnd('/')
    }

    fun saveLogin(newToken: String, newUser: User, newRefreshToken: String? = null) {
        token = newToken
        refreshToken = newRefreshToken
        user = newUser
    }

    fun logout() = prefs.edit().clear().apply()

    /** 仅清空登录态（保留节点选择），用于令牌过期无法续期时 */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER)
            .apply()
    }
}
