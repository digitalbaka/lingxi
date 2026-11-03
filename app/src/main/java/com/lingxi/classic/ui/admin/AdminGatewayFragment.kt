package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminGateway
import com.lingxi.classic.data.model.AdminGrant
import com.lingxi.classic.data.model.GatewayNode
import com.lingxi.classic.data.model.GatewaySettingsRequest
import com.lingxi.classic.data.model.GrantActionRequest
import com.lingxi.classic.data.model.NodeEditRequest
import com.lingxi.classic.databinding.DialogAdminNodeBinding
import com.lingxi.classic.databinding.FragmentAdminGatewayBinding
import com.lingxi.classic.databinding.ItemAdminGrantBinding
import com.lingxi.classic.databinding.ItemAdminNodeBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import kotlinx.coroutines.launch

/** 接入节点管理：全局开关/配额 + 节点列表 + 授权 IP 列表 */
class AdminGatewayFragment : Fragment() {

    private var _binding: FragmentAdminGatewayBinding? = null
    private val binding get() = _binding!!

    private var gateway: AdminGateway? = null
    private val nodeAdapter = NodeAdapter()
    private val grantAdapter = GrantAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminGatewayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.nodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.nodeList.adapter = nodeAdapter
        binding.grantList.layoutManager = LinearLayoutManager(requireContext())
        binding.grantList.adapter = grantAdapter
        binding.swipe.setOnRefreshListener { load() }
        binding.btnAddNode.setOnClickListener { showAddNodeDialog() }
        binding.btnEditQuota.setOnClickListener {
            inputDialog("默认每日配额（GB）", "配额 GB", isNumber = true) { text ->
                val gb = text.toLongOrNull() ?: return@inputDialog
                doAction({ load() }) {
                    ApiClient.apiService.updateGatewaySettings(
                        GatewaySettingsRequest(gateway?.enabled ?: false, gb * 1024 * 1024 * 1024)
                    )
                }
            }
        }
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = ApiClient.apiService.getAdminGateway().unwrap(context)
                if (data != null) bind(data)
            } catch (e: Exception) {
                context?.toast("加载失败：${e.message}")
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun bind(data: AdminGateway) {
        gateway = data
        binding.switchEnabled.setOnCheckedChangeListener(null)
        binding.switchEnabled.isChecked = data.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            doAction({ load() }) {
                ApiClient.apiService.updateGatewaySettings(
                    GatewaySettingsRequest(checked, gateway?.defaultQuota ?: 0)
                )
            }
        }
        binding.tvQuota.text = "默认每日配额：${FormatUtils.formatBytes(data.defaultQuota)}"
        val t = data.totals
        binding.tvTotals.text = "今日已用 ${FormatUtils.formatBytes(t?.todayUsed ?: 0)} · 白名单 ${t?.whitelist ?: 0} · 活跃 ${t?.active ?: 0} · 封禁 ${t?.blocked ?: 0}"
        nodeAdapter.submit(data.nodes)
        grantAdapter.submit(data.grants)
    }

    private fun showAddNodeDialog() {
        val ctx = context ?: return
        val b = DialogAdminNodeBinding.inflate(layoutInflater)
        val kinds = arrayOf("direct", "proxy")
        b.spKind.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, kinds)
        AlertDialog.Builder(ctx)
            .setTitle("添加节点")
            .setView(b.root)
            .setPositiveButton("创建") { _, _ ->
                val name = b.etName.text.toString().trim()
                val url = b.etUrl.text.toString().trim()
                if (name.isEmpty()) { ctx.toast("请输入节点名称"); return@setPositiveButton }
                if (url.isEmpty()) { ctx.toast("请输入节点 URL"); return@setPositiveButton }
                doAction({ load() }) {
                    ApiClient.apiService.createNode(
                        NodeEditRequest(
                            name = name,
                            url = url,
                            kind = kinds[b.spKind.selectedItemPosition],
                            note = b.etNote.text.toString().trim(),
                            enabled = b.cbEnabled.isChecked
                        )
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class NodeAdapter : RecyclerView.Adapter<NodeAdapter.VH>() {

        private val items = mutableListOf<GatewayNode>()

        inner class VH(val b: ItemAdminNodeBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<GatewayNode>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAdminNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvName.text = item.name
            holder.b.tvStatus.text = if (item.enabled) "启用" else "停用"
            holder.b.tvStatus.setBackgroundResource(
                if (item.enabled) R.drawable.bg_tag else R.drawable.admin_tag_gray
            )
            holder.b.tvStatus.setTextColor(
                ContextCompat.getColor(
                    holder.b.tvStatus.context,
                    if (item.enabled) R.color.lx_green_dark else R.color.lx_text_sub
                )
            )
            holder.b.tvStatus.typeface = Typeface.DEFAULT_BOLD
            holder.b.tvUrl.text = item.url
            holder.b.tvMeta.text = buildString {
                append(item.kind.ifBlank { "direct" })
                append(" · ×").append(item.multiplier)
                if (item.note.isNotBlank()) append(" · ").append(item.note)
            }
            holder.itemView.setOnLongClickListener {
                confirm("删除节点", "确定删除节点「${item.name}」吗？", "删除") {
                    doAction({ load() }) { ApiClient.apiService.deleteNode(item.id) }
                }
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class GrantAdapter : RecyclerView.Adapter<GrantAdapter.VH>() {

        private val items = mutableListOf<AdminGrant>()

        inner class VH(val b: ItemAdminGrantBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<AdminGrant>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAdminGrantBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvIp.text = item.ip
            holder.b.tvStatus.text = when {
                item.blocked -> "已封禁"
                item.active -> "活跃"
                else -> "空闲"
            }
            val (bg, color) = when {
                item.blocked -> R.drawable.admin_tag_red to R.color.lx_red
                item.active -> R.drawable.bg_tag to R.color.lx_green_dark
                else -> R.drawable.admin_tag_gray to R.color.lx_text_sub
            }
            holder.b.tvStatus.setBackgroundResource(bg)
            holder.b.tvStatus.setTextColor(ContextCompat.getColor(holder.b.tvStatus.context, color))
            holder.b.tvStatus.typeface = Typeface.DEFAULT_BOLD
            holder.b.tvMeta.text = "UID ${item.uid} · 已用 ${FormatUtils.formatBytes(item.used)} / ${FormatUtils.formatBytes(item.quota)} · 授权 ${item.grantCount} 次"
            holder.b.btnBlock.text = if (item.blocked) "解封" else "封禁"
            holder.b.btnBlock.setOnClickListener {
                val action = if (item.blocked) "unblock" else "block"
                doAction({ load() }) {
                    ApiClient.apiService.handleGrant(item.ip, GrantActionRequest(action))
                }
            }
            holder.b.btnReset.setOnClickListener {
                confirm("清空配额", "确定清空 ${item.ip} 的今日配额用量吗？", "确定") {
                    doAction({ load() }) {
                        ApiClient.apiService.handleGrant(item.ip, GrantActionRequest("reset_quota"))
                    }
                }
            }
            holder.b.btnRaise.setOnClickListener {
                inputDialog("提升配额（GB）", "增加的配额 GB", isNumber = true) { text ->
                    val gb = text.toLongOrNull() ?: return@inputDialog
                    doAction({ load() }) {
                        ApiClient.apiService.handleGrant(
                            item.ip,
                            GrantActionRequest("raise_quota", gb * 1024 * 1024 * 1024)
                        )
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
