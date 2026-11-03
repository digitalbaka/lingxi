package com.lingxi.classic.ui.message

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.ChatMessage
import com.lingxi.classic.databinding.ActivityChatBinding
import com.lingxi.classic.databinding.ItemChatLeftBinding
import com.lingxi.classic.databinding.ItemChatRightBinding
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import kotlinx.coroutines.launch

/** 单聊页：左右气泡 + 顶部滚动加载更早 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val adapter = ChatAdapter()

    private var peerUid: Long = 0
    private var peerName: String = ""
    private var peerAvatar: String = ""
    private var hasMore = false
    private var loading = false

    companion object {
        const val EXTRA_PEER_UID = "peer_uid"
        const val EXTRA_PEER_NAME = "peer_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerUid = intent.getLongExtra(EXTRA_PEER_UID, 0)
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: ""
        if (peerUid <= 0) { toast("参数错误"); finish(); return }
        binding.tvTitle.text = peerName.ifBlank { "聊天" }

        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerView.layoutManager = lm
        binding.recyclerView.adapter = adapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy >= 0 || loading || !hasMore) return
                val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findFirstVisibleItemPosition() == 0) {
                    loadEarlier()
                }
            }
        })

        binding.btnSend.setOnClickListener { sendMessage() }

        loadHistory()
        markRead()
    }

    // ==================== 消息加载 ====================

    private fun loadHistory() {
        loading = true
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getChatWith(peerUid)
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    peerAvatar = data.peer?.getFullAvatarUrl(ApiClient.BASE_URL) ?: ""
                    adapter.setPeerAvatar(peerAvatar)
                    adapter.submit(data.items)
                    hasMore = data.hasMore
                    if (adapter.itemCount > 0) {
                        binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                    }
                } else {
                    toast(resp.message.ifBlank { "加载失败" })
                }
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                loading = false
            }
        }
    }

    private fun loadEarlier() {
        val before = adapter.firstId() ?: return
        loading = true
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getChatWith(peerUid, before = before)
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    adapter.prepend(data.items)
                    hasMore = data.hasMore
                } else {
                    hasMore = false
                }
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    private fun markRead() {
        lifecycleScope.launch {
            try { ApiClient.apiService.markMessagesRead(mapOf("peerUid" to peerUid)) } catch (_: Exception) {}
        }
    }

    // ==================== 发送 ====================

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.sendMessage(mapOf("toUid" to peerUid, "content" to text))
                if (resp.isSuccess) {
                    binding.etInput.setText("")
                    adapter.append(
                        ChatMessage(
                            id = System.currentTimeMillis(),
                            fromUid = 0,
                            toUid = peerUid,
                            content = text,
                            mine = true,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                } else {
                    toast(resp.message.ifBlank { "发送失败" })
                }
            } catch (e: Exception) {
                toast("发送失败：${e.message}")
            } finally {
                binding.btnSend.isEnabled = true
            }
        }
    }

    // ==================== 适配器 ====================

    private class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<ChatMessage>()
        private var peerAvatarUrl: String = ""

        fun setPeerAvatar(url: String) { peerAvatarUrl = url }

        fun submit(list: List<ChatMessage>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        fun append(msg: ChatMessage) {
            items.add(msg)
            notifyItemInserted(items.size - 1)
        }

        /** 更早的消息插到头部，保持滚动位置 */
        fun prepend(list: List<ChatMessage>) {
            if (list.isEmpty()) return
            items.addAll(0, list)
            notifyItemRangeInserted(0, list.size)
        }

        fun firstId(): Long? = items.firstOrNull()?.takeIf { it.id > 0 }?.id

        class LeftVH(val binding: ItemChatLeftBinding) : RecyclerView.ViewHolder(binding.root)
        class RightVH(val binding: ItemChatRightBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int): Int = if (items[position].mine) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1)
                RightVH(ItemChatRightBinding.inflate(inflater, parent, false))
            else
                LeftVH(ItemChatLeftBinding.inflate(inflater, parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = items[position]
            when (holder) {
                is LeftVH -> {
                    holder.binding.tvContent.text = msg.content
                    ImageLoader.load(holder.binding.ivAvatar, peerAvatarUrl, circle = true)
                }
                is RightVH -> holder.binding.tvContent.text = msg.content
            }
        }
    }
}
