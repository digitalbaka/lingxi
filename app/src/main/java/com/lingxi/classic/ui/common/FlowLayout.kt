package com.lingxi.classic.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 简易流式布局：子 View 从左到右排列，超宽自动换行。
 * 子 View 的 layout_margin 作为 item 间距（搜索历史 / 热门标签 chips 用）。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        var lineWidth = paddingLeft
        var lineHeight = 0
        var totalHeight = paddingTop
        var maxWidth = paddingLeft + paddingRight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childW = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childH = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (widthMode != MeasureSpec.UNSPECIFIED && lineWidth + childW + paddingRight > widthSize) {
                // 换行
                maxWidth = maxOf(maxWidth, lineWidth + paddingRight)
                lineWidth = paddingLeft + childW
                totalHeight += lineHeight
                lineHeight = childH
            } else {
                lineWidth += childW
                lineHeight = maxOf(lineHeight, childH)
            }
        }
        totalHeight += lineHeight + paddingBottom
        maxWidth = maxOf(maxWidth, lineWidth + paddingRight)

        setMeasuredDimension(
            if (widthMode == MeasureSpec.EXACTLY) widthSize else maxWidth,
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val parentWidth = r - l
        var left = paddingLeft
        var top = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childW = child.measuredWidth
            val childH = child.measuredHeight
            if (left + lp.leftMargin + childW + lp.rightMargin + paddingRight > parentWidth) {
                left = paddingLeft
                top += lineHeight
                lineHeight = 0
            }
            val cl = left + lp.leftMargin
            val ct = top + lp.topMargin
            child.layout(cl, ct, cl + childW, ct + childH)
            left += lp.leftMargin + childW + lp.rightMargin
            lineHeight = maxOf(lineHeight, lp.topMargin + childH + lp.bottomMargin)
        }
    }
}
