package com.lingxi.classic.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.reflect.TypeToken
import com.lingxi.classic.data.api.ApiClient
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 离线缓存：把视频/音频整文件下载到本地，缓存位置跟随「缓存存储位置」设置
 * （内部存储或 SD 卡），播放时优先用本地文件。
 */
object OfflineCacheManager {

    const val KIND_VIDEO = "video"
    const val KIND_AUDIO = "audio"

    private const val PREFS = "lingxi_offline_cache"
    private const val KEY_INDEX = "index"

    data class CachedMedia(
        val key: String,
        val videoId: Long,
        val title: String,
        val kind: String,
        val cover: String,
        val fileName: String,
        val size: Long,
        val createdAt: Long,
        /** UP 主昵称（旧索引没有该字段，空串表示未知） */
        val author: String = "",
        val authorAvatar: String = "",
        /** 时长（秒）；0 表示旧索引未记录，列表页会回读本地文件元数据补齐 */
        val durationSec: Long = 0
    )

    private val main = Handler(Looper.getMainLooper())
    private val running = ConcurrentHashMap<String, Call>()
    private val progressMap = ConcurrentHashMap<String, Int>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 缓存状态观察者：不绑定页面实例，页面可见时注册、不可见时注销 */
    interface Listener {
        fun onProgress(key: String, pct: Int)
        fun onFinished(key: String, ok: Boolean, message: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 注册时先回放当前快照，重进页面立即拿到最新百分比，不必等下一次回调 */
    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        progressMap.forEach { (key, pct) -> listener.onProgress(key, pct) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyProgress(key: String, pct: Int) {
        listeners.forEach { it.onProgress(key, pct) }
    }

    private fun notifyFinished(key: String, ok: Boolean, message: String) {
        listeners.forEach { it.onFinished(key, ok, message) }
    }

    fun keyOf(videoId: Long, kind: String): String = "$videoId-$kind"

    fun isRunning(key: String): Boolean = running.containsKey(key)

    fun progressOf(key: String): Int = progressMap[key] ?: 0

    fun localFile(context: Context, videoId: Long, kind: String): File? {
        val e = find(context, keyOf(videoId, kind)) ?: return null
        val f = resolve(context, e.fileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 条目对应的本地文件（不判断是否存在），供缓存列表页判断完整性 */
    fun fileFor(context: Context, entry: CachedMedia): File = resolve(context, entry.fileName)

    fun find(context: Context, key: String): CachedMedia? =
        readIndex(context).firstOrNull { it.key == key && resolve(context, it.fileName).exists() }

    fun list(context: Context): List<CachedMedia> =
        readIndex(context).filter { resolve(context, it.fileName).exists() }
            .sortedByDescending { it.createdAt }

    fun totalBytes(context: Context): Long = list(context).sumOf { it.size }

    /** 索引里存相对文件名：SD 卡重新挂载后挂载点可能变，按当前缓存目录重新解析 */
    private fun resolve(context: Context, name: String): File {
        val f = File(name)
        return if (f.isAbsolute) f else File(CacheStorage.offlineDir(context), name)
    }

    /**
     * 开始缓存。headers 与原播放请求一致（节点鉴权等）。
     * 进度与完成通过 addListener 注册的观察者在主线程回调。
     * @return false 表示同一内容已在缓存或正在下载
     */
    fun start(
        context: Context,
        videoId: Long,
        title: String,
        kind: String,
        url: String,
        cover: String,
        headers: Map<String, String>,
        author: String = "",
        authorAvatar: String = "",
        durationSec: Long = 0
    ): Boolean {
        val app = context.applicationContext
        val key = keyOf(videoId, kind)
        if (isRunning(key) || localFile(app, videoId, kind) != null) return false

        val dir = CacheStorage.offlineDir(app)
        if (!dir.exists() && !dir.mkdirs()) {
            main.post { notifyFinished(key, false, "缓存目录不可用") }
            return false
        }
        val target = File(dir, fileNameOf(videoId, kind, url))
        val part = File(dir, target.name + ".part")

        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> if (k.isNotEmpty()) builder.header(k, v) }
        val req = client.newCall(builder.build())
        running[key] = req
        progressMap[key] = 0

        req.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                running.remove(key)
                progressMap.remove(key)
                part.delete()
                main.post { notifyFinished(key, false, e.message ?: "网络错误") }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        running.remove(key)
                        progressMap.remove(key)
                        part.delete()
                        main.post { notifyFinished(key, false, "HTTP ${resp.code}") }
                        return
                    }
                    val body = resp.body
                    if (body == null) {
                        running.remove(key)
                        progressMap.remove(key)
                        main.post { notifyFinished(key, false, "响应为空") }
                        return
                    }
                    val total = body.contentLength()
                    try {
                        part.parentFile?.mkdirs()
                        java.io.FileOutputStream(part).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var done = 0L
                                while (input.read(buf).also { read = it } >= 0) {
                                    out.write(buf, 0, read)
                                    done += read
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != progressMap[key]) {
                                            progressMap[key] = pct
                                            main.post { notifyProgress(key, pct) }
                                        }
                                    }
                                }
                                out.flush()
                            }
                        }
                        if (target.exists()) target.delete()
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                        val entry = CachedMedia(
                            key = key,
                            videoId = videoId,
                            title = title,
                            kind = kind,
                            cover = cover,
                            fileName = target.name,
                            size = target.length(),
                            createdAt = System.currentTimeMillis(),
                            author = author,
                            authorAvatar = authorAvatar,
                            durationSec = durationSec
                        )
                        writeIndex(app, readIndex(app).filter { it.key != key } + entry)
                        running.remove(key)
                        progressMap.remove(key)
                        main.post { notifyFinished(key, true, target.absolutePath) }
                    } catch (e: Exception) {
                        running.remove(key)
                        progressMap.remove(key)
                        part.delete()
                        main.post { notifyFinished(key, false, e.message ?: "写入失败") }
                    }
                }
            }
        })
        return true
    }

    fun cancel(key: String) {
        running.remove(key)?.cancel()
        progressMap.remove(key)
    }

    fun delete(context: Context, entry: CachedMedia) {
        try { resolve(context, entry.fileName).delete() } catch (_: Exception) {}
        writeIndex(context, readIndex(context).filter { it.key != entry.key })
    }

    fun clearAll(context: Context) {
        readIndex(context).forEach { try { resolve(context, it.fileName).delete() } catch (_: Exception) {} }
        writeIndex(context, emptyList())
        try { CacheStorage.offlineDir(context).deleteRecursively() } catch (_: Exception) {}
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun fileNameOf(videoId: Long, kind: String, url: String): String {
        val ext = try {
            val path = url.substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/')
            val e = name.substringAfterLast('.', "")
            if (e.isNotEmpty() && e.length <= 5) e.lowercase() else if (kind == KIND_AUDIO) "m4a" else "mp4"
        } catch (_: Exception) {
            if (kind == KIND_AUDIO) "m4a" else "mp4"
        }
        return "$videoId-$kind.$ext"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readIndex(context: Context): List<CachedMedia> {
        val raw = prefs(context).getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CachedMedia>>() {}.type
            // 老索引没有 author/authorAvatar 字段：Gson 走 Unsafe 分配、不执行 Kotlin 默认值，
            // 缺字段会读成 null。字段声明是非空 String，直接写 e.author ?: "" 会被编译器当成
            // 恒真折叠掉；用可空局部变量承接，判空才会真正落到字节码里。
            ApiClient.gson.fromJson<List<CachedMedia>>(raw, type)?.map { e ->
                val author: String? = e.author
                val avatar: String? = e.authorAvatar
                e.copy(author = author ?: "", authorAvatar = avatar ?: "")
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(context: Context, list: List<CachedMedia>) {
        try {
            prefs(context).edit().putString(KEY_INDEX, ApiClient.gson.toJson(list)).apply()
        } catch (_: Exception) {}
    }
}
