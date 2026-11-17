package com.lingxi.classic.util

import android.content.Context
import android.view.View
import android.widget.Toast

/** Toast 与 View 可见性扩展 */
fun Context.toast(msg: String?) {
    Toast.makeText(this, msg ?: return, Toast.LENGTH_SHORT).show()
}

fun Context.toastLong(msg: String?) {
    Toast.makeText(this, msg ?: return, Toast.LENGTH_LONG).show()
}

var View.visible: Boolean
    get() = visibility == View.VISIBLE
    set(value) { visibility = if (value) View.VISIBLE else View.GONE }

fun View.gone() { visibility = View.GONE }
fun View.show() { visibility = View.VISIBLE }
fun View.invisible() { visibility = View.INVISIBLE }

fun Int.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

/** 视频网格列数：随屏幕宽度/横竖屏由 res/values* 的 grid_span_count 决定，旋转时读取自动取新值 */
fun Context.gridSpanCount(): Int = resources.getInteger(com.lingxi.classic.R.integer.grid_span_count)

/** sw600dp 及以上视为平板（7 寸+），用于播放器横屏不强制全屏等大尺寸交互差异 */
fun Context.isTablet(): Boolean = resources.configuration.smallestScreenWidthDp >= 600

/**
 * 统一错误文案：沿用现有异常类型（Retrofit 抛 HttpException、OkHttp 抛 IOException），
 * 不再单独造错误码体系。401 → 登录态过期；5xx → 服务器暂时不可用；其余 → 网络异常。
 */
fun Throwable.errorMessageRes(): Int = when {
    this is retrofit2.HttpException && code() == 401 -> com.lingxi.classic.R.string.err_login_expired
    this is retrofit2.HttpException && code() in 500..599 -> com.lingxi.classic.R.string.err_server_unavailable
    else -> com.lingxi.classic.R.string.err_network
}

/** 按异常类型给出可读提示（只提示，不清理会话） */
fun Context.toastError(error: Throwable) = toast(getString(error.errorMessageRes()))

/**
 * 取服务端错误文案。
 *
 * 站点的 HTTP 状态码与业务 code 一致（400/403/...），Retrofit 遇到非 2xx 直接抛 HttpException，
 * 此时统一信封里的 message（如「验证码不正确或已过期」「这个验证码刚刚已经用过了，请等下一个」）
 * 只在 errorBody 里。不解析就只能给用户看「网络异常」，两步验证的失败提示会全部失真。
 */
fun Throwable.apiMessage(fallback: String = "请求失败，请稍后重试"): String {
    if (this !is retrofit2.HttpException) return fallback
    return try {
        val body = response()?.errorBody()?.string()
        val node = if (body.isNullOrBlank()) {
            null
        } else {
            com.lingxi.classic.data.api.ApiClient.gson
                .fromJson(body, com.google.gson.JsonObject::class.java)
                ?.get("message")
        }
        val msg = if (node != null && node.isJsonPrimitive) node.asString else null
        if (msg.isNullOrBlank()) fallback else msg
    } catch (e: Exception) {
        fallback
    }
}

/** API 返回体统一处理：code!=0 时吐 message 并返回 null */
fun <T> com.lingxi.classic.data.model.ApiResponse<T>.unwrap(context: Context? = null): T? {
    if (isSuccess) return data
    context?.toast(message.ifBlank { "请求失败 ($code)" })
    return null
}
