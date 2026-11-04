package com.lingxi.classic.data.api

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
import com.lingxi.classic.data.model.ApiResponse
import com.lingxi.classic.data.model.LoginResult
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * API 客户端 - 全局单例（连接池复用，1GB 内存设备友好）
 */
object ApiClient {

    const val BASE_URL = ApiService.BASE_URL

    private const val CONNECT_TIMEOUT = 7L
    /** 读超时保留较宽，避免误杀慢接口（推荐流/搜索），重试只针对连接与握手阶段 */
    private const val READ_TIMEOUT = 25L
    private const val WRITE_TIMEOUT = 25L
    private const val TAG = "LingXiApi"

    /** 首次 + 2 次重试 */
    private const val MAX_ATTEMPTS = 3

    /** 网关/上游抖动码：主站偶发 502/503/504，重试通常可恢复 */
    private val RETRYABLE_HTTP_CODES = setOf(502, 503, 504)

    /**
     * 只自动重试幂等请求：GET/HEAD/OPTIONS。
     * POST（发消息、发评论、点赞、下单、上传等）/PUT/PATCH/DELETE 一律不重试，避免服务端重复写入。
     * DELETE 虽在 HTTP 语义上幂等，但服务端实现未必如此，同样划入不重试。
     */
    private val RETRYABLE_METHODS = setOf("GET", "HEAD", "OPTIONS")

    /**
     * 可重试的传输层异常：握手/读超时、TLS 证书或握手失败、连接重置。
     * UnknownHostException（DNS 解析失败）不在其中：域名都解析不出来时重试没有意义。
     */
    private fun isRetryableIO(e: IOException): Boolean = when (e) {
        is SocketTimeoutException, is SSLException, is ConnectException, is SocketException -> true
        else -> false
    }

    /** 服务端 5xx（含网关返回的 HTML 错误页）：单独成一类，避免被当成「数据格式错误」 */
    class HttpServerException(val code: Int) : IOException("HTTP $code")

    enum class ErrorKind { NO_NETWORK, TLS, SERVER, UNKNOWN }

    /**
     * 错误分类，供上层给出「是不是你自己网络问题」的判断依据。
     * 注意 TLS 握手超时实测会抛成 `SocketTimeoutException("SSL handshake timed out")`，
     * 所以要先看消息里的 handshake/SSL/TLS 关键字，再按异常类型归类。
     */
    fun classifyError(e: Throwable?): ErrorKind {
        if (e == null) return ErrorKind.UNKNOWN
        if (e is HttpServerException) return ErrorKind.SERVER
        val msg = e.message.orEmpty()
        if (e is SSLException ||
            msg.contains("handshake", ignoreCase = true) ||
            msg.contains("SSL", ignoreCase = true) ||
            msg.contains("TLS", ignoreCase = true) ||
            msg.contains("certificate", ignoreCase = true)
        ) return ErrorKind.TLS
        if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
            e is SocketException || e is NoRouteToHostException
        ) return ErrorKind.NO_NETWORK
        return ErrorKind.UNKNOWN
    }

    /** 页面注册的重试进度回调，在 OkHttp 工作线程触发（参数为「即将进行的第几次尝试 / 总次数」） */
    @Volatile
    var onRetry: ((attempt: Int, max: Int) -> Unit)? = null

    /** 5xx 且响应体不是 JSON（502 常返回 CDN/网关的 HTML 错误页）时按服务端错误处理 */
    private fun looksLikeJson(response: Response): Boolean {
        val contentType = response.body?.contentType()?.toString().orEmpty()
        if (contentType.contains("json", ignoreCase = true)) return true
        return runCatching {
            val head = response.peekBody(256).string().trimStart()
            head.startsWith("{") || head.startsWith("[")
        }.getOrDefault(false)
    }

    private fun notifyRetry(nextAttempt: Int) {
        val callback = onRetry ?: return
        runCatching { callback(nextAttempt, MAX_ATTEMPTS) }
            .onFailure { Log.w(TAG, "onRetry callback failed: ${it.message}") }
    }

    /** 退避 1s → 2s，带 ±20% 抖动，避免所有客户端同一时刻整齐重试 */
    private fun sleepBeforeRetry(attempt: Int) {
        val baseMs = if (attempt <= 1) 1000L else 2000L
        val factor = 0.8 + kotlin.random.Random.nextDouble() * 0.4
        try {
            Thread.sleep((baseMs * factor).toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            // 转成 IOException 交给 OkHttp/Retrofit 的取消路径，避免非受检异常冒到调用线程
            throw java.io.InterruptedIOException("retry sleep interrupted")
        }
    }

    /**
     * 每次尝试只记一行：方法 + 去掉 query 的 URL + 结果（HTTP 码或异常类名）+ 耗时。
     * 仅用于回答「请求有没有发出、失败在哪一类」，绝不输出 body/header/凭证。
     */
    private fun logAttempt(request: Request, attempt: Int, outcome: String, startMs: Long) {
        val safeUrl = "${request.url.scheme}://${request.url.host}${request.url.encodedPath}"
        Log.w(TAG, "attempt=$attempt/$MAX_ATTEMPTS ${request.method} $safeUrl -> $outcome ${System.currentTimeMillis() - startMs}ms")
    }

    /**
     * 统一重试执行：幂等请求遇到 502/503/504 或可重试的传输层异常时最多尝试 MAX_ATTEMPTS 次。
     * 收口在拦截器里，Retrofit 的一次调用只会有一次结果，不会重复回调/重复渲染。
     */
    private fun executeWithRetry(chain: Interceptor.Chain, request: Request): Response {
        val idempotent = request.method in RETRYABLE_METHODS
        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val startMs = System.currentTimeMillis()
            try {
                val response = chain.proceed(request)
                logAttempt(request, attempt, "HTTP ${response.code}", startMs)
                if (!idempotent || response.code !in RETRYABLE_HTTP_CODES || attempt == MAX_ATTEMPTS) {
                    // 5xx 且不是 JSON（网关 HTML 错误页）→ 抛服务端错误，交给上层归类为「服务端不可用」
                    if (response.code >= 500 && !looksLikeJson(response)) {
                        response.close()
                        throw HttpServerException(response.code)
                    }
                    return response
                }
                // 幂等请求且仍可重试：关闭当前响应（释放连接）后重来
                response.close()
            } catch (e: IOException) {
                if (e is HttpServerException) throw e
                logAttempt(request, attempt, e.javaClass.simpleName, startMs)
                lastError = e
                if (!idempotent || !isRetryableIO(e) || attempt == MAX_ATTEMPTS) throw e
            }
            notifyRetry(attempt + 1)
            sleepBeforeRetry(attempt)
        }
        throw lastError ?: IOException("request failed")
    }

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

    /** 刷新令牌专用客户端：不带鉴权与 401 处理，避免刷新请求再触发刷新；也不做隐式重试（refreshToken 是轮换的） */
    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * accessToken 有效期仅 2 小时，过期用 refreshToken 续期。
     * refresh 是轮换的：成功后必须保存响应里的新 refreshToken。
     */
    @Synchronized
    private fun refreshAccessToken(): String? {
        val refresh = AuthManager.refreshToken
        if (refresh.isNullOrEmpty()) {
            Log.w(TAG, "no refreshToken, clearing session")
            AuthManager.clearSession()
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
                    if (response.code == 400 || response.code == 401) AuthManager.clearSession()
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
            // 关掉 OkHttp 隐式重试：它会重发 POST，可能造成重复写入；幂等请求由下面的拦截器负责重试
            .retryOnConnectionFailure(false)
            // 统一重试：仅幂等请求，遇 502/503/504 或握手/连接类异常时退避重试
            .addInterceptor { chain -> executeWithRetry(chain, chain.request()) }
            // 自动附加 Authorization
            .addInterceptor { chain ->
                val original = chain.request()
                val token = AuthManager.token
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
            // 401 自动续期后重试原请求
            .authenticator { _, response ->
                if (response.request.header("Authorization") == null) return@authenticator null
                if (authRetryCount(response) > 2) return@authenticator null
                val newToken = refreshAccessToken() ?: return@authenticator null
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

    fun fullUrl(path: String): String {
        if (path.startsWith("http")) return path
        return if (path.startsWith("/")) BASE_URL + path
        else "$BASE_URL/$path"
    }
}
