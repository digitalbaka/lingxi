package com.lingxi.classic.ui.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.FavoriteFolder
import com.lingxi.classic.data.model.HistoryItem
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivityVideoListBinding
import com.lingxi.classic.databinding.ItemVideoBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/**
 * 通用列表页：type ∈ history / favorites / my_videos / folder
 * extra: "type"(String) "folder_id"(Long) "title"(String)
 */
class VideoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoListBinding
    private lateinit var loadMoreListener: LoadMoreListener

    private var type = "history"
    private var folderId = 0L
    private var launchedWithFolder = false
    private var page = 1
    private var hasMore = true
    private var loading = false

    private lateinit var videoAdapter: VideoAdapter      // folder 内容
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var myVideoAdapter: MyVideoAdapter
    private lateinit var folderAdapter: FolderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        type = intent.getStringExtra("type") ?: "history"
        folderId = intent.getLongExtra("folder_id", 0L)
        launchedWithFolder = type == "folder"
        binding.tvTitle.text = intent.getStringExtra("title").takeUnless { it.isNullOrBlank() } ?: defaultTitle()

        binding.btnBack.setOnClickListener { handleBack() }
        if (type == "history") {
            binding.btnAction.visible = true
            binding.btnAction.setOnClickListener { confirmClearHistory() }
        }

        setupList()
        binding.swipeRefresh.setOnRefreshListener { reload() }
        binding.tvEmpty.setOnClickListener { reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        reload()
    }

    private fun defaultTitle(): String = when (type) {
        "history" -> "观看历史"
        "favorites" -> "我的收藏"
        "my_videos" -> "我的稿件"
        else -> "收藏夹"
    }

    /** 从收藏夹进入文件夹后，返回键先回到收藏夹列表 */
    private fun handleBack() {
        if (type == "folder" && !launchedWithFolder) {
            type = "favorites"
            binding.tvTitle.text = defaultTitle()
            page = 1
            hasMore = false
            binding.rvList.layoutManager = LinearLayoutManager(this)
            binding.rvList.adapter = folderAdapter
            loadMoreListener.reset()
            loadMoreListener.hasMore = false
            loadInternal()
        } else {
            finish()
        }
    }

    private fun setupList() {
        loadMoreListener = LoadMoreListener { loadNext() }
        binding.rvList.addOnScrollListener(loadMoreListener)
        when (type) {
            "history" -> {
                historyAdapter = HistoryAdapter()
                binding.rvList.layoutManager = LinearLayoutManager(this)
                binding.rvList.adapter = historyAdapter
            }
            "favorites" -> {
                folderAdapter = FolderAdapter()
                binding.rvList.layoutManager = LinearLayoutManager(this)
                binding.rvList.adapter = folderAdapter
            }
            "my_videos" -> {
                myVideoAdapter = MyVideoAdapter()
                binding.rvList.layoutManager = GridLayoutManager(this, 2)
                binding.rvList.adapter = myVideoAdapter
            }
            else -> { // folder
                videoAdapter = VideoAdapter(
                    onClick = { Navigator.openVideo(this, it) },
                    onLongClick = { confirmUnfavorite(it) }
                )
                binding.rvList.layoutManager = GridLayoutManager(this, 2)
                binding.rvList.adapter = videoAdapter
            }
        }
    }

    // ==================== 加载 ====================

    private fun reload() {
        if (loading) return
        page = 1
        hasMore = type != "favorites"
        loadInternal()
    }

    private fun loadNext() {
        if (loading || !hasMore || type == "favorites") return
        page += 1
        loadInternal()
    }

    private fun loadInternal() {
        loading = true
        if (page == 1) binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                when (type) {
                    "history" -> {
                        val d = ApiClient.apiService.getHistory(page).unwrap(this@VideoListActivity)
                        if (d != null) {
                            // video 可能已被删除，为 null 的条目直接跳过
                            val list = d.items.filter { it.video != null }
                            if (page == 1) historyAdapter.submit(list) else historyAdapter.append(list)
                            hasMore = d.hasMore
                        } else pageFailed()
                    }
                    "favorites" -> {
                        val d = ApiClient.apiService.getMyFolders().unwrap(this@VideoListActivity)
                        if (d != null) {
                            folderAdapter.submit(d.items)
                            hasMore = false
                        }
                    }
                    "my_videos" -> {
                        val d = ApiClient.apiService.getMyVideos(page = page).unwrap(this@VideoListActivity)
                        if (d != null) {
                            if (page == 1) myVideoAdapter.submit(d.items) else myVideoAdapter.append(d.items)
                            hasMore = d.hasMore
                        } else pageFailed()
                    }
                    else -> {
                        val d = ApiClient.apiService.getFolderItems(folderId, page).unwrap(this@VideoListActivity)
                        if (d != null) {
                            if (page == 1) videoAdapter.submit(d.items) else videoAdapter.append(d.items)
                            hasMore = d.hasMore
                        } else pageFailed()
                    }
                }
                updateEmpty()
            } catch (e: Exception) {
                toast("网络异常")
                pageFailed()
                updateEmpty()
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun pageFailed() {
        if (page > 1) page -= 1
    }

    private fun updateEmpty() {
        val count = when (type) {
            "history" -> if (::historyAdapter.isInitialized) historyAdapter.itemCount else 0
            "favorites" -> if (::folderAdapter.isInitialized) folderAdapter.itemCount else 0
            "my_videos" -> if (::myVideoAdapter.isInitialized) myVideoAdapter.itemCount else 0
            else -> if (::videoAdapter.isInitialized) videoAdapter.itemCount else 0
        }
        binding.tvEmpty.visible = count == 0
        if (count == 0) binding.tvEmpty.text = "暂无内容"
    }

    // ==================== 收藏夹 ====================

    private fun openFolder(f: FavoriteFolder) {
        type = "folder"
        folderId = f.id
        binding.tvTitle.text = f.name
        page = 1
        hasMore = true
        if (!::videoAdapter.isInitialized) {
            videoAdapter = VideoAdapter(
                onClick = { Navigator.openVideo(this, it) },
                onLongClick = { confirmUnfavorite(it) }
            )
        }
        binding.rvList.layoutManager = GridLayoutManager(this, 2)
        binding.rvList.adapter = videoAdapter
        loadMoreListener.reset()
        loadInternal()
    }

    private fun confirmUnfavorite(v: Video) {
        AlertDialog.Builder(this)
            .setTitle("取消收藏")
            .setMessage("将《${v.title}》移出收藏夹？")
            .setPositiveButton("移除") { _, _ -> unfavorite(v) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun unfavorite(v: Video) {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.unfavoriteVideo(v.id).unwrap(this@VideoListActivity)
                if (r != null) {
                    toast("已移除")
                    val idx = videoAdapter.getItems().indexOfFirst { it.id == v.id }
                    if (idx >= 0) videoAdapter.removeAt(idx)
                    updateEmpty()
                }
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    // ==================== 观看历史 ====================

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空观看历史")
            .setMessage("确定清空全部观看历史吗？")
            .setPositiveButton("清空") { _, _ -> clearHistory() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearHistory() {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.clearHistory().unwrap(this@VideoListActivity)
                if (r != null) {
                    toast("已清空")
                    reload()
                }
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    private fun confirmDeleteHistory(v: Video) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("删除《${v.title}》的观看记录？")
            .setPositiveButton("删除") { _, _ -> deleteHistory(v) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteHistory(v: Video) {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.deleteHistoryItem(v.id).unwrap(this@VideoListActivity)
                if (r != null) {
                    toast("已删除")
                    historyAdapter.removeByVideoId(v.id)
                    updateEmpty()
                }
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    // ==================== 我的稿件 ====================

    private fun showVideoOps(v: Video) {
        val ops = mutableListOf("删除")
        if (v.status == 0) ops.add("催审")
        AlertDialog.Builder(this)
            .setTitle(v.title)
            .setItems(ops.toTypedArray()) { _, which ->
                when (ops[which]) {
                    "删除" -> confirmDeleteVideo(v)
                    "催审" -> nudgeVideo(v)
                }
            }
            .show()
    }

    private fun confirmDeleteVideo(v: Video) {
        AlertDialog.Builder(this)
            .setTitle("删除稿件")
            .setMessage("确定删除《${v.title}》吗？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ -> deleteVideo(v) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteVideo(v: Video) {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.deleteVideo(v.id).unwrap(this@VideoListActivity)
                if (r != null) {
                    toast("已删除")
                    myVideoAdapter.removeById(v.id)
                    updateEmpty()
                }
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    private fun nudgeVideo(v: Video) {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.nudgeReview(v.id).unwrap(this@VideoListActivity)
                if (r != null) toast(r.message.ifBlank { "已催审" })
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    // ==================== Adapters ====================

    /** 观看历史：视频卡片 + "看到 xx%" */
    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = mutableListOf<HistoryItem>()

        fun submit(list: List<HistoryItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<HistoryItem>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun removeByVideoId(videoId: Long) {
            val idx = items.indexOfFirst { it.video?.id == videoId }
            if (idx >= 0) {
                items.removeAt(idx)
                notifyItemRemoved(idx)
            }
        }

        inner class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val v = item.video ?: return
            val b = holder.b
            b.tvTitle.text = v.title
            ImageLoader.load(b.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))
            b.tvDuration.visible = !v.isArticle
            b.tvDuration.text = if (v.isAudio) "音频" else FormatUtils.formatDuration(v.duration)
            when {
                v.isArticle -> {
                    b.tvKindBadge.visible = true
                    b.tvKindBadge.text = "专栏"
                }
                v.isAudio -> {
                    b.tvKindBadge.visible = true
                    b.tvKindBadge.text = "音频"
                }
                else -> b.tvKindBadge.visible = false
            }
            b.tvMeta.text = buildString {
                append(v.up.displayName())
                append(" · ")
                append(FormatUtils.formatCount(v.viewCount))
                append(" 播放 · 看到 ")
                append(item.percent)
                append("%")
            }
            holder.itemView.setOnClickListener { Navigator.openVideo(holder.itemView.context, v) }
            holder.itemView.setOnLongClickListener {
                confirmDeleteHistory(v)
                true
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivCover)
        }
    }

    /** 我的稿件：视频卡片 + 审核状态角标（0 待审核 / 1 已发布 / 2 未通过红色） */
    private inner class MyVideoAdapter : RecyclerView.Adapter<MyVideoAdapter.VH>() {

        private val items = mutableListOf<Video>()

        fun submit(list: List<Video>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Video>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun removeById(id: Long) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items.removeAt(idx)
                notifyItemRemoved(idx)
            }
        }

        inner class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = items[position]
            val b = holder.b
            val ctx = holder.itemView.context
            b.tvTitle.text = v.title
            ImageLoader.load(b.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))
            b.tvDuration.visible = !v.isArticle
            b.tvDuration.text = if (v.isAudio) "音频" else FormatUtils.formatDuration(v.duration)

            val (statusText, colorRes) = when (v.status) {
                0 -> "待审核" to R.color.lx_orange
                2 -> "未通过" to R.color.lx_red
                else -> "已发布" to R.color.lx_green_dark
            }
            b.tvKindBadge.visible = true
            b.tvKindBadge.text = statusText
            b.tvKindBadge.setTextColor(ContextCompat.getColor(ctx, colorRes))

            b.tvMeta.text = buildString {
                append(FormatUtils.formatCount(v.viewCount))
                append(" 播放")
                if (v.danmakuCount > 0) append(" · ${FormatUtils.formatCount(v.danmakuCount)} 弹幕")
            }
            holder.itemView.setOnClickListener { Navigator.openVideo(ctx, v) }
            holder.itemView.setOnLongClickListener {
                showVideoOps(v)
                true
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivCover)
        }
    }

    /** 收藏夹列表：名称 + 数量 */
    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.VH>() {

        private val items = mutableListOf<FavoriteFolder>()

        fun submit(list: List<FavoriteFolder>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            return VH(tv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.tv.text = "${f.name}（${f.count}）"
            holder.itemView.setOnClickListener { openFolder(f) }
        }
    }
}
