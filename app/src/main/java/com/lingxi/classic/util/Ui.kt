package com.lingxi.classic.util

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
 * 平板上把内容列限制在最大宽度并水平居中。
 * 手机（sw<600dp）不做任何处理，保持原有全宽布局；平板横竖屏、分屏均按当前宽度取较小值。
 */
fun View.centerContentOnTablet(maxWidthDp: Int) {
    val ctx = context
    if (!ctx.isTablet()) return
    val width = minOf(maxWidthDp, ctx.resources.configuration.screenWidthDp).dp(ctx)
    val lp = layoutParams
    lp.width = width
    if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.CENTER_HORIZONTAL
    layoutParams = lp
}

/** API 返回体统一处理：code!=0 时吐 message 并返回 null */
fun <T> com.lingxi.classic.data.model.ApiResponse<T>.unwrap(context: Context? = null): T? {
    if (isSuccess) return data
    context?.toast(message.ifBlank { "请求失败 ($code)" })
    return null
}
