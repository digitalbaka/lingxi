package com.lingxi.classic.ui.space

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Dynamic
import com.lingxi.classic.data.model.UserInfo
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivitySpaceBinding
import com.lingxi.classic.databinding.ItemDynamicSimpleBinding
import com.lingxi.classic.databinding.ItemUserSimpleBinding
import com.lingxi.classic.ui.auth.LoginActivity
import com.lingxi.classic.ui.common.LoadMoreListener
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.common.VideoAdapter
import com.lingxi.classic.ui.message.ChatActivity
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.gridSpanCount
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 用户空间：资料卡 + 视频/文章/动态 Tab */
class UserSpaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpaceBinding

    private var uid: Long = 0
    private var isFollowing = false
    private var currentUserName = ""

    private var currentTab = TAB_VIDEO
    private var page = 1
    private var hasMore = true

    private val videoAdapter = VideoAdapter(onClick = { Navigator.openVideo(this, it) })
    private val articleAdapter = VideoAdapter(onClick = { Navigator.openVideo(this, it) })
    private val dynamicAdapter = DynamicAdapter(onVideoClick = { video ->
        Navigator.openVideo(this, video.id, video.kind)
    })
    private lateinit var loadMoreListener: LoadMoreListener

    companion object {
        private const val TAG = "LingXiSpace"
        private const val TAB_VIDEO = 0
        private const val TAB_ARTICLE = 1
        private const val TAB_DYNAMIC = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uid = intent.getLongExtra(Navigator.EXTRA_UID, 0)
        if (uid <= 0) { toast("参数错误"); finish(); return }

        val grid = GridLayoutManager(this, gridSpanCount())
        binding.recyclerView.layoutManager = grid
        binding.recyclerView.adapter = videoAdapter
        loadMoreListener = LoadMoreListener { loadTabContent(append = true) }
        binding.recyclerView.addOnScrollListener(loadMoreListener)

        binding.swipeRefresh.setOnRefreshListener { loadTabContent(append = false) }

        binding.tabVideo.setOnClickListener { switchTab(TAB_VIDEO) }
        binding.tabArticle.setOnClickListener { switchTab(TAB_ARTICLE) }
        binding.tabDynamic.setOnClickListener { switchTab(TAB_DYNAMIC) }

        binding.btnFollow.setOnClickListener { toggleFollow() }
        binding.btnMessage.setOnClickListener { openChat() }
        binding.btnFollowing.setOnClickListener { showUserListDialog(following = true) }
        binding.btnFollowers.setOnClickListener { showUserListDialog(following = false) }

        loadSpaceInfo()
        loadTabContent(append = false)
    }

    // ==================== 空间资料 ====================

    /** 占位是否还停在「加载中」：finally 里据此兜底收尾 */
    private var spaceInfoLoading = false

    private fun loadSpaceInfo() {
        showSpaceLoading()
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                val resp = ApiClient.apiService.getUserSpace(uid)
                Log.i(TAG, "space_info ok elapsed=${SystemClock.elapsedRealtime() - start}ms")
                val info = resp.data
                if (!resp.isSuccess || info == null) {
                    toast(resp.message.ifBlank { "加载失败" })
                    showSpaceError()
                    return@launch
                }
                binding.tvJoined.setOnClickListener(null)
                binding.ivAvatar.alpha = 1f
                spaceInfoLoading = false
                val user = info.user
                currentUserName = user?.nickname?.ifBlank { user.username } ?: "用户 $uid"
                binding.tvNickname.text = currentUserName
                binding.tvLevel.text = "LV ${info.level?.level ?: user?.level ?: 0}"
                binding.tvJoined.text = if (info.joinedAt.isNotEmpty()) "加入时间：${FormatUtils.formatDateString(info.joinedAt)}" else ""
                ImageLoader.load(binding.ivAvatar, user?.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)

                binding.tvFollowingCount.text = FormatUtils.formatCount((user?.followingCount ?: 0).toLong())
                binding.tvFollowerCount.text = FormatUtils.formatCount((user?.followerCount ?: 0).toLong())
                binding.tvLikeCount.text = FormatUtils.formatCount(user?.likeReceived ?: 0)
                binding.tvVideoCount.text = info.videoCount.toString()

                binding.btnFollow.visible = !info.isSelf
                binding.btnMessage.visible = !info.isSelf
                isFollowing = info.isFollowing
                updateFollowBtn()
            } catch (e: CancellationException) {
                // 页面销毁/切页导致的取消不是加载失败：重新抛出，不弹「Job was cancelled」
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "space_info fail elapsed=${SystemClock.elapsedRealtime() - start}ms ${e.javaClass.simpleName}: ${e.message}")
                toast("加载失败：${e.message}")
                showSpaceError()
            } finally {
                // 取消/异常出口兜底：占位一定收尾，不残留「加载中…」
                if (spaceInfoLoading) showSpaceError()
            }
        }
    }

    /** 资料卡加载态：用现有控件填占位文案，避免首屏十几秒空白 */
    private fun showSpaceLoading() {
        spaceInfoLoading = true
        binding.tvNickname.text = "加载中…"
        binding.tvLevel.text = ""
        binding.tvJoined.text = "正在加载用户资料"
        binding.tvJoined.setOnClickListener(null)
        binding.tvFollowingCount.text = "—"
        binding.tvFollowerCount.text = "—"
        binding.tvLikeCount.text = "—"
        binding.tvVideoCount.text = "—"
        binding.btnFollow.visible = false
        binding.btnMessage.visible = false
        binding.ivAvatar.alpha = 0.35f
    }

    /** 加载失败态：点资料卡上的提示文字可重试（列表一起重拉） */
    private fun showSpaceError() {
        spaceInfoLoading = false
        binding.tvNickname.text = "加载失败"
        binding.tvJoined.text = "网络异常，点此重试"
        binding.tvJoined.setOnClickListener {
            loadSpaceInfo()
            loadTabContent(append = false)
        }
        binding.ivAvatar.alpha = 0.35f
    }

    private fun updateFollowBtn() {
        binding.btnFollow.text = if (isFollowing) "已关注" else "+ 关注"
    }

    /** 私信：直接进入与该用户的单聊页；未登录先去登录 */
    private fun openChat() {
        if (!AuthManager.isLoggedIn) {
            toast("请先登录")
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_UID, uid)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, currentUserName)
        )
    }

    private fun toggleFollow() {
        binding.btnFollow.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.followUser(uid)
                if (resp.isSuccess && resp.data != null) {
                    isFollowing = resp.data.following
                    updateFollowBtn()
                    toast(if (isFollowing) "已关注" else "已取消关注")
                } else {
                    toast(resp.message.ifBlank { "操作失败" })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
            } finally {
                binding.btnFollow.isEnabled = true
            }
        }
    }

    // ==================== Tab 切换与分页 ====================

    private fun switchTab(tab: Int) {
        if (tab == currentTab) return
        currentTab = tab
        binding.tabVideo.setTextColor(androidx.core.content.ContextCompat.getColor(this, 
            if (tab == TAB_VIDEO) com.lingxi.classic.R.color.lx_green_dark else com.lingxi.classic.R.color.lx_text_sub))
        binding.tabArticle.setTextColor(androidx.core.content.ContextCompat.getColor(this, 
            if (tab == TAB_ARTICLE) com.lingxi.classic.R.color.lx_green_dark else com.lingxi.classic.R.color.lx_text_sub))
        binding.tabDynamic.setTextColor(androidx.core.content.ContextCompat.getColor(this, 
            if (tab == TAB_DYNAMIC) com.lingxi.classic.R.color.lx_green_dark else com.lingxi.classic.R.color.lx_text_sub))
        binding.tabVideo.setTypeface(null, if (tab == TAB_VIDEO) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabArticle.setTypeface(null, if (tab == TAB_ARTICLE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabDynamic.setTypeface(null, if (tab == TAB_DYNAMIC) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        binding.recyclerView.layoutManager = if (tab == TAB_DYNAMIC)
            LinearLayoutManager(this) else GridLayoutManager(this, gridSpanCount())
        binding.recyclerView.adapter = when (tab) {
            TAB_ARTICLE -> articleAdapter
            TAB_DYNAMIC -> dynamicAdapter
            else -> videoAdapter
        }
        loadTabContent(append = false)
    }

    private fun loadTabContent(append: Boolean) {
        if (!append) {
            page = 1
            hasMore = true
            loadMoreListener.reset()
        }
        if (!hasMore) return
        loadMoreListener.loading = true
        if (!append) binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                when (currentTab) {
                    TAB_DYNAMIC -> loadDynamics(append)
                    else -> loadVideos(append)
                }
            } catch (e: CancellationException) {
                // 切页/退出的取消不是加载失败：重新抛出，不弹「Job was cancelled」
                throw e
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                binding.swipeRefresh.isRefreshing = false
                loadMoreListener.loading = false
            }
        }
    }

    private suspend fun loadVideos(append: Boolean) {
        // 文章走通用列表接口（/users/{id}/videos 只返回视频，不含文章）
        val name = if (currentTab == TAB_ARTICLE) "articles" else "user_videos"
        val start = SystemClock.elapsedRealtime()
        val resp = try {
            if (currentTab == TAB_ARTICLE)
                ApiClient.apiService.getVideos(page = page, kind = "article", uid = uid)
            else
                ApiClient.apiService.getUserVideos(uid, page = page)
        } catch (e: Exception) {
            Log.w(TAG, "space $name fail page=$page elapsed=${SystemClock.elapsedRealtime() - start}ms ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        Log.i(TAG, "space $name ok page=$page elapsed=${SystemClock.elapsedRealtime() - start}ms")
        val data = resp.data
        if (!resp.isSuccess || data == null) { toast(resp.message.ifBlank { "加载失败" }); return }
        val adapter = if (currentTab == TAB_ARTICLE) articleAdapter else videoAdapter
        if (append) adapter.append(data.items) else adapter.submit(data.items)
        hasMore = data.hasMore
        loadMoreListener.hasMore = hasMore
        if (hasMore) page++
    }

    private suspend fun loadDynamics(append: Boolean) {
        val start = SystemClock.elapsedRealtime()
        val resp = try {
            ApiClient.apiService.getUserDynamics(uid, page = page)
        } catch (e: Exception) {
            Log.w(TAG, "space dynamics fail page=$page elapsed=${SystemClock.elapsedRealtime() - start}ms ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        Log.i(TAG, "space dynamics ok page=$page elapsed=${SystemClock.elapsedRealtime() - start}ms")
        val data = resp.data
        if (!resp.isSuccess || data == null) { toast(resp.message.ifBlank { "加载失败" }); return }
        if (append) dynamicAdapter.append(data.items) else dynamicAdapter.submit(data.items)
        hasMore = data.hasMore
        loadMoreListener.hasMore = hasMore
        if (hasMore) page++
    }

    // ==================== 关注/粉丝列表弹窗 ====================

    private fun showUserListDialog(following: Boolean) {
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@UserSpaceActivity) }
        val adapter = UserListAdapter { user -> Navigator.openSpace(this, user.id) }
        rv.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (following) "关注" else "粉丝")
            .setView(rv)
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            (resources.displayMetrics.heightPixels * 0.6).toInt()
        )

        var dlgPage = 1
        var dlgHasMore = true
        lateinit var dlgLoadMore: LoadMoreListener
        dlgLoadMore = LoadMoreListener {
            if (!dlgHasMore) return@LoadMoreListener
            lifecycleScope.launch {
                try {
                    val resp = if (following) ApiClient.apiService.getFollowing(uid, dlgPage)
                        else ApiClient.apiService.getFollowers(uid, dlgPage)
                    val data = resp.data
                    if (resp.isSuccess && data != null) {
                        if (dlgPage == 1) adapter.submit(data.items) else adapter.append(data.items)
                        dlgHasMore = data.hasMore
                        if (dlgHasMore) dlgPage++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {} finally {
                    dlgLoadMore.loading = false
                }
            }
        }
        dlgLoadMore.loading = true
        rv.addOnScrollListener(dlgLoadMore)
        lifecycleScope.launch {
            try {
                val resp = if (following) ApiClient.apiService.getFollowing(uid, 1)
                    else ApiClient.apiService.getFollowers(uid, 1)
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    adapter.submit(data.items)
                    dlgHasMore = data.hasMore
                    if (dlgHasMore) dlgPage = 2
                } else {
                    toast(resp.message.ifBlank { "加载失败" })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                dlgLoadMore.loading = false
            }
        }
    }

    // ==================== 适配器 ====================

    private class UserListAdapter(
        private val onClick: (UserInfo) -> Unit
    ) : RecyclerView.Adapter<UserListAdapter.VH>() {

        private val items = mutableListOf<UserInfo>()

        fun submit(list: List<UserInfo>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun append(list: List<UserInfo>) {
            val start = items.size; items.addAll(list); notifyItemRangeInserted(start, list.size)
        }

        class VH(val binding: ItemUserSimpleBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemUserSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            holder.binding.tvName.text = u.displayName()
            holder.binding.tvSub.text = "LV ${u.level} · ${FormatUtils.formatCount(u.followerCount.toLong())} 粉丝"
            ImageLoader.load(holder.binding.ivAvatar, u.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
            holder.itemView.setOnClickListener { onClick(u) }
        }
    }

    private class DynamicAdapter(
        private val onVideoClick: (com.lingxi.classic.data.model.Video) -> Unit
    ) : RecyclerView.Adapter<DynamicAdapter.VH>() {

        private val items = mutableListOf<Dynamic>()

        fun submit(list: List<Dynamic>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun append(list: List<Dynamic>) {
            val start = items.size; items.addAll(list); notifyItemRangeInserted(start, list.size)
        }

        class VH(val binding: ItemDynamicSimpleBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDynamicSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            val b = holder.binding
            b.tvContent.text = d.content.ifBlank { "分享了一条内容" }
            b.tvTime.text = FormatUtils.formatRelative(d.createdAt)
            val video = d.video
            b.tvVideoTitle.visible = video != null
            if (video != null) {
                b.tvVideoTitle.text = video.title
                b.tvVideoTitle.setOnClickListener { onVideoClick(video) }
            } else {
                b.tvVideoTitle.setOnClickListener(null)
            }
        }
    }
}
