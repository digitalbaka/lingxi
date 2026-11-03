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

/** API 返回体统一处理：code!=0 时吐 message 并返回 null */
fun <T> com.lingxi.classic.data.model.ApiResponse<T>.unwrap(context: Context? = null): T? {
    if (isSuccess) return data
    context?.toast(message.ifBlank { "请求失败 ($code)" })
    return null
}
