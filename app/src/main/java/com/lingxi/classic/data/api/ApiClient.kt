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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    /** 刷新令牌专用客户端：不带鉴权与 401 处理，避免刷新请求再触发刷新 */
    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
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
            .retryOnConnectionFailure(true)
            // 上游抖动（502/503/504）自动重试，间隔递增
            .addInterceptor { chain ->
                val request = chain.request()
                var response = chain.proceed(request)
                var attempt = 0
                while (response.code in RETRYABLE_HTTP_CODES && attempt < MAX_SERVER_RETRY) {
                    attempt++
                    response.close()
                    Thread.sleep(600L * attempt)
                    response = chain.proceed(request)
                }
                response
            }
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
