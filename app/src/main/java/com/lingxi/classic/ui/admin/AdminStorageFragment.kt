package com.lingxi.classic.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.StorageDisk
import com.lingxi.classic.databinding.FragmentAdminStorageBinding
import com.lingxi.classic.databinding.ItemAdminDiskBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import kotlinx.coroutines.launch

/** 存储管理：磁盘概况 + 重扫 / 深度统计 */
class AdminStorageFragment : Fragment() {

    private var _binding: FragmentAdminStorageBinding? = null
    private val binding get() = _binding!!

    private val diskAdapter = DiskAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.diskList.layoutManager = LinearLayoutManager(requireContext())
        binding.diskList.adapter = diskAdapter
        binding.swipe.setOnRefreshListener { load(0) }
        binding.btnRescan.setOnClickListener {
            doAction({ load(0) }) { ApiClient.apiService.rescanStorage() }
        }
        binding.btnDeep.setOnClickListener { load(1) }
        load(0)
    }

    private fun load(deep: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = ApiClient.apiService.getAdminStorage(deep).unwrap(context)
                if (data != null) {
                    binding.tvSummary.text = buildString {
                        append("可写磁盘：").append(data.writable).append(" / ").append(data.total).append("\n")
                        append("最小剩余空间：").append(FormatUtils.formatBytes(data.minFreeBytes)).append("\n")
                        append("写入目录：").append(data.writeRoot.ifBlank { "-" })
                        if (data.extraDirs.isNotEmpty()) {
                            append("\n额外目录：").append(data.extraDirs.joinToString("、"))
                        }
                    }
                    diskAdapter.submit(data.disks)
                }
            } catch (e: Exception) {
                context?.toast("加载失败：${e.message}")
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private inner class DiskAdapter : RecyclerView.Adapter<DiskAdapter.VH>() {

        private val items = mutableListOf<StorageDisk>()

        inner class VH(val b: ItemAdminDiskBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<StorageDisk>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAdminDiskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvLabel.text = item.label.ifBlank { item.root }
            holder.b.tvKind.text = item.kind.ifBlank { "磁盘" }
            holder.b.tvRoot.text = item.root
            holder.b.progress.progress =
                if (item.totalBytes > 0) (item.usedBytes * 100 / item.totalBytes).toInt().coerceIn(0, 100) else 0
            holder.b.tvUsage.text = "${FormatUtils.formatBytes(item.usedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)} · 文件 ${item.fileCount}"
            holder.b.tvFlags.text = buildList {
                if (item.writable) add("可写")
                if (item.preferred) add("优先")
            }.joinToString(" · ")
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
