package com.lingxi.classic.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.databinding.FragmentAdminSectionBinding
import com.lingxi.classic.databinding.ItemAdminEntryBinding
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/** 板块入口项 */
data class AdminEntry(
    val icon: String,
    val name: String,
    val badgeKey: String? = null,
    val factory: () -> Fragment
)

/** 管理后台板块基类：入口列表 + 待办红点 + 子页容器 */
abstract class AdminSectionFragment : Fragment() {

    private var _binding: FragmentAdminSectionBinding? = null
    protected val binding get() = _binding!!

    protected abstract val entries: List<AdminEntry>

    private var badges: Map<String, Int> = emptyMap()
    private lateinit var entryAdapter: EntryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminSectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        entryAdapter = EntryAdapter()
        binding.entryList.layoutManager = LinearLayoutManager(requireContext())
        binding.entryList.adapter = entryAdapter

        childFragmentManager.addOnBackStackChangedListener {
            if (childFragmentManager.backStackEntryCount == 0) {
                binding.childContainer.visible = false
            }
        }
        loadTodos()
    }

    private fun loadTodos() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val todos = ApiClient.apiService.getAdminTodos().unwrap() ?: return@launch
                badges = mapOf(
                    "queue" to todos.queue,
                    "comments" to todos.comments,
                    "reports" to todos.reports,
                    "verifications" to todos.verifications,
                    "posters" to todos.posters,
                    "creator" to todos.creator,
                    "withdrawals" to todos.withdrawals
                )
                entryAdapter.notifyDataSetChanged()
            } catch (_: Exception) {
                // 角标失败静默
            }
        }
    }

    /** 有子页时消费返回键 */
    fun handleBack(): Boolean {
        return if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            true
        } else false
    }

    private inner class EntryAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<EntryAdapter.VH>() {

        inner class VH(val b: ItemAdminEntryBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAdminEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.b.tvIcon.text = entry.icon
            holder.b.tvName.text = entry.name
            val count = entry.badgeKey?.let { badges[it] } ?: 0
            holder.b.dotBadge.visible = count > 0
            holder.itemView.setOnClickListener { open(entry) }
        }

        override fun getItemCount(): Int = entries.size
    }

    private fun open(entry: AdminEntry) {
        binding.childContainer.visible = true
        childFragmentManager.beginTransaction()
            .replace(binding.childContainer.id, entry.factory())
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
