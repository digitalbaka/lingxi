package com.lingxi.classic.ui.node

import android.os.Bundle
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
import kotlinx.coroutines.launch

/** 节点选择：主站 + 直连节点列表，选中即切换播放源 */
class NodeSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodeSelectBinding
    private val adapter = NodeAdapter { node -> select(node) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodeSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        loadNodes()
    }

    private fun loadNodes() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getGatewayNodes()
                val info = resp.data
                if (!resp.isSuccess || info == null) {
                    toast(resp.message.ifBlank { "加载失败" })
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
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
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
