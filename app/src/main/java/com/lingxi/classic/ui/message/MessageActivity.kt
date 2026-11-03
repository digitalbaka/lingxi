package com.lingxi.classic.ui.message

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Conversation
import com.lingxi.classic.data.model.Notification
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivityMessageBinding
import com.lingxi.classic.databinding.ItemConversationBinding
import com.lingxi.classic.databinding.ItemNotificationBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/** 消息中心：私信会话 + 通知 两个 Tab */
class MessageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageBinding

    private var currentTab = TAB_MESSAGE
    private var notifPage = 1
    private var notifHasMore = true
    private var loading = false

    private val conversationAdapter = ConversationAdapter { conv ->
        val user = conv.user ?: return@ConversationAdapter
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_UID, user.id)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, user.displayName())
        )
    }
    private val notificationAdapter = NotificationAdapter()

    companion object {
        private const val TAB_MESSAGE = 0
        private const val TAB_NOTIFICATION = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = conversationAdapter
        binding.swipeRefresh.setOnRefreshListener { reload() }

        binding.tabMessage.setOnClickListener { switchTab(TAB_MESSAGE) }
        binding.tabNotification.setOnClickListener { switchTab(TAB_NOTIFICATION) }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || currentTab != TAB_NOTIFICATION || loading || !notifHasMore) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 3) {
                    loadNotifications(append = true)
                }
            }
        })

        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == TAB_MESSAGE) loadConversations()
    }

    private fun switchTab(tab: Int) {
        if (tab == currentTab) return
        currentTab = tab
        binding.tabMessage.setTextColor(androidx.core.content.ContextCompat.getColor(this, 
            if (tab == TAB_MESSAGE) R.color.lx_green_dark else R.color.lx_text_sub))
        binding.tabNotification.setTextColor(androidx.core.content.ContextCompat.getColor(this, 
            if (tab == TAB_NOTIFICATION) R.color.lx_green_dark else R.color.lx_text_sub))
        binding.tabMessage.setTypeface(null, if (tab == TAB_MESSAGE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabNotification.setTypeface(null, if (tab == TAB_NOTIFICATION) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.recyclerView.adapter = if (tab == TAB_MESSAGE) conversationAdapter else notificationAdapter
        if (tab == TAB_NOTIFICATION && notificationAdapter.isEmpty()) {
            loadNotifications(append = false)
            markAllNotificationsRead()
        }
    }

    private fun reload() {
        if (currentTab == TAB_MESSAGE) loadConversations() else loadNotifications(append = false)
    }

    private fun loadConversations() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getConversations(limit = 50)
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    conversationAdapter.submit(data.items)
                } else {
                    toast(resp.message.ifBlank { "加载失败" })
                }
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadNotifications(append: Boolean) {
        if (!append) { notifPage = 1; notifHasMore = true }
        loading = true
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getNotifications(page = notifPage)
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    if (append) notificationAdapter.append(data.items)
                    else notificationAdapter.submit(data.items)
                    notifHasMore = data.hasMore
                    if (notifHasMore) notifPage++
                } else {
                    toast(resp.message.ifBlank { "加载失败" })
                }
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    /** 进入通知 Tab 即全部已读 */
    private fun markAllNotificationsRead() {
        lifecycleScope.launch {
            try { ApiClient.apiService.markNotificationsRead(mapOf("all" to true)) } catch (_: Exception) {}
        }
    }

    // ==================== 适配器 ====================

    private class ConversationAdapter(
        private val onClick: (Conversation) -> Unit
    ) : RecyclerView.Adapter<ConversationAdapter.VH>() {

        private val items = mutableListOf<Conversation>()

        fun submit(list: List<Conversation>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val b = holder.binding
            val user = c.user
            b.tvName.text = user.displayName("用户")
            b.tvLast.text = c.last?.content ?: ""
            b.tvTime.text = FormatUtils.formatRelative(c.last?.createdAt ?: c.updatedAt)
            b.tvUnread.visible = c.unread > 0
            b.tvUnread.text = if (c.unread > 99) "99+" else c.unread.toString()
            ImageLoader.load(b.ivAvatar, user?.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
            holder.itemView.setOnClickListener { onClick(c) }
        }
    }

    private class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.VH>() {

        private val items = mutableListOf<Notification>()

        fun isEmpty() = items.isEmpty()
        fun submit(list: List<Notification>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun append(list: List<Notification>) {
            val start = items.size; items.addAll(list); notifyItemRangeInserted(start, list.size)
        }

        class VH(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            val b = holder.binding
            b.tvTitle.text = n.title.ifBlank { n.type }
            b.tvContent.text = n.content
            b.tvTime.text = FormatUtils.formatRelative(n.createdAt)
            b.dotUnread.visible = !n.read
        }
    }
}
