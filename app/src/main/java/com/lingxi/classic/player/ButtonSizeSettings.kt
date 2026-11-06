package com.lingxi.classic.player

import android.content.Context
import android.content.SharedPreferences
import android.widget.TextView
import com.lingxi.classic.LingXiApp
import kotlin.math.roundToInt

/**
 * 播放页控制条按钮大小（持久化）。
 * 只作用于单个控件的 minWidth/minHeight/padding/textSize，不改全局 density，
 * 因此不会影响其他页面与布局换算。
 */
object ButtonSizeSettings {

    private const val PREFS_NAME = "lingxi_ui"
    private const val KEY_SIZE = "control_button_size"

    const val SMALL = 0
    const val STANDARD = 1
    const val LARGE = 2

    const val DEFAULT = STANDARD

    val labels = arrayOf("小", "标准", "大")

    private val prefs: SharedPreferences by lazy {
        LingXiApp.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var level: Int
        get() = prefs.getInt(KEY_SIZE, DEFAULT).coerceIn(SMALL, LARGE)
        set(value) = prefs.edit().putInt(KEY_SIZE, value.coerceIn(SMALL, LARGE)).apply()

    fun label(level: Int = this.level): String = labels[level.coerceIn(SMALL, LARGE)]

    /** 最小触控边长：标准 48dp（Material 最小触控目标），大 56dp，小 40dp 仍高于 36dp 下限 */
    fun minTouchDp(level: Int = this.level): Int = when (level.coerceIn(SMALL, LARGE)) {
        SMALL -> 40
        LARGE -> 56
        else -> 48
    }

    private fun horizontalPaddingDp(level: Int): Int = when (level.coerceIn(SMALL, LARGE)) {
        SMALL -> 8
        LARGE -> 12
        else -> 10
    }

    private fun verticalPaddingDp(level: Int): Int = when (level.coerceIn(SMALL, LARGE)) {
        SMALL -> 5
        LARGE -> 9
        else -> 7
    }

    private fun textSizeSp(level: Int): Float = when (level.coerceIn(SMALL, LARGE)) {
        SMALL -> 12f
        LARGE -> 15f
        else -> 13f
    }

    /** 纯图标按钮（如返回箭头的容器）：只放大触控区域与内边距，不改文字字号 */
    fun applyTouchTarget(view: TextView, level: Int = this.level) {
        val l = level.coerceIn(SMALL, LARGE)
        val density = view.resources.displayMetrics.density
        val min = (minTouchDp(l) * density).roundToInt()
        view.minWidth = min
        view.minHeight = min
        view.setPadding(
            (horizontalPaddingDp(l) * density).roundToInt(),
            (verticalPaddingDp(l) * density).roundToInt(),
            (horizontalPaddingDp(l) * density).roundToInt(),
            (verticalPaddingDp(l) * density).roundToInt()
        )
    }

    /** 带文字的控制按钮：触控区域、内边距与字号同步放大 */
    fun applyLabelButton(view: TextView, level: Int = this.level) {
        applyTouchTarget(view, level)
        view.textSize = textSizeSp(level.coerceIn(SMALL, LARGE))
    }
}
