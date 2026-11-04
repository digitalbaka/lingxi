package com.lingxi.classic.ui.main

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.reflect.TypeToken
import com.lingxi.classic.R
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
import com.lingxi.classic.util.openInBrowser
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

        // 重试进度只做轻量提示（不弹 toast）；回到后台/销毁时清理，避免指向已销毁的视图
        ApiClient.onRetry = { attempt, max -> showRetrying(attempt, max) }

        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ApiClient.onRetry = null
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

    /** 首屏/刷新：轮播、置顶与推荐并行请求；推荐失败时用上次成功的数据兜底 */
    private fun refresh() {
        if (loading) return
        loading = true
        page = 1
        binding.swipeRefresh.isRefreshing = true
        // 首次请求期间不能白屏：列表没有真实数据时先给「正在加载…」
        if (adapter.dataCount == 0) showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val adsDeferred = async { runCatching { ApiClient.apiService.getAds() } }
                val pinnedDeferred = async { runCatching { ApiClient.apiService.getPinned(6) } }
                val recommendDeferred = async { runCatching { ApiClient.apiService.getRecommend(1) } }

                // 置顶与轮播失败静默，不阻塞推荐流
                val pinned = pinnedDeferred.await().getOrNull()?.unwrap()?.items.orEmpty()
                updateBanner(adsDeferred.await().getOrNull()?.unwrap()?.items.orEmpty(), pinned)

                val recommendResult = recommendDeferred.await()
                val recommend = recommendResult.getOrNull()?.unwrap(context)
                if (recommend == null) {
                    // 推荐流是首屏骨架：有上次成功的缓存就用缓存，避免整屏空白；没有才显示错误态
                    val cached = loadCachedFeed()
                    if (!cached.isNullOrEmpty()) {
                        applyFeed(cached)
                        hasMore = false
                        loadMoreListener.reset()
                        loadMoreListener.hasMore = false
                    } else {
                        val tip = errorTip(recommendResult.exceptionOrNull())
                        // 业务 code!=0 时 unwrap 已吐过服务端文案，这里不再重复弹；只有网络异常才提示一次
                        if (recommendResult.isFailure) context?.toast(tip)
                        showError(tip)
                    }
                    return@launch
                }

                val merged = ArrayList<Video>(pinned.size + recommend.items.size)
                pinned.forEach { merged.add(it.copy(title = "📌 ${it.title}", pinned = true)) }
                merged.addAll(recommend.items)

                applyFeed(merged)
                saveFeedCache(merged)
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
                val tip = errorTip(e)
                context?.toast(tip)
                showError(tip)
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
            ctx.openInBrowser(ApiClient.fullUrl(entry.linkUrl))
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
                // 分页失败只提示一次，重试进度由 ApiClient.onRetry 轻量展示
                context?.toast(errorTip(e))
            } finally {
                loading = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun applyFeed(items: List<Video>) {
        adapter.submit(items)
        if (items.isNotEmpty()) binding.tvEmpty.visible = false
    }

    /** 重试期间在空视图上显示 "正在重试 (2/3)"，不弹 toast、不遮内容 */
    private fun showRetrying(attempt: Int, max: Int) {
        val root = _binding?.root ?: return
        root.post {
            val b = _binding ?: return@post
            if (adapter.dataCount == 0) {
                b.tvEmpty.text = getString(R.string.home_retrying, attempt, max)
                b.tvEmpty.visible = true
            }
        }
    }

    /** 按错误类型给出分层文案：无网络 / 加密连接失败 / 服务端不可用 */
    private fun errorTip(error: Throwable?): String = when (ApiClient.classifyError(error)) {
        ApiClient.ErrorKind.TLS -> getString(R.string.net_error_tls)
        ApiClient.ErrorKind.SERVER -> (error as? ApiClient.HttpServerException)
            ?.let { getString(R.string.net_error_server, it.code) }
            ?: getString(R.string.net_error_unknown)
        ApiClient.ErrorKind.NO_NETWORK -> getString(R.string.net_error_no_network)
        ApiClient.ErrorKind.UNKNOWN -> getString(R.string.net_error_unknown)
    }

    /** 首屏加载态：仅在没有真实数据时占位，已有内容时保持列表可见 */
    private fun showLoading() {
        binding.tvEmpty.text = getString(R.string.home_loading)
        binding.tvEmpty.visible = true
    }

    private fun showError(message: String) {
        if (adapter.dataCount == 0) {
            binding.tvEmpty.text = message
            binding.tvEmpty.visible = true
        }
    }

    /** 首页缓存只留前 20 条，进程内共享并落到 cacheDir：体积小，1GB 运存设备也放心 */
    private fun saveFeedCache(items: List<Video>) {
        if (items.isEmpty()) return
        val snapshot = items.take(CACHE_MAX)
        cachedFeed = snapshot
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                File(ctx.cacheDir, CACHE_FILE).writeText(ApiClient.gson.toJson(snapshot))
            }
        }
    }

    private suspend fun loadCachedFeed(): List<Video>? {
        cachedFeed?.let { return it }
        val ctx = context ?: return null
        return withContext(Dispatchers.IO) {
            val feed = runCatching {
                val file = File(ctx.cacheDir, CACHE_FILE)
                if (!file.exists()) return@runCatching null
                val type = object : TypeToken<List<Video>>() {}.type
                ApiClient.gson.fromJson<List<Video>>(file.readText(), type)
            }.getOrNull()
            if (!feed.isNullOrEmpty()) cachedFeed = feed
            feed
        }
    }

    companion object {
        private const val CACHE_FILE = "home_feed.json"
        private const val CACHE_MAX = 20

        /** 上次成功的首页内容，冷启动时从 cacheDir 的轻量文件恢复 */
        @Volatile
        private var cachedFeed: List<Video>? = null

        fun newInstance() = HomeFragment()
    }
}
