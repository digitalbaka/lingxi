package com.lingxi.classic.ui.node

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.PlaybackHelper
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.GatewayNode
import com.lingxi.classic.databinding.ActivityNodeSelectBinding
import com.lingxi.classic.databinding.ItemNodeBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress

/** 节点选择：主站 + 直连节点列表，选中即切换播放源 */
class NodeSelectActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "LingXiNode"
        /** UI 侧硬上限：不依赖 OkHttp callTimeout，保证约 10s 一定给用户结果 */
        private const val NODE_UI_TIMEOUT_MS = 10_000L
    }

    private lateinit var binding: ActivityNodeSelectBinding
    private val adapter = NodeAdapter { node -> select(node) }

    /** 正在拉取节点列表：防止刷新按钮连点导致并发请求 */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodeSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnResetNode.setOnClickListener { resetToMainSite() }
        binding.btnRefresh.setOnClickListener { loadNodes(manual = true) }

        loadNodes()
    }

    /** 节点列表加载失败时，允许直接回到主站（等同选中列表里的主站行，持久化保存） */
    private fun resetToMainSite() {
        PlaybackHelper.selectNode(null)
        toast("已重置为默认服务器")
        finish()
    }

    /** 加载失败时统一入口：原 toast 文案不变，仅把「切回主站」按钮亮出来 */
    private fun showResetEntry(msg: String) {
        toast(msg)
        binding.btnResetNode.visible = true
    }

    private fun loadNodes(manual: Boolean = false) {
        // 刷新按钮连点时直接忽略，避免并发请求把列表刷乱
        if (loading) return
        loading = true
        binding.btnRefresh.isEnabled = false
        binding.btnRefresh.alpha = 0.5f
        binding.btnRefresh.text = "刷新中…"
        binding.btnResetNode.visible = false
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val resp = withTimeout(NODE_UI_TIMEOUT_MS) {
                    // 先单独给系统 DNS 解析计时：解析不可中断时，这里会一直涨到接近上限，
                    // 可据此判断 10s 到底卡在 DNS、建连还是读响应
                    val host = ApiClient.BASE_URL.substringAfter("://").substringBefore("/").substringBefore(":")
                    if (host.isNotEmpty()) {
                        val dnsStart = SystemClock.elapsedRealtime()
                        val dnsOk = withContext(Dispatchers.IO) {
                            try {
                                InetAddress.getByName(host)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                        Log.d(TAG, "nodes dns host=$host ok=$dnsOk elapsed=${SystemClock.elapsedRealtime() - dnsStart}ms")
                    }
                    ApiClient.nodeService.getGatewayNodes()
                }
                Log.i(TAG, "nodes ok elapsed=${SystemClock.elapsedRealtime() - started}ms")
                val info = resp.data
                if (!resp.isSuccess || info == null) {
                    showResetEntry(resp.message.ifBlank { "加载失败" })
                    return@launch
                }
                if (!info.enabled) {
                    binding.tvDisabled.visible = true
                    binding.recyclerView.visible = false
                    binding.tvQuota.visible = false
                    return@launch
                }
                binding.tvDisabled.visible = false
                binding.recyclerView.visible = true

                val rows = mutableListOf<NodeRow>()
                rows.add(NodeRow(0, info.origin?.name?.ifBlank { "主站" } ?: "主站（默认）",
                    "", info.origin?.url ?: "", false, 1.0))
                info.nodes.filter { it.enabled }.forEach { n ->
                    rows.add(NodeRow(n.id, n.name, n.note, n.url, n.isDirect, n.multiplier))
                }
                adapter.submit(rows)

                val quota = info.quota
                binding.tvQuota.visible = quota != null
                if (quota != null) {
                    binding.tvQuota.text = "节点流量：已用 ${FormatUtils.formatBytes(quota.used)} / 共 ${FormatUtils.formatBytes(quota.limit)}"
                }
                if (manual) toast("节点列表已刷新")
            } catch (e: TimeoutCancellationException) {
                // 协程硬上限到点：不等底层 DNS/连接自己结束，先给结果并亮出「切回主站」
                Log.w(TAG, "nodes ui-timeout after ${SystemClock.elapsedRealtime() - started}ms (cap=${NODE_UI_TIMEOUT_MS}ms)")
                showResetEntry("加载失败：请求超时")
            } catch (e: CancellationException) {
                // 页面销毁/旋转导致的取消不是加载失败：重新抛出，不 toast、不亮「切回主站」
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "nodes fail elapsed=${SystemClock.elapsedRealtime() - started}ms ${e.javaClass.simpleName}: ${e.message}")
                showResetEntry("加载失败：${e.message}")
            } finally {
                loading = false
                binding.btnRefresh.isEnabled = true
                binding.btnRefresh.alpha = 1f
                binding.btnRefresh.text = "刷新"
            }
        }
    }

    private fun select(row: NodeRow) {
        if (row.id == 0L) {
            PlaybackHelper.selectNode(null)
        } else {
            PlaybackHelper.selectNode(
                GatewayNode(id = row.id, name = row.name, kind = if (row.isDirect) "direct" else "", url = row.url)
            )
        }
        toast("已切换到 ${row.name}")
        adapter.refreshSelection()
    }

    private data class NodeRow(
        val id: Long,
        val name: String,
        val note: String,
        val url: String,
        val isDirect: Boolean,
        val multiplier: Double
    )

    private class NodeAdapter(
        private val onClick: (NodeRow) -> Unit
    ) : RecyclerView.Adapter<NodeAdapter.VH>() {

        private val items = mutableListOf<NodeRow>()

        fun submit(list: List<NodeRow>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun refreshSelection() = notifyDataSetChanged()

        class VH(val binding: ItemNodeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            val b = holder.binding
            b.tvName.text = row.name
            b.tvKindTag.visible = row.isDirect
            b.tvKindTag.text = "直连"
            b.tvMultiplier.visible = row.multiplier != 1.0
            b.tvMultiplier.text = "×${row.multiplier}"
            b.tvNote.visible = row.note.isNotEmpty()
            b.tvNote.text = row.note
            b.tvUrl.visible = row.url.isNotEmpty()
            b.tvUrl.text = row.url
            b.tvCheck.visible = AuthManager.nodeId == row.id
            holder.itemView.setOnClickListener { onClick(row) }
        }
    }
}
