package com.lingxi.classic.ui.main

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Dynamic
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.FragmentDynamicsBinding
import com.lingxi.classic.databinding.ItemDynamicBinding
import com.lingxi.classic.ui.auth.LoginActivity
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.gridSpanCount
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/**
 * 动态页：未登录显示登录引导；已登录分「投稿视频」「动态」两个 Tab，支持发动态与删除自己的动态。
 */
class DynamicsFragment : Fragment() {

    private var _binding: FragmentDynamicsBinding? = null
    private val binding get() = _binding!!

    private var tab = 0 // 0 投稿视频，1 动态
    private var page = 1
    private var hasMore = true
    private var loading = false
    private var contentLoaded = false

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var dynamicAdapter: DynamicAdapter
    private lateinit var loadMoreListener: LoadMoreListener
    private lateinit var gridLm: GridLayoutManager
    private lateinit var linearLm: LinearLayoutManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDynamicsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        videoAdapter = VideoAdapter(onClick = { Navigator.openVideo(requireContext(), it) })
        dynamicAdapter = DynamicAdapter()
        gridLm = GridLayoutManager(requireContext(), requireContext().gridSpanCount())
        linearLm = LinearLayoutManager(requireContext())

        loadMoreListener = LoadMoreListener { loadNext() }
        binding.rvList.addOnScrollListener(loadMoreListener)

        binding.swipeRefresh.setOnRefreshListener { reload() }
        binding.tvEmpty.setOnClickListener { reload() }
        binding.tvTabVideos.setOnClickListener { selectTab(0) }
        binding.tvTabDynamics.setOnClickListener { selectTab(1) }
        binding.btnPost.setOnClickListener { showPostDialog() }
        binding.btnLogin.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }

        applyLoginState()
    }

    override fun onResume() {
        super.onResume()
        // 从登录页返回后刷新登录态
        applyLoginState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** MainActivity 声明了 configChanges，旋转不重建，这里手动刷新列数 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::gridLm.isInitialized) {
            gridLm.spanCount = requireContext().gridSpanCount()
        }
    }

    private fun applyLoginState() {
        val loggedIn = AuthManager.isLoggedIn
        binding.llLogin.visible = !loggedIn
        binding.llContent.visible = loggedIn
        if (loggedIn && !contentLoaded) {
            contentLoaded = true
            selectTab(0)
        }
    }

    private fun selectTab(index: Int) {
        tab = index
        val sel = ContextCompat.getColor(requireContext(), R.color.lx_green)
        val unsel = ContextCompat.getColor(requireContext(), R.color.lx_text_sub)
        binding.tvTabVideos.setTextColor(if (index == 0) sel else unsel)
        binding.tvTabDynamics.setTextColor(if (index == 1) sel else unsel)
        binding.tvTabVideos.setTypeface(null, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
        binding.tvTabDynamics.setTypeface(null, if (index == 1) Typeface.BOLD else Typeface.NORMAL)
        binding.rvList.layoutManager = if (index == 0) gridLm else linearLm
        binding.rvList.adapter = if (index == 0) videoAdapter else dynamicAdapter
        reload()
    }

    // ==================== 加载 ====================

    private fun reload() {
        if (loading) return
        page = 1
        hasMore = true
        loadInternal()
    }

    private fun loadNext() {
        if (loading || !hasMore) return
        page += 1
        loadInternal()
    }

    private fun loadInternal() {
        loading = true
        if (page == 1) binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (tab == 0) {
                    val d = ApiClient.apiService.getFollowingVideos(page).unwrap(context)
                    if (d != null) {
                        if (page == 1) videoAdapter.submit(d.items) else videoAdapter.append(d.items)
                        hasMore = d.hasMore
                        updateEmpty()
                    } else pageFailed()
                } else {
                    val d = ApiClient.apiService.getDynamics(page).unwrap(context)
                    if (d != null) {
                        if (page == 1) dynamicAdapter.submit(d.items) else dynamicAdapter.append(d.items)
                        hasMore = d.hasMore
                        updateEmpty()
                    } else pageFailed()
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
                pageFailed()
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun pageFailed() {
        if (page > 1) page -= 1 else showError()
    }

    private fun updateEmpty() {
        val count = if (tab == 0) videoAdapter.itemCount else dynamicAdapter.itemCount
        if (count == 0 && page == 1) {
            binding.tvEmpty.text = "暂无内容"
            binding.tvEmpty.visible = true
        } else {
            binding.tvEmpty.visible = false
        }
    }

    private fun showError() {
        val count = if (tab == 0) videoAdapter.itemCount else dynamicAdapter.itemCount
        if (count == 0) {
            binding.tvEmpty.text = "加载失败，点击重试"
            binding.tvEmpty.visible = true
        }
    }

    // ==================== 发动态 / 删动态 ====================

    private fun showPostDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val et = EditText(ctx).apply {
            hint = "分享此刻的想法…"
            textSize = 14f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = (16 * density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(ctx)
            .setTitle("发动态")
            .setView(container)
            .setPositiveButton("发布") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isEmpty()) ctx.toast("说点什么吧") else postDynamic(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun postDynamic(text: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.postDynamic(mapOf<String, Any>("content" to text)).unwrap(context)
                if (r != null) {
                    context?.toast("已发布")
                    if (tab == 1) reload() else selectTab(1)
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
            }
        }
    }

    private fun onDynamicLongClick(d: Dynamic) {
        // 仅允许删除自己的动态
        if (d.user?.id != AuthManager.user?.id) return
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("删除动态")
            .setMessage("确定删除这条动态吗？")
            .setPositiveButton("删除") { _, _ -> deleteDynamic(d) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteDynamic(d: Dynamic) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.deleteDynamic(d.id).unwrap(context)
                if (r != null) {
                    context?.toast("已删除")
                    val idx = dynamicAdapter.indexOf(d.id)
                    if (idx >= 0) dynamicAdapter.removeAt(idx)
                    updateEmpty()
                }
            } catch (e: Exception) {
                context?.toast("网络异常")
            }
        }
    }

    // ==================== 动态列表 Adapter ====================

    private inner class DynamicAdapter : RecyclerView.Adapter<DynamicAdapter.VH>() {

        private val items = mutableListOf<Dynamic>()

        fun submit(list: List<Dynamic>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Dynamic>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun removeAt(position: Int) {
            if (position in items.indices) {
                items.removeAt(position)
                notifyItemRemoved(position)
            }
        }

        fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

        inner class VH(val b: ItemDynamicBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemDynamicBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            val b = holder.b
            val ctx = holder.itemView.context

            ImageLoader.load(b.ivAvatar, d.user?.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
            b.tvName.text = d.user.displayName()
            b.tvTime.text = FormatUtils.formatRelative(d.createdAt)
            b.tvContent.visible = d.content.isNotBlank()
            b.tvContent.text = d.content

            val v = d.video
            val showVideo = d.kind == "video" && v != null
            b.videoCard.root.visible = showVideo
            if (showVideo && v != null) {
                val vb = b.videoCard
                vb.tvTitle.text = v.title
                ImageLoader.load(vb.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))
                vb.tvDuration.visible = !v.isArticle
                vb.tvDuration.text = if (v.isAudio) "音频" else FormatUtils.formatDuration(v.duration)
                when {
                    v.isArticle -> {
                        vb.tvKindBadge.visible = true
                        vb.tvKindBadge.text = "专栏"
                    }
                    v.isAudio -> {
                        vb.tvKindBadge.visible = true
                        vb.tvKindBadge.text = "音频"
                    }
                    else -> vb.tvKindBadge.visible = false
                }
                vb.tvMeta.text = buildString {
                    append(v.up.displayName())
                    append(" · ")
                    append(FormatUtils.formatCount(v.viewCount))
                    append(" 播放")
                }
                vb.root.setOnClickListener { Navigator.openVideo(ctx, v) }
            }

            holder.itemView.setOnLongClickListener {
                onDynamicLongClick(d)
                true
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivAvatar)
            ImageLoader.clear(holder.b.videoCard.ivCover)
        }
    }

    companion object {
        fun newInstance() = DynamicsFragment()
    }
}
