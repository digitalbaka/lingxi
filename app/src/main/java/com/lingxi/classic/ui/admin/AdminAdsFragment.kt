package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminAd
import com.lingxi.classic.databinding.DialogAdminAdBinding
import com.lingxi.classic.databinding.ItemAdminAdBinding
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 广告管理（不分页） */
class AdminAdsFragment : AdminListFragment<AdminAd>() {

    override fun emptyText(): String = "暂无广告"

    override fun onViewReady() {
        binding.topAction.visible = true
        binding.topAction.text = "＋ 新增广告"
        binding.topAction.setOnClickListener { showCreateDialog() }
    }

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminAd>, Boolean> {
        val data = ApiClient.apiService.getAdminAds().unwrap(context)
        return (data?.items ?: emptyList()) to false
    }

    override fun createAdapter(): AdminAdapter<AdminAd, *> =
        object : AdminAdapter<AdminAd, ItemAdminAdBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminAdBinding =
                ItemAdminAdBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminAdBinding, item: AdminAd, position: Int) {
                ImageLoader.load(b.ivImage, ApiClient.fullUrl(item.imageUrl))
                b.tvTitle.text = item.title
                b.tvLink.text = item.linkUrl.ifBlank { "无链接" }
                b.tvMeta.text = "位置：${item.position.ifBlank { "-" }} · 排序 ${item.sortOrder} · 曝光 ${item.impressions} · 点击 ${item.clicks}"
                b.switchEnabled.setOnCheckedChangeListener(null)
                b.switchEnabled.isChecked = item.enabled
                b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                    doAction({ refresh() }) {
                        ApiClient.apiService.updateAd(item.id, mapOf("enabled" to checked))
                    }
                }
                b.btnDelete.setOnClickListener {
                    confirm("删除广告", "确定删除广告「${item.title}」吗？", "删除") {
                        doAction({ refresh() }) { ApiClient.apiService.deleteAd(item.id) }
                    }
                }
            }
        }

    private fun showCreateDialog() {
        val ctx = context ?: return
        val b = DialogAdminAdBinding.inflate(layoutInflater)
        AlertDialog.Builder(ctx)
            .setTitle("新增广告")
            .setView(b.root)
            .setPositiveButton("创建") { _, _ ->
                val title = b.etTitle.text.toString().trim()
                if (title.isEmpty()) { ctx.toast("请输入广告标题"); return@setPositiveButton }
                val body = mapOf<String, Any>(
                    "title" to title,
                    "imageUrl" to b.etImage.text.toString().trim(),
                    "linkUrl" to b.etLink.text.toString().trim(),
                    "position" to b.etPosition.text.toString().trim(),
                    "sortOrder" to (b.etSort.text.toString().toIntOrNull() ?: 0)
                )
                doAction({ refresh() }) { ApiClient.apiService.createAd(body) }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
