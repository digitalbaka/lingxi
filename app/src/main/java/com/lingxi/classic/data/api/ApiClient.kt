package com.lingxi.classic.data.api

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.lingxi.classic.BuildConfig
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.AdItem
import com.lingxi.classic.data.model.ApiResponse
import com.lingxi.classic.data.model.ItemList
import com.lingxi.classic.data.model.LoginResult
import com.lingxi.classic.data.model.PageData
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.expiresAtMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API 客户端 - 全局单例（连接池复用，1GB 内存设备友好）
 */
object ApiClient {

    const val BASE_URL = ApiService.BASE_URL

    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 30L
    private const val TAG = "LingXiApi"

    private val RETRYABLE_HTTP_CODES = setOf(502, 503, 504)
    private const val MAX_SERVER_RETRY = 3

    /**
     * 首屏推荐的单次调用上限：A1 取证显示服务端新 TLS 连接会挂起 19–30s
     * （ClientHello 发出后 30s 内收不到任何 ACK/首字节），而全局 readTimeout=30s
     * 恰好卡在它的恢复窗口边缘，才会出现「要么 31s 成功、要么 30s 失败」。
     * 取 10s：最坏空白从 30s 压到 10s，同时给第二次重试留出同样 10s 预算（合计 ≤20s）。
     */
    private const val HOME_CALL_TIMEOUT_MS = 10_000L

    /** 首屏静默失败是否换连接重试一次；改成 false 即回退成「单次 30s 超时」的旧行为 */
    private const val HOME_RETRY_ON_TIMEOUT = true

    /**
     * 首屏附属请求（banner 广告 / 置顶视频）的单次上限：这两条一直走全局 30s 读超时，
     * 一旦碰上同样的 TLS 挂起就会把首屏列表门住（实测：推荐日志 11.8s 已拿到，界面 32.2s 才出）。
     * 取 9s（比推荐单次 10s 更短）：列表落定后最迟 9s 内补齐；不做二次请求，
     * 总耗时由 callTimeout 硬截断，不会再出现 30s/60s 的等待。
     */
    private const val HOME_AUX_CALL_TIMEOUT_MS = 9_000L

    /** 401 兜底等待续期的上限：主站 refresh 正常耗时约 100–300ms，留 10 倍余量又不长时间卡住网络回调 */
    private const val AUTH_RETRY_WAIT_MS = 4_000L

    /** 续期失败后的预热退避窗口，避免失败风暴 */
    private const val WARMUP_BACKOFF_MS = 30_000L

    /**
     * 容错 String 适配器：个别接口把 `data.message` 返回成对象（如私信发送返回消息实体），
     * 而 DTO 中该字段声明为 String。严格反序列化会抛 JsonSyntaxException，使「已发送成功」
     * 被误判为失败。这里对对象/数组跳过并回填空串，业务结果仍以响应 code 为准。
     */
    private val lenientStringFactory = object : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != String::class.java) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : TypeAdapter<T>() {
                override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

                @Suppress("UNCHECKED_CAST")
                override fun read(reader: JsonReader): T {
                    val token = reader.peek()
                    if (token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY) {
                        reader.skipValue()
                        return "" as T
                    }
                    return delegate.read(reader)
                }
            }
        }
    }

    val gson: Gson by lazy {
        GsonBuilder()
            .serializeNulls()
            .registerTypeAdapterFactory(lenientStringFactory)
            .create()
    }

    /** 5xx 且响应体不是 JSON 时由旧重试链路抛出；保留类型供登录页等按类给出文案 */
    class HttpServerException(val code: Int) : IOException("HTTP $code")

    enum class ErrorKind { NO_NETWORK, TLS, SERVER, UNKNOWN }

    /** 登录/发码等一次性 POST 的失败分类：只用于给用户可读文案，不参与重试 */
    fun classifyError(e: Throwable?): ErrorKind {
        if (e == null) return ErrorKind.UNKNOWN
        if (e is HttpServerException) return ErrorKind.SERVER
        if (e is retrofit2.HttpException) {
            return if (e.code() in 500..599) ErrorKind.SERVER else ErrorKind.UNKNOWN
        }
        val msg = e.message.orEmpty().lowercase()
        if (msg.contains("ssl") || msg.contains("tls") || msg.contains("handshake") ||
            msg.contains("certificate")
        ) return ErrorKind.TLS
        if (msg.contains("network") || msg.contains("connect") || msg.contains("timeout") ||
            msg.contains("unreachable") || msg.contains("socket")
        ) return ErrorKind.NO_NETWORK
        return ErrorKind.UNKNOWN
    }

    /** 刷新令牌专用客户端：不带鉴权与 401 处理，避免刷新请求再触发刷新 */
    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    private val refreshLock = java.util.concurrent.locks.ReentrantLock()
    private val refreshDone = refreshLock.newCondition()

    /** 单飞守卫：同一时刻只允许一个续期任务 */
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 已为哪个 expiresAt 触发过后台预热（0 表示未触发）；保证同一过期窗口只触发一次 */
    private val warmupMark = java.util.concurrent.atomic.AtomicLong(0L)

    /** 上次续期失败时间，用于 30 秒退避，避免失败风暴 */
    private val lastRefreshFailAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** 后台预热作用域：绑定进程而非页面，不持 Activity/Context 引用，进程退出即回收 */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 401 兜底续期：**本类唯一允许等待续期结果的位置**，上限 [AUTH_RETRY_WAIT_MS]。
     * 已有续期在跑就等它（不重复发请求），没有就自己跑完。
     * 失败返回旧令牌（可能是 null），由调用方决定是否重试。
     */
    private fun refreshForRetry(): String? {
        refreshLock.lock()
        try {
            if (refreshInFlight.get()) {
                refreshDone.await(AUTH_RETRY_WAIT_MS, TimeUnit.MILLISECONDS)
                return AuthManager.token
            }
            refreshInFlight.set(true)
            try {
                return refreshAccessToken() ?: AuthManager.token
            } finally {
                refreshInFlight.set(false)
                refreshDone.signalAll()
            }
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * 后台预热续期（fire-and-forget）：请求路径只调用它、绝不等待。
     * 触发条件：过期时间已知 + 已进入临期窗口 + 同一过期窗口未触发过 + 不在失败退避期。
     */
    private fun warmUpIfNeeded() {
        val at = AuthManager.expiresAt ?: return
        if (!AuthManager.isExpiringSoon()) return
        if (System.currentTimeMillis() - lastRefreshFailAt.get() < WARMUP_BACKOFF_MS) return
        // CAS 保证同一 expiresAt 窗口内只有一个线程/请求能触发
        if (!warmupMark.compareAndSet(0L, at)) return
        if (!refreshInFlight.compareAndSet(false, true)) {
            warmupMark.compareAndSet(at, 0L)
            return
        }
        refreshScope.launch {
            try {
                if (refreshAccessToken() == null) lastRefreshFailAt.set(System.currentTimeMillis())
            } finally {
                refreshLock.lock()
                try {
                    refreshInFlight.set(false)
                    refreshDone.signalAll()
                } finally {
                    refreshLock.unlock()
                }
                warmupMark.compareAndSet(at, 0L)
            }
        }
    }

    /** App 启动/恢复前台时检查一次临期，必要时后台预热（不阻塞调用方） */
    fun warmUpSessionIfNeeded() {
        if (AuthManager.isLoggedIn) warmUpIfNeeded()
    }

    /**
     * 取本次请求使用的 accessToken：**只判断、不等待**，最坏额外耗时 0。
     * 临期时顶多把当前令牌发出去，同时甩一个后台续期；失败也不清会话。
     */
    private fun tokenForRequest(): String? {
        val token = AuthManager.token
        if (token.isNullOrEmpty()) return null
        if (!AuthManager.isExpiringSoon()) return token
        // 只触发后台预热，不等待：本次请求照旧用当前令牌
        warmUpIfNeeded()
        return token
    }

    /**
     * accessToken 有效期仅 2 小时，过期用 refreshToken 续期。
     * refresh 是轮换的：成功后必须保存响应里的新 refreshToken。
     *
     * 服务端契约（实测 POST https://lingxi.xiguastudio.top/api/v1/auth/refresh）：
     *   - 方法/路径：POST，JSON 体 {"refreshToken": "..."}
     *   - 成功：HTTP 2xx + ApiResponse<LoginResult>（字段沿用登录响应的 accessToken / refreshToken / user，
     *     无有效令牌无法实测，见遗留项）
     *   - 令牌无效：HTTP 401 + {code:401, message:"登录已过期，请重新登录"}
     *   - 缺字段或非法 JSON：HTTP 400 + {code:400, message:"缺少 refreshToken" / "请求体不是合法的 JSON"}
     *   - 用 GET 请求该路径：HTTP 404 + {code:404, message:"接口不存在: GET /api/v1/auth/refresh"}
     *
     * 注意：续期失败一律 **不清账号会话**。401 可能是网关/节点侧给出，400/404/5xx 更不代表凭证失效；
     * 而且节点授权（nodeGrantedIp）与账号凭证本来就是两层，节点侧过期不该让用户重新登录。
     * 最坏结果为该请求报 401，用户可在「我的」页手动重新登录。
     */
    private fun refreshAccessToken(): String? {
        val refresh = AuthManager.refreshToken
        if (refresh.isNullOrEmpty()) {
            Log.w(TAG, "no refreshToken, skip refresh")
            return null
        }
        return try {
            val payload = gson.toJson(mapOf("refreshToken" to refresh))
            val request = Request.Builder()
                .url("$BASE_URL/api/v1/auth/refresh")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("X-Device-Id", AuthManager.deviceId)
                .header("X-Device-Label", AuthManager.DEVICE_LABEL)
                .build()
            tokenClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "refresh failed: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val type = object : TypeToken<ApiResponse<LoginResult>>() {}.type
                val result = gson.fromJson<ApiResponse<LoginResult>>(body, type)
                val newToken = result?.data?.accessToken
                if (newToken.isNullOrEmpty()) return null
                AuthManager.token = newToken
                result.data.refreshToken?.let { AuthManager.refreshToken = it }
                result.data.user?.let { AuthManager.user = it }
                // 过期时间随令牌一起更新；解析不出就置空，退回被动 401 续期
                AuthManager.expiresAt = result.data.expiresAtMillis()
                newToken
            }
        } catch (e: Exception) {
            Log.w(TAG, "refresh error: ${e.message}")
            null
        }
    }

    private fun authRetryCount(response: okhttp3.Response): Int {
        var count = 0
        var current: okhttp3.Response? = response
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // release 关闭 BODY 日志：整段 JSON 读进内存拼字符串，小内存设备吃不消
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            // 上游抖动（502/503/504）自动重试，间隔递增
            .addInterceptor { chain ->
                val request = chain.request()
                var response = chain.proceed(request)
                // 只重试幂等请求：发评论/登录/投币等 POST 若被重放会产生重复副作用
                if (request.method != "GET" && request.method != "HEAD") return@addInterceptor response
                var attempt = 0
                while (response.code in RETRYABLE_HTTP_CODES && attempt < MAX_SERVER_RETRY) {
                    attempt++
                    response.close()
                    Thread.sleep(600L * attempt)
                    response = chain.proceed(request)
                }
                response
            }
            // 自动附加 Authorization（临期时先主动续期，续期失败仍用旧令牌交给 401 兜底）
            .addInterceptor { chain ->
                val original = chain.request()
                val token = tokenForRequest()
                val request = if (!token.isNullOrEmpty() && original.header("Authorization") == null) {
                    original.newBuilder().header("Authorization", "Bearer $token").build()
                } else original
                chain.proceed(request)
            }
            // 设备标识头：两步验证「记住这台设备」信任机制依赖（值必须纯 ASCII）
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-Device-Id", AuthManager.deviceId)
                    .header("X-Device-Label", AuthManager.DEVICE_LABEL)
                    .build()
                chain.proceed(request)
            }
            // 401 自动续期后重试原请求（与主动续期共用单飞，避免重复 refresh）
            .authenticator { _, response ->
                if (response.request.header("Authorization") == null) return@authenticator null
                if (authRetryCount(response) > 2) return@authenticator null
                val sent = response.request.header("Authorization")
                val newToken = refreshForRetry() ?: return@authenticator null
                // 续期没换到新令牌（网络/节点/网关问题、或凭证真失效）：放弃重试，但绝不清会话
                if ("Bearer $newToken" == sent) return@authenticator null
                response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("$BASE_URL/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val apiService: ApiService by lazy { retrofit.create(ApiService::class.java) }

    /**
     * 首屏推荐专用服务：只在这一个入口上叠加 callTimeout，并换成空连接池，
     * 保证重试时一定新建 TCP 连接，不会复用那条已挂起的连接。
     * 其它接口继续走 [apiService]（全局 30s 读超时不变）。
     */
    private val homeRecommendService: ApiService by lazy {
        retrofit.newBuilder()
            .client(
                okHttpClient.newBuilder()
                    .callTimeout(HOME_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                    .build()
            )
            .build()
            .create(ApiService::class.java)
    }

    /**
     * 首屏附属请求专用服务：只叠加 callTimeout（不换连接池、不做二次请求），
     * 鉴权/设备头/401 续期等拦截器全部沿用 [okHttpClient]，不是裸客户端。
     */
    private val homeAuxService: ApiService by lazy {
        retrofit.newBuilder()
            .client(
                okHttpClient.newBuilder()
                    .callTimeout(HOME_AUX_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
            )
            .build()
            .create(ApiService::class.java)
    }

    /** 首屏 banner 广告（短超时，失败静默；由 HomeFragment 在列表落定后补齐） */
    suspend fun adsFirstScreen(): ApiResponse<ItemList<AdItem>> =
        timedAuxRequest("ads") { homeAuxService.getAds() }

    /** 首屏置顶视频（短超时，失败静默；由 HomeFragment 在列表落定后补齐） */
    suspend fun pinnedFirstScreen(limit: Int = 6): ApiResponse<ItemList<Video>> =
        timedAuxRequest("pinned") { homeAuxService.getPinned(limit) }

    /** 记录附属请求的真实耗时，用于核对「列表落定 ↔ banner/置顶补齐」是否错位 */
    private suspend fun <T> timedAuxRequest(name: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            val result = block()
            Log.i(TAG, "home_first_screen aux=$name ok elapsed=${SystemClock.elapsedRealtime() - start}ms")
            return result
        } catch (e: Exception) {
            Log.w(
                TAG,
                "home_first_screen aux=$name fail elapsed=${SystemClock.elapsedRealtime() - start}ms " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }
    }

    /**
     * 首屏推荐（仅 page=1 首屏使用）：10s 超时 + 静默失败换连接重试一次，总时长上限约 20s。
     * 只重试 IOException（一个 HTTP 响应字节都没收到，含读超时/连接被断）；
     * 401/4xx/5xx 都是 HttpException（已收到响应），不在这里重试，
     * 502/503/504 仍由上面的 server retry interceptor 负责。
     * 日志保留：装机后用它量化重试是否真的救回首屏，不含任何令牌/用户信息。
     */
    suspend fun recommendFirstScreen(page: Int = 1): ApiResponse<PageData<Video>> {
        val start = SystemClock.elapsedRealtime()
        try {
            return homeRecommendService.getRecommend(page)
        } catch (e: IOException) {
            if (!HOME_RETRY_ON_TIMEOUT) throw e
            Log.w(
                TAG,
                "home_first_screen retry=start attempt=1 elapsed=${SystemClock.elapsedRealtime() - start}ms " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            val retryStart = SystemClock.elapsedRealtime()
            try {
                val resp = homeRecommendService.getRecommend(page)
                Log.i(
                    TAG,
                    "home_first_screen retry=success elapsed=${SystemClock.elapsedRealtime() - retryStart}ms " +
                        "total=${SystemClock.elapsedRealtime() - start}ms"
                )
                return resp
            } catch (e2: IOException) {
                Log.w(
                    TAG,
                    "home_first_screen retry=fail elapsed=${SystemClock.elapsedRealtime() - retryStart}ms " +
                        "total=${SystemClock.elapsedRealtime() - start}ms " +
                        "${e2.javaClass.simpleName}: ${e2.message}"
                )
                throw e2
            }
        }
    }

    fun fullUrl(path: String): String {
        if (path.startsWith("http")) return path
        return if (path.startsWith("/")) BASE_URL + path
        else "$BASE_URL/$path"
    }
}
