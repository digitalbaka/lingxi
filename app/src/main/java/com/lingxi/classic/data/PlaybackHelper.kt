package com.lingxi.classic.data

import android.content.Context
import android.util.Log
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.GatewayNode
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.joinUrl
import com.lingxi.classic.player.VideoCacheManager

/**
 * 播放地址解析：节点 + handoff 授权 + 毒缓存清理。
 *
 * 站点媒体走「白嫖节点」：App 播放前必须先 POST /gateway/handoff 授权，
 * 否则节点返回 HTTP 200 + {code:40301} JSON（播放器表现为无限加载/解析失败）。
 * 该 JSON 若被磁盘缓存命中形成死循环，重新授权前必须 evict。
 */
object PlaybackHelper {

    private const val TAG = "Playback"

    data class PlaySource(
        val url: String,
        val headers: Map<String, String>,
        val viaNode: Boolean,
        val nodeName: String
    )

    /** 当前是否使用直连节点 */
    fun usingDirectNode(): Boolean = AuthManager.nodeId != 0L && !AuthManager.nodeUrl.isNullOrEmpty()

    /**
     * 上传服务基址：用户切换了直连节点时，上传（视频/音频/封面）直连该节点，
     * 与播放链路一致（节点反向代理站点 API + handoff 授权 + X-Forwarded-For）。
     */
    fun uploadBaseUrl(): String =
        if (usingDirectNode()) AuthManager.nodeUrl!!.trimEnd('/') else ApiClient.BASE_URL

    /** 确保直连节点已授权（handoff）。返回 true 表示可用 */
    suspend fun ensureNodeGranted(): Boolean {
        if (!usingDirectNode()) return true
        if (!AuthManager.isLoggedIn) return true // 未登录走主站即可
        return try {
            val resp = ApiClient.apiService.handoff(com.lingxi.classic.data.model.HandoffRequest(AuthManager.nodeId))
            if (resp.isSuccess) {
                resp.data?.ip?.let { AuthManager.nodeGrantedIp = it }
                true
            } else {
                Log.w(TAG, "handoff failed: ${resp.message}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "handoff error", e)
            false
        }
    }

    /** 生成最终播放源（含节点拼接 + X-Forwarded-For 头） */
    suspend fun buildPlaySource(context: Context, video: Video, qualityUrl: String? = null): PlaySource {
        val base = AuthManager.getPlaybackBaseUrl()
        var url = joinUrl(base, qualityUrl ?: video.src)

        // 大会员媒体票据（App 无法用 Cookie 时拼参数）
        if (video.previewGated && AuthManager.isLoggedIn) {
            try {
                val t = ApiClient.apiService.getVipMediaTicket().data
                if (t != null && t.ticket.isNotEmpty() && t.param.isNotEmpty()) {
                    url += (if (url.contains("?")) "&" else "?") + t.param + "=" + t.ticket
                }
            } catch (_: Exception) {}
        }

        val headers = mutableMapOf<String, String>()
        if (usingDirectNode()) {
            ensureNodeGranted()
            AuthManager.nodeGrantedIp?.let { headers["X-Forwarded-For"] = it }
        }
        // 切地址后先清掉可能存在的毒缓存
        VideoCacheManager.evict(context, url)
        return PlaySource(url, headers, usingDirectNode(), AuthManager.nodeName ?: "主站")
    }

    /** 播放失败（可能 40301 毒缓存）后的恢复：清缓存 + 重新授权。返回是否应重试 */
    suspend fun recoverFromPlaybackError(context: Context, url: String): Boolean {
        if (!usingDirectNode()) return false
        VideoCacheManager.evict(context, url)
        AuthManager.nodeGrantedIp = null
        return ensureNodeGranted()
    }

    /** 切换节点：保存选择并重置授权状态 */
    fun selectNode(node: GatewayNode?) {
        if (node == null || node.id == 0L) {
            AuthManager.nodeId = 0
            AuthManager.nodeUrl = null
            AuthManager.nodeName = "主站"
            AuthManager.nodeGrantedIp = null
        } else {
            AuthManager.nodeId = node.id
            AuthManager.nodeUrl = node.url
            AuthManager.nodeName = node.name
            AuthManager.nodeGrantedIp = null
        }
    }
}
