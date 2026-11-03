package com.lingxi.classic.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.databinding.FragmentHomeBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** 首页：置顶视频（📌 前缀）+ 推荐流，2 列网格，下拉刷新 + 滚动分页 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private lateinit var loadMoreListener: LoadMoreListener

    private var page = 1
    private var hasMore = true
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoAdapter(onClick = { Navigator.openVideo(requireContext(), it) })
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerView.adapter = adapter

        loadMoreListener = LoadMoreListener { loadMore() }
        binding.recyclerView.addOnScrollListener(loadMoreListener)

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.tvEmpty.setOnClickListener { refresh() }

        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 首屏/刷新：置顶与推荐并行请求 */
    private fun refresh() {
        if (loading) return
        loading = true
        page = 1
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pinnedDeferred = async { runCatching { ApiClient.apiService.getPinned(6) }.getOrNull() }
                val recommendDeferred = async { runCatching { ApiClient.apiService.getRecommend(1) }.getOrNull() }

                // 置顶失败静默，不阻塞推荐流
                val pinned = pinnedDeferred.await()?.unwrap()?.items.orEmpty()
                val recommendResp = recommendDeferred.await()
                if (recommendResp == null) {
                    context?.toast("网络异常")
                    showError()
                    return@launch
                }
                val recommend = recommendResp.unwrap(context)
                if (recommend == null) {
                    showError()
                    return@launch
                }

                val merged = ArrayList<Video>(pinned.size + recommend.items.size)
                pinned.forEach { merged.add(it.copy(title = "📌 ${it.title}", pinned = true)) }
                merged.addAll(recommend.items)

                adapter.submit(merged)
                hasMore = recommend.hasMore
                loadMoreListener.reset()
                loadMoreListener.hasMore = hasMore

                if (merged.isEmpty()) {
                    binding.tvEmpty.text = "暂无内容"
                    binding.tvEmpty.visible = true
                } else {
                    binding.tvEmpty.visible = false
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
                showError()
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    /** 滚动到底加载下一页推荐 */
    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val d = ApiClient.apiService.getRecommend(page + 1).unwrap(context)
                if (d != null) {
                    page += 1
                    adapter.append(d.items)
                    hasMore = d.hasMore
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
            } finally {
                loading = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun showError() {
        if (adapter.itemCount == 0) {
            binding.tvEmpty.text = "加载失败，点击重试"
            binding.tvEmpty.visible = true
        }
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
