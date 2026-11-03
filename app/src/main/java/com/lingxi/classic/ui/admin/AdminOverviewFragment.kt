package com.lingxi.classic.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.FragmentAdminOverviewBinding
import com.lingxi.classic.databinding.ItemAdminStatBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import kotlinx.coroutines.launch

/** 站务数据总览 */
class AdminOverviewFragment : Fragment() {

    private var _binding: FragmentAdminOverviewBinding? = null
    private val binding get() = _binding!!

    private val statsAdapter = StatAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.statsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.statsGrid.adapter = statsAdapter
        binding.swipe.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = ApiClient.apiService.getAdminOverview().unwrap(context)
                if (data != null) {
                    val t = data.totals
                    statsAdapter.submit(
                        listOf(
                            "用户" to FormatUtils.formatCount(t?.users ?: 0),
                            "稿件" to FormatUtils.formatCount(t?.videos ?: 0),
                            "待审核" to FormatUtils.formatCount(t?.pending ?: 0),
                            "弹幕" to FormatUtils.formatCount(t?.danmaku ?: 0),
                            "评论" to FormatUtils.formatCount(t?.comments ?: 0),
                            "关注" to FormatUtils.formatCount(t?.follows ?: 0),
                            "播放" to FormatUtils.formatCount(t?.views ?: 0)
                        )
                    )
                    fillRecent(binding.recentUsers, data.recentUsers.map {
                        "${it.nickname.ifBlank { it.username }}（UID ${it.id}）· Lv${it.level}"
                    })
                    fillRecent(binding.recentVideos, data.recentVideos.map {
                        "${it.title} · ${it.up.displayName()}"
                    })
                }
            } catch (e: Exception) {
                context?.toast("加载失败：${e.message}")
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun fillRecent(container: LinearLayout, lines: List<String>) {
        container.removeAllViews()
        if (lines.isEmpty()) {
            container.addView(makeLine("暂无数据"))
            return
        }
        lines.forEach { container.addView(makeLine(it)) }
    }

    private fun makeLine(text: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.lingxi.classic.R.color.lx_text))
            setPadding(0, 6, 0, 6)
        }
    }

    private inner class StatAdapter : RecyclerView.Adapter<StatAdapter.VH>() {

        private val items = mutableListOf<Pair<String, String>>()

        inner class VH(val b: ItemAdminStatBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<Pair<String, String>>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAdminStatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.b.tvValue.text = items[position].second
            holder.b.tvName.text = items[position].first
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
