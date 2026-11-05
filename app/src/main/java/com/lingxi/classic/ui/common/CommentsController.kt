package com.lingxi.classic.ui.common

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Comment
import com.lingxi.classic.data.model.CommentSendRequest
import com.lingxi.classic.data.model.PinRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.DialogCommentRepliesBinding
import com.lingxi.classic.databinding.ItemCommentBinding
import com.lingxi.classic.databinding.ItemCommentReplyBinding
import com.lingxi.classic.databinding.ViewCommentInputBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 评论区控制器：封装分页加载 / 发评论 / 点赞 / 展开回复 / 删除 / 置顶。
 * 宿主 RecyclerView 一般嵌在 NestedScrollView 中（isNestedScrollingEnabled=false），
 * 自身不产生滚动事件，宿主需在滚动到底部附近时调用 [maybeLoadMore]。
 */
class CommentsController(
    private val videoId: Long,
    private val upUid: Long,
    private val recyclerView: RecyclerView,
    inputBar: View,
    private val context: Context
) {
    private val input = ViewCommentInputBinding.bind(inputBar)
    private val adapter = CommentAdapter()

    private var page = 1
    private var loading = false
    private var hasMore = true

    private val scope: CoroutineScope
        get() = (context as LifecycleOwner).lifecycleScope

    private val myUid: Long get() = AuthManager.user?.id ?: 0L

    /** 当前登录用户即本稿 UP 主 → 显示置顶按钮 */
    private val isUp: Boolean get() = upUid > 0 && myUid == upUid

    init {
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.setHasFixedSize(false)

        input.btnSend.setOnClickListener { sendTopLevel() }
        input.etComment.setOnClickListener {
            if (!AuthManager.isLoggedIn) context.toast("请先登录")
        }
        refresh()
    }

    fun refresh() {
        page = 1
        hasMore = true
        loadPage(1)
    }

    /** 滚动接近底部时由宿主调用，内部自行去重 */
    fun maybeLoadMore() {
        if (hasMore && !loading) loadPage(page + 1)
    }

    private fun loadPage(p: Int) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val data = ApiClient.apiService.getComments(videoId, null, p).unwrap(context)
                if (data != null) {
                    page = p
                    hasMore = data.hasMore
                    if (p == 1) adapter.submit(data.items) else adapter.append(data.items)
                }
            } catch (e: Exception) {
                if (p == 1) context.toast("评论加载失败：${e.message}")
            } finally {
                loading = false
            }
        }
    }

    private fun sendTopLevel() {
        if (!AuthManager.isLoggedIn) {
            context.toast("请先登录")
            return
        }
        val content = input.etComment.text.toString().trim()
        if (content.isEmpty()) {
            context.toast("评论不能为空")
            return
        }
        scope.launch {
            try {
                ApiClient.apiService.sendComment(videoId, CommentSendRequest(content))
                    .unwrap(context) ?: return@launch
                input.etComment.setText("")
                context.toast("评论成功")
                hideKeyboard()
                refresh()
            } catch (e: Exception) {
                context.toast("发送失败：${e.message}")
            }
        }
    }

    /**
     * 发送成功后收起键盘（失败路径不调用，保留输入内容与键盘）。
     * token 取宿主 Activity 的 decorView，其次退回输入条根视图的 windowToken：
     * etComment 这时候可能已经因为清空文本而失去焦点，用它自己的 token 不一定有效。
     */
    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (context as? Activity)?.window?.decorView?.windowToken
            ?: input.root.windowToken
            ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun toggleLike(comment: Comment, position: Int) {
        if (!AuthManager.isLoggedIn) {
            context.toast("请先登录")
            return
        }
        scope.launch {
            try {
                val r = ApiClient.apiService.likeComment(comment.id).unwrap(context) ?: return@launch
                adapter.update(position, comment.copy(liked = r.liked, likeCount = r.likeCount, upLiked = r.upLiked))
            } catch (e: Exception) {
                context.toast("操作失败：${e.message}")
            }
        }
    }

    private fun togglePin(comment: Comment, position: Int) {
        scope.launch {
            try {
                ApiClient.apiService.pinComment(comment.id, PinRequest(!comment.pinned))
                    .unwrap(context) ?: return@launch
                adapter.update(position, comment.copy(pinned = !comment.pinned))
                context.toast(if (!comment.pinned) "已置顶" else "已取消置顶")
            } catch (e: Exception) {
                context.toast("操作失败：${e.message}")
            }
        }
    }

    private fun confirmDelete(comment: Comment, position: Int) {
        AlertDialog.Builder(context)
            .setMessage("删除这条评论？")
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    try {
                        ApiClient.apiService.deleteComment(comment.id).unwrap(context) ?: return@launch
                        adapter.removeAt(position)
                        context.toast("已删除")
                    } catch (e: Exception) {
                        context.toast("删除失败：${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 楼中楼：BottomSheet 风格弹窗，内部分页加载全部回复并可直接回复 */
    private fun showRepliesDialog(comment: Comment, position: Int) {
        val db = DialogCommentRepliesBinding.inflate(LayoutInflater.from(context))
        val replyAdapter = ReplyAdapter()
        db.rvReplies.layoutManager = LinearLayoutManager(context)
        db.rvReplies.adapter = replyAdapter
        val dialog = AlertDialog.Builder(context).setView(db.root).create()

        var rPage = 1
        var rHasMore = true
        var rLoading = false

        fun loadReplies(p: Int) {
            if (rLoading) return
            rLoading = true
            scope.launch {
                try {
                    val data = ApiClient.apiService.getCommentReplies(comment.id, p).unwrap(context)
                    if (data != null) {
                        rPage = p
                        rHasMore = data.hasMore
                        if (p == 1) replyAdapter.submit(data.items) else replyAdapter.append(data.items)
                    }
                } catch (e: Exception) {
                    context.toast("加载失败：${e.message}")
                } finally {
                    rLoading = false
                }
            }
        }

        db.rvReplies.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || rLoading || !rHasMore) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 3) loadReplies(rPage + 1)
            }
        })

        db.btnReply.setOnClickListener {
            if (!AuthManager.isLoggedIn) {
                context.toast("请先登录")
                return@setOnClickListener
            }
            val content = db.etReply.text.toString().trim()
            if (content.isEmpty()) return@setOnClickListener
            scope.launch {
                try {
                    ApiClient.apiService.sendComment(
                        videoId, CommentSendRequest(content, parentId = comment.id)
                    ).unwrap(context) ?: return@launch
                    db.etReply.setText("")
                    context.toast("回复成功")
                    val latest = adapter.items.getOrNull(position) ?: comment
                    adapter.update(position, latest.copy(replyCount = latest.replyCount + 1))
                    loadReplies(1)
                } catch (e: Exception) {
                    context.toast("发送失败：${e.message}")
                }
            }
        }

        loadReplies(1)
        dialog.show()
        dialog.window?.let { w ->
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        }
    }

    // ==================== 一级评论列表 ====================

    private inner class CommentAdapter : RecyclerView.Adapter<CommentAdapter.VH>() {

        val items = mutableListOf<Comment>()

        fun submit(list: List<Comment>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Comment>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun update(position: Int, comment: Comment) {
            if (position in items.indices) {
                items[position] = comment
                notifyItemChanged(position)
            }
        }

        fun removeAt(position: Int) {
            if (position in items.indices) {
                items.removeAt(position)
                notifyItemRemoved(position)
            }
        }

        inner class VH(val b: ItemCommentBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val b = holder.b
            ImageLoader.load(b.ivAvatar, c.user?.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
            b.tvNickname.text = c.user.displayName()
            b.tvTime.text = FormatUtils.formatRelative(c.createdAt)
            b.tvContent.text = c.content

            b.tvPinned.visible = c.pinned
            b.tvUpBadge.visible = c.user?.id == upUid && upUid > 0
            b.tvUpLiked.visible = c.upLiked

            b.tvLike.text = "❤ ${c.likeCount}"
            b.tvLike.setTextColor(
                ContextCompat.getColor(context, if (c.liked) R.color.lx_green else R.color.lx_text_sub)
            )
            b.tvReply.text = if (c.replyCount > 0) "回复 ${c.replyCount}" else "回复"

            b.tvPin.visible = isUp
            b.tvPin.text = if (c.pinned) "取消置顶" else "置顶"

            // 楼中楼预览：内嵌前 2 条
            b.llReplies.removeAllViews()
            val preview = c.replies.take(2)
            b.llReplies.visible = preview.isNotEmpty()
            for (r in preview) {
                val rb = ItemCommentReplyBinding.inflate(LayoutInflater.from(context), b.llReplies, false)
                rb.tvReplyName.text = "${r.user.displayName()}："
                rb.tvReplyContent.text = r.content
                b.llReplies.addView(rb.root)
            }
            b.tvViewAllReplies.visible = c.replyCount > 2
            if (c.replyCount > 2) b.tvViewAllReplies.text = "查看全部 ${c.replyCount} 条回复 >"

            b.tvLike.setOnClickListener { toggleLike(c, holder.bindingAdapterPosition) }
            b.tvReply.setOnClickListener { showRepliesDialog(c, holder.bindingAdapterPosition) }
            b.tvViewAllReplies.setOnClickListener { showRepliesDialog(c, holder.bindingAdapterPosition) }
            b.tvPin.setOnClickListener { togglePin(c, holder.bindingAdapterPosition) }
            b.ivAvatar.setOnClickListener {
                val uid = c.user?.id ?: 0L
                if (uid > 0) Navigator.openSpace(context, uid)
            }
            holder.itemView.setOnLongClickListener {
                if (myUid > 0 && c.user?.id == myUid) {
                    confirmDelete(c, holder.bindingAdapterPosition)
                    true
                } else false
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivAvatar)
        }
    }

    // ==================== 回复弹窗列表 ====================

    private inner class ReplyAdapter : RecyclerView.Adapter<ReplyAdapter.VH>() {

        private val items = mutableListOf<Comment>()

        fun submit(list: List<Comment>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Comment>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        inner class VH(val b: ItemCommentReplyBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCommentReplyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.b.tvReplyName.text = "${r.user.displayName()}："
            holder.b.tvReplyContent.text = r.content
        }
    }
}
