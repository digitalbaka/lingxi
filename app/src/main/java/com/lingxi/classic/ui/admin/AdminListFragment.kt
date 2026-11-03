package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.lingxi.classic.R
import com.lingxi.classic.databinding.FragmentAdminListBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/** 管理后台通用 RecyclerView Adapter 基类（ViewBinding 驱动） */
abstract class AdminAdapter<T, VB : ViewBinding> : RecyclerView.Adapter<AdminAdapter.VH<VB>>() {

    val items = mutableListOf<T>()

    class VH<VB : ViewBinding>(val b: VB) : RecyclerView.ViewHolder(b.root)

    protected abstract fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): VB
    protected abstract fun onBind(b: VB, item: T, position: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH<VB> =
        VH(onCreateBinding(LayoutInflater.from(parent.context), parent))

    override fun onBindViewHolder(holder: VH<VB>, position: Int) = onBind(holder.b, items[position], position)

    override fun getItemCount(): Int = items.size

    fun submit(list: List<T>, append: Boolean = false) {
        if (!append) items.clear()
        val start = items.size
        items.addAll(list)
        if (append && start > 0) notifyItemRangeInserted(start, list.size)
        else notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}

/**
 * 管理后台分页列表 Fragment 基类：
 * 状态筛选 Tab + 搜索行 + SwipeRefresh + 自动加载更多
 */
abstract class AdminListFragment<T> : Fragment() {

    private var _binding: FragmentAdminListBinding? = null
    protected val binding get() = _binding!!

    protected abstract fun createAdapter(): AdminAdapter<T, *>

    /** 拉取一页数据：返回 列表 到 是否还有更多 */
    protected abstract suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<T>, Boolean>

    /** 状态筛选项（标签 到 status 值），空表示无筛选 */
    protected open fun filters(): List<Pair<String, String>> = emptyList()
    protected open fun searchable(): Boolean = false
    protected open fun emptyText(): String = "暂无数据"
    protected open fun onViewReady() {}

    protected lateinit var adapter: AdminAdapter<T, *>
    private lateinit var loadMore: LoadMoreListener

    protected var currentStatus = ""
    protected var currentKeyword = ""
    private var page = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = createAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        loadMore = LoadMoreListener { load() }
        binding.recycler.addOnScrollListener(loadMore)
        binding.swipe.setOnRefreshListener { refresh() }
        binding.empty.text = emptyText()

        val filterDefs = filters()
        if (filterDefs.isNotEmpty()) {
            binding.filterScroll.visible = true
            buildTabs(binding.filterRow, filterDefs.map { it.first }, filterDefs.indexOfFirst { it.second == currentStatus }.coerceAtLeast(0)) { index ->
                currentStatus = filterDefs[index].second
                refresh()
            }
        }

        if (searchable()) {
            binding.searchRow.visible = true
            val doSearch = {
                currentKeyword = binding.etSearch.text.toString().trim()
                refresh()
            }
            binding.btnSearch.setOnClickListener { doSearch() }
            binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
            }
        }

        onViewReady()
        refresh()
    }

    /** 构建一行可选 Tab（选中绿底加粗，未选灰字） */
    protected fun buildTabs(row: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val ctx = requireContext()
        row.removeAllViews()
        labels.forEachIndexed { index, label ->
            val tv = TextView(ctx).apply {
                text = label
                textSize = 13f
                val hp = 14.dp(ctx)
                val vp = 6.dp(ctx)
                setPadding(hp, vp, hp, vp)
                setOnClickListener { onPick(index) }
            }
            row.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8.dp(ctx)
            })
        }
        styleTabs(row, selected)
    }

    protected fun styleTabs(row: LinearLayout, selected: Int) {
        val ctx = requireContext()
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            if (i == selected) {
                tv.setBackgroundResource(R.drawable.bg_tag)
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_green_dark))
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                tv.background = null
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_text_sub))
                tv.typeface = Typeface.DEFAULT
            }
        }
    }

    fun refresh() {
        page = 1
        loadMore.reset()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (list, hasMore) = fetch(currentStatus, currentKeyword, page)
                adapter.submit(list, append = page > 1)
                loadMore.loading = false
                loadMore.hasMore = hasMore
                page++
                binding.empty.visible = adapter.items.isEmpty()
            } catch (e: Exception) {
                loadMore.loading = false
                context?.toast("加载失败：${e.message}")
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
