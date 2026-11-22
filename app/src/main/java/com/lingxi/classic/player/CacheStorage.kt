package com.lingxi.classic.player

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 缓存存储位置：内部存储 / SD 卡（可移除的外置存储）。
 *
 * 老机型（Android 5.1）与 Android 10+ 都不申请存储权限：
 * - 内部：应用私有目录 filesDir
 * - 外置：getExternalFilesDirs() 里第二个及以后的卷（应用专属目录，API 19+ 免权限），
 *   用 Environment.isExternalStorageRemovable 判断是否为可移除介质。
 */
object CacheStorage {

    const val TARGET_INTERNAL = "internal"
    const val TARGET_EXTERNAL = "external"

    private const val PREFS_NAME = "lingxi_cache"
    private const val KEY_TARGET = "storage_target"
    private const val DIR_STREAM = "video_cache"
    private const val DIR_OFFLINE = "offline_media"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTarget(context: Context): String =
        prefs(context).getString(KEY_TARGET, TARGET_INTERNAL) ?: TARGET_INTERNAL

    fun isExternalTarget(context: Context): Boolean = getTarget(context) == TARGET_EXTERNAL

    fun setTarget(context: Context, target: String) {
        prefs(context).edit().putString(KEY_TARGET, target).apply()
        // 缓存目录变了，旧 SimpleCache 必须释放，否则新目录建不起来
        VideoCacheManager.resetInstance()
    }

    /** 可移除的外部存储根目录（应用专属），不可用时返回 null */
    fun removableRoot(context: Context): File? {
        val dirs = context.getExternalFilesDirs(null) ?: return null
        for (dir in dirs) {
            if (dir == null) continue
            try {
                if (Environment.isExternalStorageRemovable(dir)) return dir
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun isExternalAvailable(context: Context): Boolean {
        val root = removableRoot(context) ?: return false
        return root.exists() || root.mkdirs() || root.canWrite()
    }

    /** 选中的缓存根目录：SD 卡不可用时回退内部存储，避免功能直接不可用 */
    private fun baseDir(context: Context): File {
        if (isExternalTarget(context)) {
            val root = removableRoot(context)
            if (root != null && (root.exists() || root.mkdirs()) && root.canWrite()) return root
        }
        return context.applicationContext.filesDir
    }

    /** ExoPlayer 流式缓存目录 */
    fun streamCacheDir(context: Context): File = File(baseDir(context), DIR_STREAM)

    /** 离线缓存（手动缓存到本地）目录 */
    fun offlineDir(context: Context): File = File(baseDir(context), DIR_OFFLINE)

    fun describeTarget(context: Context): String {
        if (!isExternalTarget(context)) return "内部存储"
        return if (isExternalAvailable(context)) "SD 卡" else "SD 卡不可用，已回退内部存储"
    }

    fun availableBytes(context: Context): Long = try {
        val dir = baseDir(context)
        if (!dir.exists()) dir.mkdirs()
        dir.usableSpace
    } catch (_: Exception) {
        0L
    }
}
