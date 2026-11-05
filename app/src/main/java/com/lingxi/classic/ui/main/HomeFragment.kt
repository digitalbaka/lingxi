package com.lingxi.classic.ui.main

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastError
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
    /** 置顶卡是否已并入当前列表（避免 banner/置顶后补任务重复插入） */
    private var pinnedMerged = false
    /** 刷新代次：后补任务落地时校验，丢弃过期的补齐结果 */
    private var refreshGeneration = 0
    /** 本代推荐已 submit 的代次；置顶早于推荐到达时靠它判断该缓存还是直接合并 */
    private var recommendSubmittedGen = 0
    /** 置顶先于推荐到达时的缓存（仅当前代有效），推荐 submit 后立即合并 */
    private var pendingPinned: List<Video>? = null

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

    /**
     * 首屏/刷新：推荐列表单独等待并落定，banner/置顶走短超时（9s）并行补齐，不阻塞首屏。
     * 之前按「置顶 → banner → 推荐」串行等待，这两条走全局 30s 超时，
     * 导致推荐 11.8s 已拿到、界面 32s 才出（装机实测）。
     */
    private fun refresh() {
        if (loading) {
            // 有请求在跑（分页/补页）时忽略这次下拉，但要把刷新手势的转圈收掉，
            // 否则 SwipeRefreshLayout 的 isRefreshing 会一直停在 true
            binding.swipeRefresh.isRefreshing = false
            return
        }
        loading = true
        page = 1
        pinnedMerged = false
        // 新代开始：上一代没等到的置顶缓存直接丢弃
        pendingPinned = null
        val gen = ++refreshGeneration
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 首屏推荐走短超时 + 换连接重试（ApiClient.recommendFirstScreen），
                // 分页 loadMore() 仍用全局 30s 超时，不受此改造影响
                val recommendResult = runCatching { ApiClient.recommendFirstScreen(1) }
                val recommendResp = recommendResult.getOrNull()
                if (recommendResp == null) {
                    // 按异常类型给提示：401 → 登录态过期、5xx → 服务器不可用、其余 → 网络异常
                    recommendResult.exceptionOrNull()?.let { context?.toastError(it) }
                    showError()
                    return@launch
                }
                val recommend = recommendResp.unwrap(context)
                if (recommend == null) {
                    showError()
                    return@launch
                }
                // 过期刷新（用户已重新下拉）的结果不再落到界面上
                if (gen != refreshGeneration) return@launch

                adapter.submit(recommend.items)
                // 推荐已落定：置顶若先到就先缓存，此刻必须立刻合并（同一帧，避免出现
                // 「置顶先进旧列表 → 推荐整表替换把置顶冲掉」或「置顶晚到再合进新列表」两种错位）
                recommendSubmittedGen = gen
                mergePendingPinned(gen)
                hasMore = recommend.hasMore
                loadMoreListener.reset()
                loadMoreListener.hasMore = hasMore
                // 首屏只有几条、内容铺不满一屏时，滚动事件永远不会触发 → 主动补页
                autoFillIfNotFull(gen)

                if (recommend.items.isEmpty()) {
                    binding.tvEmpty.text = getString(R.string.empty_no_content)
                    binding.tvEmpty.visible = true
                } else {
                    binding.tvEmpty.visible = false
                }
            } catch (e: Exception) {
                context?.toastError(e)
                showError()
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }

        // banner / 置顶：与推荐并行，但首屏不等它们；到货后再补，失败静默
        viewLifecycleOwner.lifecycleScope.launch {
            val pinnedDeferred = async { runCatching { ApiClient.pinnedFirstScreen(6) }.getOrNull() }
            val adsDeferred = async { runCatching { ApiClient.adsFirstScreen() }.getOrNull() }
            val pinned = pinnedDeferred.await()?.unwrap()?.items.orEmpty()
            val ads = adsDeferred.await()?.unwrap()?.items.orEmpty()
            if (gen != refreshGeneration) return@launch
            updateBanner(ads, pinned)
            if (pinned.isNotEmpty()) {
                if (recommendSubmittedGen == gen) {
                    // 推荐已落定：直接合进当前列表
                    applyPinned(pinned, gen)
                } else {
                    // 置顶先到：先缓存，等本代推荐 submit 后再合并，绝不改旧列表
                    pendingPinned = pinned
                }
            }
        }
    }

    /**
     * 首屏不足一屏时主动补页：
     * 1. 等本次 submit 的布局落定（doOnLayout，不轮询）后再判断；
     * 2. 判据是「最后一项已经可见」——还有没露出来的条目就交给滚动加载（LoadMoreListener）；
     * 3. 最多连补 [AUTO_FILL_MAX_PAGES] 页，防止服务端异常时打成请求风暴；
     * 4. 走与 loadMore 相同的 [page] / [loading] / [hasMore] 状态，并校验刷新代次，
     *    旧代次的补页结果直接丢弃；失败静默（已上屏列表保持不动，不转错误态）。
     */
    private fun autoFillIfNotFull(gen: Int, filled: Int = 0) {
        if (filled >= AUTO_FILL_MAX_PAGES) return
        val rv = binding.recyclerView
        rv.post {
            if (gen != refreshGeneration) return@post
            rv.doOnLayout { fillOnePageIfNeeded(gen, filled) }
        }
    }

    private fun fillOnePageIfNeeded(gen: Int, filled: Int) {
        val lm = gridLayoutManager
        val lastVisible = lm.findLastVisibleItemPosition()
        val count = adapter.itemCount
        // 可观测性：区分「跑了但跳过」与「根本没跑」（没跑时不会有 check 行）
        Log.d(TAG, "home_autofill check gen=$gen filled=$filled lastVisible=$lastVisible count=$count hasMore=$hasMore loading=$loading")
        if (gen != refreshGeneration) return logFillSkip(gen, "staleGen", lastVisible, count)
        if (!hasMore) return logFillSkip(gen, "noMore", lastVisible, count)
        if (loading) return logFillSkip(gen, "loading", lastVisible, count)
        if (adapter.getItems().isEmpty()) return logFillSkip(gen, "empty", lastVisible, count)
        // 列表尾项已可见 ⇒ 内容铺不满（或刚好到屏幕底），没有可滚动空间
        if (lastVisible < count - 1) return logFillSkip(gen, "notAtEnd", lastVisible, count)
        Log.i(TAG, "home_autofill start gen=$gen next=${page + 1} lastVisible=$lastVisible count=$count")
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            var got = 0
            try {
                // 用 unwrap() 不带 context：失败时静默，不弹 toast
                val d = ApiClient.apiService.getRecommend(page + 1).unwrap()
                if (gen != refreshGeneration) return@launch
                if (d != null && d.items.isNotEmpty()) {
                    page += 1
                    adapter.append(d.items)
                    hasMore = d.hasMore
                    got = d.items.size
                } else {
                    hasMore = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "home_autofill fail gen=$gen next=${page + 1} ${e.javaClass.simpleName}: ${e.message}")
                return@launch
            } finally {
                loading = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
            Log.i(TAG, "home_autofill appended gen=$gen page=$page got=$got count=${adapter.itemCount} hasMore=$hasMore")
            autoFillIfNotFull(gen, filled + 1)
        }
    }

    /** 补页跳过原因（debug 级：每次刷新最多一两行，长期保留不占 info 日志） */
    private fun logFillSkip(gen: Int, reason: String, lastVisible: Int, count: Int) {
        Log.d(TAG, "home_autofill skip reason=$reason gen=$gen lastVisible=$lastVisible count=$count hasMore=$hasMore")
    }

    /** 推荐 submit 后立刻检查：置顶若已提前到达就合并，否则留给 aux 协程直接合并 */
    private fun mergePendingPinned(gen: Int) {
        val pinned = pendingPinned ?: return
        pendingPinned = null
        applyPinned(pinned, gen)
    }

    /**
     * 置顶卡后补（列表已由推荐渲染落定后才调用）。
     * - 用户已滚入列表：按「原首个可见项」回滚到新索引，避免内容被顶走；
     * - 仍停在顶部（含 banner 头部）：回到 position 0，按最终顺序「banner → 置顶 → 推荐」呈现，
     *   推荐卡一次性整体下移，这是置顶插入的固有语义，不产生错位的滚动状态。
     * - 合并前先剔除列表里已有的置顶项，保证同一条置顶不会出现两次。
     */
    private fun applyPinned(pinned: List<Video>, gen: Int) {
        if (gen != refreshGeneration || pinned.isEmpty() || pinnedMerged) return
        val current = adapter.getItems().filterNot { it.pinned }
        // 推荐失败/为空时列表不该只剩置顶，交给 tvEmpty 重试或下拉刷新
        if (current.isEmpty()) return
        pinnedMerged = true

        val merged = ArrayList<Video>(pinned.size + current.size)
        pinned.forEach { merged.add(it.copy(title = "📌 ${it.title}", pinned = true)) }
        merged.addAll(current)

        val lm = gridLayoutManager
        val firstPos = lm.findFirstVisibleItemPosition()
        val offset = if (firstPos >= 0) {
            val v = lm.findViewByPosition(firstPos)
            if (v != null) v.top - binding.recyclerView.paddingTop else 0
        } else {
            0
        }
        adapter.submit(merged)
        if (firstPos <= 0) {
            lm.scrollToPosition(0)
        } else {
            lm.scrollToPositionWithOffset(firstPos + pinned.size, offset)
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
                context?.toastError(e)
            } finally {
                loading = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    /**
     * 首屏失败态。Banner 是 adapter 的常驻 header，itemCount 恒 ≥1，
     * 所以必须判真实数据而不是 itemCount，否则错误态永远不显示。
     */
    private fun showError() {
        if (adapter.getItems().isEmpty()) {
            binding.tvEmpty.text = getString(R.string.err_load_failed_retry)
            binding.tvEmpty.visible = true
        }
    }

    companion object {
        private const val TAG = "LingXiHome"
        /** 首屏铺不满一屏时最多连补的页数（防止服务端异常时请求风暴） */
        private const val AUTO_FILL_MAX_PAGES = 2
        fun newInstance() = HomeFragment()
    }
}
