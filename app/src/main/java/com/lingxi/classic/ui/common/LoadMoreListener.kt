package com.lingxi.classic.ui.common

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/** 滚动到底自动加载更多的监听器 */
class LoadMoreListener(
    private val threshold: Int = 4,
    private val onLoadMore: () -> Unit
) : RecyclerView.OnScrollListener() {

    var loading = false
    var hasMore = true

    fun reset() {
        loading = false
        hasMore = true
    }

    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        if (dy <= 0 || loading || !hasMore) return
        val lm = rv.layoutManager ?: return
        val lastVisible = when (lm) {
            is LinearLayoutManager -> lm.findLastVisibleItemPosition()
            is StaggeredGridLayoutManager -> {
                val arr = lm.findLastVisibleItemPositions(null)
                arr.maxOrNull() ?: 0
            }
            else -> return
        }
        if (lastVisible >= lm.itemCount - threshold) {
            loading = true
            onLoadMore()
        }
    }
}
