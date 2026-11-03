package com.lingxi.classic.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 首页轮播：双 ImageView 交叉淡入 + 圆点指示 + 自动轮换 + 左右滑动切换。
 * 不使用 ViewPager 等重依赖，1GB 内存设备友好。
 */
@SuppressLint("ClickableViewAccessibility")
class BannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 轮播条目（kind：ad/poster 走推广上报；video 为推荐回落，直接跳播放页） */
    data class BannerEntry(
        val id: Long,
        val kind: String,
        val title: String,
        val imageUrl: String,
        val linkUrl: String,
        val label: String = ""
    )

    var onOpen: ((BannerEntry) -> Unit)? = null
    var onShown: ((BannerEntry) -> Unit)? = null

    private val ivFront = ImageView(context)
    private val ivBack = ImageView(context)
    private val tvTitle: TextView
    private val tvLabel: TextView
    private val dotsLayout: LinearLayout

    private var items: List<BannerEntry> = emptyList()
    private var index = 0
    private var showingFront = true

    private val handler = Handler(Looper.getMainLooper())
    private val rotateTask = object : Runnable {
        override fun run() {
            if (items.size > 1) show((index + 1) % items.size, animate = true)
            handler.postDelayed(this, ROTATE_MS)
        }
    }

    private var downX = 0f
    private var downY = 0f

    init {
        val ivParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        ivFront.scaleType = ImageView.ScaleType.CENTER_CROP
        ivBack.scaleType = ImageView.ScaleType.CENTER_CROP
        ivBack.alpha = 0f
        addView(ivFront, ivParams)
        addView(ivBack, ivParams)

        // 底部标题遮罩条
        val scrim = View(context).apply {
            setBackgroundColor(0x55000000)
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, 30.dp(context)).apply {
            gravity = Gravity.BOTTOM
        })

        tvTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(10.dp(context), 0, 10.dp(context), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(tvTitle, LayoutParams(LayoutParams.MATCH_PARENT, 30.dp(context)).apply {
            gravity = Gravity.BOTTOM
            marginEnd = 80.dp(context)
        })

        tvLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(6.dp(context), 2.dp(context), 6.dp(context), 2.dp(context))
            background = GradientDrawable().apply {
                cornerRadius = 4.dp(context).toFloat()
                setColor(0x99000000.toInt())
            }
        }
        addView(tvLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = 8.dp(context)
            topMargin = 8.dp(context)
        })

        dotsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(dotsLayout, LayoutParams(LayoutParams.WRAP_CONTENT, 30.dp(context)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = 10.dp(context)
        })

        visibility = GONE

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (items.size > 1 && Math.abs(dx) > 48.dp(context) && Math.abs(dx) > Math.abs(dy) * 2) {
                        if (dx < 0) show((index + 1) % items.size, animate = true)
                        else show((index - 1 + items.size) % items.size, animate = true)
                        restartRotation()
                    } else if (Math.abs(dx) < 12.dp(context) && Math.abs(dy) < 12.dp(context)) {
                        items.getOrNull(index)?.let { onOpen?.invoke(it) }
                    }
                }
            }
            true
        }
    }

    /** 2.7:1 宽高比 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = (w / 2.7f).toInt()
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    }

    fun setItems(list: List<BannerEntry>) {
        stopRotation()
        items = list
        index = 0
        if (list.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        buildDots()
        show(0, animate = false)
        startRotation()
    }

    private fun buildDots() {
        dotsLayout.removeAllViews()
        val size = 6.dp(context)
        val margin = 3.dp(context)
        for (i in items.indices) {
            val dot = View(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, 0, margin, 0)
            dotsLayout.addView(dot, lp)
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dotsLayout.childCount) {
            val dot = dotsLayout.getChildAt(i)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) Color.WHITE else 0x66FFFFFF)
            }
        }
    }

    private fun show(newIndex: Int, animate: Boolean) {
        val entry = items.getOrNull(newIndex) ?: return
        index = newIndex
        tvTitle.text = entry.title
        tvLabel.text = entry.label.ifEmpty { if (entry.kind == "ad") "广告" else "" }
        tvLabel.visibility = if (tvLabel.text.isNullOrEmpty()) GONE else VISIBLE
        updateDots()

        val incoming = if (showingFront) ivBack else ivFront
        val outgoing = if (showingFront) ivFront else ivBack
        ImageLoader.load(incoming, entry.imageUrl)
        if (animate) {
            incoming.animate().cancel()
            outgoing.animate().cancel()
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(280).start()
            outgoing.animate().alpha(0f).setDuration(280).start()
        } else {
            incoming.animate().cancel()
            outgoing.animate().cancel()
            incoming.alpha = 1f
            outgoing.alpha = 0f
        }
        showingFront = !showingFront
        onShown?.invoke(entry)
    }

    fun startRotation() {
        stopRotation()
        if (items.size > 1) handler.postDelayed(rotateTask, ROTATE_MS)
    }

    fun stopRotation() {
        handler.removeCallbacks(rotateTask)
    }

    private fun restartRotation() {
        startRotation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 从回收池挂回时恢复当前帧（detached 时已清图）
        if (items.isNotEmpty()) show(index, animate = false)
        startRotation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRotation()
        handler.removeCallbacksAndMessages(null)
        ImageLoader.clear(ivFront)
        ImageLoader.clear(ivBack)
    }

    companion object {
        private const val ROTATE_MS = 4000L
    }
}
