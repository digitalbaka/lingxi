package com.lingxi.classic.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频磁盘缓存（全局单例，SimpleCache 有文件锁）。
 * LRU 淘汰 + 可选上限；清除「毒缓存」（未授权节点返回的 40301 JSON 被当视频缓存）
 * 用 evict(url)。
 */
object VideoCacheManager {

    val CACHE_LIMIT_OPTIONS_MB = intArrayOf(50, 100, 150, 200)
    private const val DEFAULT_CACHE_LIMIT_MB = 100
    private const val PREFS_NAME = "lingxi_cache"
    private const val KEY_LIMIT_MB = "cache_limit_mb"

    @Volatile
    private var simpleCache: SimpleCache? = null

    fun getCacheLimitBytes(context: Context): Long {
        val mb = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_MB, DEFAULT_CACHE_LIMIT_MB)
        return mb * 1024L * 1024L
    }

    fun getCacheLimitMb(context: Context): Int =
        (getCacheLimitBytes(context) / 1024 / 1024).toInt()

    fun setCacheLimitMb(context: Context, mb: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT_MB, mb).apply()
        synchronized(this) {
            try { simpleCache?.release() } catch (_: Exception) {}
            simpleCache = null
        }
    }

    /** 启动时修剪：必须在任何 SimpleCache 实例创建之前调用 */
    fun trimIfOverLimit(context: Context) {
        try {
            val dir = File(context.cacheDir, "video_cache")
            if (!dir.exists()) return
            val limit = getCacheLimitBytes(context)
            val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (size > limit) dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: SimpleCache(
                File(context.cacheDir, "video_cache"),
                LeastRecentlyUsedCacheEvictor(getCacheLimitBytes(context)),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { simpleCache = it }
        }
    }

    /**
     * 包装磁盘缓存的数据源工厂。
     * defaultHeaders：直连节点场景需 X-Forwarded-For（节点靠它识别 handoff 授权的公网 IP）
     */
    fun cacheDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): CacheDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("LingXiClassic/1.0")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        if (defaultHeaders.isNotEmpty()) {
            upstream.setDefaultRequestProperties(defaultHeaders)
        }
        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun getCacheSizeBytes(context: Context): Long {
        return try { getCache(context).cacheSpace } catch (e: Exception) { 0L }
    }

    /**
     * 删除指定 URL 的缓存。
     * 节点未授权时返回 HTTP 200 + {code:40301} JSON，CacheDataSource 会当视频流永久缓存，
     * 重新授权后仍命中毒缓存形成死循环，必须在重试授权前清掉。
     */
    fun evict(context: Context, url: String) {
        try { getCache(context).removeResource(url) } catch (_: Exception) {}
    }

    fun clearCache(context: Context) {
        synchronized(this) {
            try { simpleCache?.release() } catch (_: Exception) {}
            simpleCache = null
            File(context.cacheDir, "video_cache").deleteRecursively()
        }
    }
}
