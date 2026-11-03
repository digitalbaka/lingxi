package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.ShopProduct
import com.lingxi.classic.databinding.DialogAdminProductBinding
import com.lingxi.classic.databinding.ItemAdminProductBinding
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import java.util.Locale

/** 商品管理（不分页） */
class AdminProductsFragment : AdminListFragment<ShopProduct>() {

    override fun emptyText(): String = "暂无商品"

    override fun onViewReady() {
        binding.topAction.visible = true
        binding.topAction.text = "＋ 新增商品"
        binding.topAction.setOnClickListener { showCreateDialog() }
    }

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<ShopProduct>, Boolean> {
        val data = ApiClient.apiService.getAdminProducts().unwrap(context)
        return (data?.items ?: emptyList()) to false
    }

    override fun createAdapter(): AdminAdapter<ShopProduct, *> =
        object : AdminAdapter<ShopProduct, ItemAdminProductBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminProductBinding =
                ItemAdminProductBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminProductBinding, item: ShopProduct, position: Int) {
                b.tvTitle.text = item.title
                b.tvPrice.text = "¥" + String.format(Locale.getDefault(), "%.2f", item.price / 100f)
                b.tvMeta.text = "类型：${item.kind.ifBlank { "-" }} · 库存 ${item.stock} · 限购 ${item.perUserLimit} · 排序 ${item.sortOrder}"
                b.switchEnabled.setOnCheckedChangeListener(null)
                b.switchEnabled.isChecked = item.enabled
                b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                    doAction({ refresh() }) {
                        ApiClient.apiService.updateProduct(item.id, mapOf("enabled" to checked))
                    }
                }
                b.btnDelete.setOnClickListener {
                    confirm("删除商品", "确定删除商品「${item.title}」吗？", "删除") {
                        doAction({ refresh() }) { ApiClient.apiService.deleteProduct(item.id) }
                    }
                }
            }
        }

    private fun showCreateDialog() {
        val ctx = context ?: return
        val b = DialogAdminProductBinding.inflate(layoutInflater)
        AlertDialog.Builder(ctx)
            .setTitle("新增商品")
            .setView(b.root)
            .setPositiveButton("创建") { _, _ ->
                val title = b.etTitle.text.toString().trim()
                if (title.isEmpty()) { ctx.toast("请输入商品标题"); return@setPositiveButton }
                val priceYuan = b.etPrice.text.toString().toDoubleOrNull() ?: 0.0
                val body = mapOf<String, Any>(
                    "title" to title,
                    "kind" to b.etKind.text.toString().trim(),
                    "price" to (priceYuan * 100).toLong(),
                    "stock" to (b.etStock.text.toString().toIntOrNull() ?: -1),
                    "perUserLimit" to (b.etLimit.text.toString().toIntOrNull() ?: 0),
                    "sortOrder" to (b.etSort.text.toString().toIntOrNull() ?: 0),
                    "enabled" to true
                )
                doAction({ refresh() }) { ApiClient.apiService.createProduct(body) }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
