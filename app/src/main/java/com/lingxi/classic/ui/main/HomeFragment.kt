package com.lingxi.classic.ui.main

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdItem
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.databinding.FragmentHomeBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.BannerView
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.gridSpanCount
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** 首页：Banner 轮播 + 置顶视频（📌 前缀）+ 推荐流，2 列网格，下拉刷新 + 滚动分页 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var loadMoreListener: LoadMoreListener
    private var bannerView: BannerView? = null

    private var page = 1
    private var hasMore = true
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoAdapter(onClick = { Navigator.openVideo(requireContext(), it) })
        gridLayoutManager = GridLayoutManager(requireContext(), requireContext().gridSpanCount())
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeaderPosition(position)) gridLayoutManager.spanCount else 1
        }
        binding.recyclerView.layoutManager = gridLayoutManager
        // Banner 作为列表全宽头部，有数据时才显示
        adapter.setHeader { parent ->
            BannerView(parent.context).apply {
                val m = 8.dp(parent.context)
                val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(m, m, m, 0)
                layoutParams = lp
                onOpen = { entry -> openBanner(entry) }
                onShown = { entry -> reportImpression(entry) }
                bannerView = this
            }
        }
        binding.recyclerView.adapter = adapter

        loadMoreListener = LoadMoreListener { loadMore() }
        binding.recyclerView.addOnScrollListener(loadMoreListener)

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.tvEmpty.setOnClickListener { refresh() }

        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerView = null
        _binding = null
    }

    /** MainActivity 声明了 configChanges，旋转不重建，这里手动刷新列数 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::gridLayoutManager.isInitialized) {
            gridLayoutManager.spanCount = requireContext().gridSpanCount()
        }
    }

    /** 首屏/刷新：轮播、置顶与推荐并行请求 */
    private fun refresh() {
        if (loading) return
        loading = true
        page = 1
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val adsDeferred = async { runCatching { ApiClient.apiService.getAds() }.getOrNull() }
                val pinnedDeferred = async { runCatching { ApiClient.apiService.getPinned(6) }.getOrNull() }
                val recommendDeferred = async { runCatching { ApiClient.apiService.getRecommend(1) }.getOrNull() }

                // 置顶与轮播失败静默，不阻塞推荐流
                val pinned = pinnedDeferred.await()?.unwrap()?.items.orEmpty()
                updateBanner(adsDeferred.await()?.unwrap()?.items.orEmpty(), pinned)

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

    /** 广告/海报为空时按文档回落到置顶视频轮播 */
    private fun updateBanner(ads: List<AdItem>, pinned: List<Video>) {
        val entries = if (ads.isNotEmpty()) {
            ads.map { BannerView.BannerEntry(it.id, it.kind, it.title, ApiClient.fullUrl(it.imageUrl), it.linkUrl, it.label) }
        } else {
            pinned.take(5).map {
                BannerView.BannerEntry(it.id, "video", it.title, it.getFullCoverUrl(ApiClient.BASE_URL), "/video/${it.id}")
            }
        }
        bannerView?.setItems(entries)
    }

    private val videoLinkRegex = Regex("/video/(\\d+)")

    private fun openBanner(entry: BannerView.BannerEntry) {
        val ctx = context ?: return
        if (entry.kind == "ad" || entry.kind == "poster") {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { ApiClient.apiService.reportPromotionClick(entry.kind, entry.id) }
            }
        }
        val vid = videoLinkRegex.find(entry.linkUrl)?.groupValues?.get(1)?.toLongOrNull()
        if (entry.kind == "video" || vid != null) {
            Navigator.openVideo(ctx, vid ?: entry.id)
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ApiClient.fullUrl(entry.linkUrl)))) }
        }
    }

    /** 曝光上报：服务端按访客+天幂等，每次轮换到都可报 */
    private fun reportImpression(entry: BannerView.BannerEntry) {
        if (entry.kind != "ad" && entry.kind != "poster") return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { ApiClient.apiService.reportPromotionImpression(entry.kind, entry.id) }
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
