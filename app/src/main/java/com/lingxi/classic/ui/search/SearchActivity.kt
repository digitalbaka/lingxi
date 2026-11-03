package com.lingxi.classic.ui.search

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.UserInfo
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivitySearchBinding
import com.lingxi.classic.databinding.ItemUserBinding
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.gridSpanCount
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/**
 * 搜索页：历史搜索（本地最多 10 条）+ 热门标签，结果分「视频」「UP主」两个 Tab。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var userAdapter: UserAdapter
    private lateinit var loadMoreListener: LoadMoreListener
    private lateinit var gridLm: GridLayoutManager
    private lateinit var linearLm: LinearLayoutManager

    private var keyword = ""
    private var tab = 0 // 0 视频，1 UP主
    private var page = 1
    private var hasMore = true
    private var loading = false
    private var resultShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoAdapter = VideoAdapter(onClick = { Navigator.openVideo(this, it) })
        userAdapter = UserAdapter()
        gridLm = GridLayoutManager(this, gridSpanCount())
        linearLm = LinearLayoutManager(this)
        binding.rvResults.layoutManager = gridLm
        binding.rvResults.adapter = videoAdapter

        loadMoreListener = LoadMoreListener { loadNext() }
        binding.rvResults.addOnScrollListener(loadMoreListener)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { doSearch(binding.etKeyword.text.toString()) }
        binding.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding.etKeyword.text.toString())
                true
            } else false
        }
        binding.tvTabVideo.setOnClickListener { selectTab(0) }
        binding.tvTabUp.setOnClickListener { selectTab(1) }

        renderHistory()
        loadHotTags()
    }

    // ==================== 历史 & 热标签 ====================

    private fun loadHistory(): List<String> =
        (prefs.getString(KEY_WORDS, "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .take(MAX_HISTORY)

    private fun saveHistory(word: String) {
        val list = loadHistory().toMutableList()
        list.remove(word)
        list.add(0, word)
        prefs.edit().putString(KEY_WORDS, list.take(MAX_HISTORY).joinToString(",")).apply()
    }

    private fun renderHistory() {
        val list = loadHistory()
        binding.tvLabelHistory.visible = list.isNotEmpty()
        binding.flHistory.removeAllViews()
        list.forEach { word ->
            binding.flHistory.addView(makeChip(word) { doSearch(word) })
        }
    }

    private fun loadHotTags() {
        lifecycleScope.launch {
            try {
                val d = ApiClient.apiService.getHotTags().unwrap() ?: return@launch
                binding.flTags.removeAllViews()
                d.items.forEach { m ->
                    val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                    binding.flTags.addView(makeChip(name) { doSearch(name) })
                }
            } catch (e: Exception) {
                // 热标签失败静默
            }
        }
    }

    private fun makeChip(text: String, onClick: () -> Unit): TextView {
        val margin = (8 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.lx_green_dark))
            setBackgroundResource(R.drawable.bg_tag)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = margin
                bottomMargin = margin
            }
            setOnClickListener { onClick() }
        }
    }

    // ==================== 搜索 ====================

    private fun doSearch(q: String) {
        val k = q.trim()
        if (k.isEmpty()) {
            toast("请输入关键词")
            return
        }
        keyword = k
        if (binding.etKeyword.text.toString() != k) binding.etKeyword.setText(k)
        binding.etKeyword.setSelection(k.length)
        hideKeyboard()
        saveHistory(k)
        renderHistory()

        resultShown = true
        binding.svDiscover.visible = false
        binding.llResult.visible = true
        page = 1
        hasMore = true
        loadInternal()
    }

    private fun selectTab(index: Int) {
        tab = index
        val sel = ContextCompat.getColor(this, R.color.lx_green)
        val unsel = ContextCompat.getColor(this, R.color.lx_text_sub)
        binding.tvTabVideo.setTextColor(if (index == 0) sel else unsel)
        binding.tvTabUp.setTextColor(if (index == 1) sel else unsel)
        binding.tvTabVideo.setTypeface(null, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
        binding.tvTabUp.setTypeface(null, if (index == 1) Typeface.BOLD else Typeface.NORMAL)
        binding.rvResults.layoutManager = if (index == 0) gridLm else linearLm
        binding.rvResults.adapter = if (index == 0) videoAdapter else userAdapter
        binding.tvEmpty.visible = false
        if (resultShown) {
            page = 1
            hasMore = true
            loadInternal()
        }
    }

    private fun loadNext() {
        if (loading || !hasMore || !resultShown) return
        page += 1
        loadInternal()
    }

    private fun loadInternal() {
        loading = true
        lifecycleScope.launch {
            try {
                val type = if (tab == 0) "video" else "user"
                val d = ApiClient.apiService.search(keyword, type, page).unwrap(this@SearchActivity)
                if (d != null) {
                    hasMore = d.hasMore
                    if (tab == 0) {
                        if (page == 1) videoAdapter.submit(d.items) else videoAdapter.append(d.items)
                    } else {
                        if (page == 1) userAdapter.submit(d.users) else userAdapter.append(d.users)
                    }
                    updateEmpty()
                } else if (page > 1) {
                    page -= 1
                }
            } catch (e: Exception) {
                toast("网络异常")
                if (page > 1) page -= 1
            } finally {
                loading = false
                loadMoreListener.loading = false
                loadMoreListener.hasMore = hasMore
            }
        }
    }

    private fun updateEmpty() {
        val count = if (tab == 0) videoAdapter.itemCount else userAdapter.itemCount
        binding.tvEmpty.visible = count == 0
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etKeyword.windowToken, 0)
    }

    // ==================== 关注 ====================

    private fun toggleFollow(u: UserInfo) {
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.followUser(u.id).unwrap(this@SearchActivity)
                if (r != null) {
                    userAdapter.setFollowing(u.id, r.following)
                    toast(if (r.following) "已关注" else "已取消关注")
                }
            } catch (e: Exception) {
                toast("网络异常")
            }
        }
    }

    // ==================== UP主列表 Adapter ====================

    private inner class UserAdapter : RecyclerView.Adapter<UserAdapter.VH>() {

        private val items = mutableListOf<UserInfo>()
        private val followingIds = mutableSetOf<Long>()

        fun submit(list: List<UserInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<UserInfo>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun setFollowing(uid: Long, following: Boolean) {
            if (following) followingIds.add(uid) else followingIds.remove(uid)
            val idx = items.indexOfFirst { it.id == uid }
            if (idx >= 0) notifyItemChanged(idx)
        }

        inner class VH(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            val b = holder.b
            ImageLoader.load(b.ivAvatar, u.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
            b.tvNickname.text = u.displayName()
            b.tvSub.text = "Lv${u.level} · ${FormatUtils.formatCount(u.followerCount)} 粉丝"
            b.btnFollow.text = if (u.id in followingIds) "已关注" else "关注"
            b.btnFollow.setOnClickListener { toggleFollow(u) }
            holder.itemView.setOnClickListener { Navigator.openSpace(holder.itemView.context, u.id) }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivAvatar)
        }
    }

    companion object {
        private const val PREFS_NAME = "search_history"
        private const val KEY_WORDS = "words"
        private const val MAX_HISTORY = 10
    }
}
