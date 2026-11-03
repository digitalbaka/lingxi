package com.lingxi.classic.ui.main

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Partition
import com.lingxi.classic.databinding.FragmentPartitionBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.gridSpanCount
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/**
 * 分区页：顶部横向分区栏（含「全部」「排行」伪分区）+ 2 列视频网格。
 * 全部/具体分区支持 newest/hottest 排序切换与分页；排行不分页、不排序。
 */
class PartitionFragment : Fragment() {

    private var _binding: FragmentPartitionBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var loadMoreListener: LoadMoreListener

    private val partitions = mutableListOf<Partition>()

    /** 选中项：0=全部，1=排行，>=2 对应 partitions[index-2] */
    private var selected = 0
    private var sort = "newest"
    private var page = 1
    private var hasMore = true
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPartitionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoAdapter(onClick = { Navigator.openVideo(requireContext(), it) })
        gridLayoutManager = GridLayoutManager(requireContext(), requireContext().gridSpanCount())
        binding.rvVideos.layoutManager = gridLayoutManager
        binding.rvVideos.adapter = adapter

        loadMoreListener = LoadMoreListener { loadNext() }
        binding.rvVideos.addOnScrollListener(loadMoreListener)

        binding.swipeRefresh.setOnRefreshListener { reload() }
        binding.tvEmpty.setOnClickListener { reload() }

        binding.btnSort.setOnClickListener {
            sort = if (sort == "newest") "hottest" else "newest"
            binding.btnSort.text = if (sort == "newest") "最新" else "最热"
            reload()
        }

        loadPartitions()
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** MainActivity 声明了 configChanges，旋转不重建，这里手动刷新列数 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::gridLayoutManager.isInitialized) {
            gridLayoutManager.spanCount = requireContext().gridSpanCount()
        }
    }

    // ==================== 分区栏 ====================

    private fun loadPartitions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val d = ApiClient.apiService.getPartitions().unwrap() ?: return@launch
                partitions.clear()
                partitions.addAll(d.items)
                buildChips()
            } catch (e: Exception) {
                // 分区栏失败不阻塞内容区
            }
        }
    }

    private fun buildChips() {
        val container = binding.llPartitions
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (6 * density).toInt()
        val count = 2 + partitions.size
        for (i in 0 until count) {
            val name = when (i) {
                0 -> "全部"
                1 -> "排行"
                else -> {
                    val p = partitions[i - 2]
                    if (p.videoCount > 0) "${p.name} ${p.videoCount}" else p.name
                }
            }
            val tv = TextView(requireContext()).apply {
                text = name
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setOnClickListener { selectPartition(i) }
            }
            container.addView(tv)
        }
        styleChips()
    }

    private fun styleChips() {
        val container = binding.llPartitions
        val normalColor = ContextCompat.getColor(requireContext(), R.color.lx_text)
        val pseudoSelectedColor = ContextCompat.getColor(requireContext(), R.color.lx_green_dark)
        for (i in 0 until container.childCount) {
            val tv = container.getChildAt(i) as? TextView ?: continue
            if (i == selected) {
                tv.setBackgroundResource(R.drawable.bg_tag)
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT)
            }
            tv.setTextColor(
                when {
                    i >= 2 -> partitionColor(partitions[i - 2].color)
                    i == selected -> pseudoSelectedColor
                    else -> normalColor
                }
            )
        }
    }

    /** 分区自定义颜色，解析失败回退主题绿 */
    private fun partitionColor(color: String): Int = try {
        Color.parseColor(color)
    } catch (e: Exception) {
        ContextCompat.getColor(requireContext(), R.color.lx_green)
    }

    private fun selectPartition(index: Int) {
        if (index == selected) return
        selected = index
        styleChips()
        // 排行不支持排序
        binding.btnSort.visible = index != 1
        reload()
    }

    // ==================== 视频列表 ====================

    private fun reload() {
        if (loading) return
        page = 1
        hasMore = true
        loadInternal()
    }

    private fun loadNext() {
        if (loading || !hasMore || selected == 1) return
        page += 1
        loadInternal()
    }

    private fun loadInternal() {
        loading = true
        if (page == 1) binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (selected == 1) {
                    // 排行榜：一次拉全量，不分页
                    val d = ApiClient.apiService.getRanking().unwrap(context)
                    if (d != null) {
                        adapter.submit(d.items)
                        hasMore = false
                        updateEmpty()
                    } else if (page == 1) {
                        showError()
                    }
                } else {
                    val slug = if (selected >= 2) partitions.getOrNull(selected - 2)?.slug else null
                    val d = ApiClient.apiService.getVideos(page = page, sort = sort, partition = slug).unwrap(context)
                    if (d != null) {
                        if (page == 1) adapter.submit(d.items) else adapter.append(d.items)
                        hasMore = d.hasMore
                        updateEmpty()
                    } else if (page == 1) {
                        showError()
                    } else {
                        page -= 1
                    }
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
                if (page == 1) showError() else page -= 1
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun updateEmpty() {
        if (adapter.itemCount == 0 && page == 1) {
            binding.tvEmpty.text = "暂无内容"
            binding.tvEmpty.visible = true
        } else {
            binding.tvEmpty.visible = false
        }
    }

    private fun showError() {
        if (adapter.itemCount == 0) {
            binding.tvEmpty.text = "加载失败，点击重试"
            binding.tvEmpty.visible = true
        }
    }

    companion object {
        fun newInstance() = PartitionFragment()
    }
}
