package com.lingxi.classic.player

import android.content.Context

/**
 * 本地音频播放进度：按内容 id 各存一份「毫秒位置 + 时间戳」，最多保留最近 20 条。
 * 只服务本地音频（离线缓存/本地文件），在线播放不读写这里。
 */
object AudioPositionStore {

    private const val PREFS = "lingxi_audio_positions"
    private const val KEY_PREFIX = "pos_"
    private const val MAX_ENTRIES = 20
    private const val SEPARATOR = '|'

    /** 写入/覆盖一条进度，超出上限时按时间戳淘汰最旧的（不淘汰本次写入的 key） */
    fun save(context: Context, contentId: Long, positionMs: Long) {
        if (contentId <= 0L || positionMs <= 0L) return
        val prefs = prefs(context)
        val currentKey = keyOf(contentId)
        val now = System.currentTimeMillis()
        val entries = prefs.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val ts = (value as? String)?.substringAfter(SEPARATOR, "")?.toLongOrNull() ?: 0L
                key to ts
            }
            .sortedBy { it.second }
        val edit = prefs.edit().putString(currentKey, "$positionMs$SEPARATOR$now")
        var excess = if (currentKey in prefs.all) entries.size - MAX_ENTRIES else entries.size + 1 - MAX_ENTRIES
        for ((key, _) in entries) {
            if (excess <= 0) break
            if (key == currentKey) continue
            edit.remove(key)
            excess--
        }
        edit.apply()
    }

    /** 命中才返回；没有记录或位置为 0 返回 null，调用方从 0 起播 */
    fun load(context: Context, contentId: Long): Long? {
        if (contentId <= 0L) return null
        val raw = prefs(context).getString(keyOf(contentId), null) ?: return null
        val pos = raw.substringBefore(SEPARATOR).toLongOrNull() ?: return null
        return if (pos > 0L) pos else null
    }

    /** 播到结尾时清掉本条，避免下次进来直接接在结尾 */
    fun clear(context: Context, contentId: Long) {
        if (contentId <= 0L) return
        prefs(context).edit().remove(keyOf(contentId)).apply()
    }

    private fun keyOf(contentId: Long) = "$KEY_PREFIX$contentId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
