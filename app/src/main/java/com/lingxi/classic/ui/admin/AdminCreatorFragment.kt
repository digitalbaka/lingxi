package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.CreatorApplication
import com.lingxi.classic.data.model.ReviewRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminCreatorBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 创作者申请审核 */
class AdminCreatorFragment : AdminListFragment<CreatorApplication>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待审核" to "0", "已通过" to "1", "已驳回" to "2"
    )

    override fun emptyText(): String = "暂无申请"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<CreatorApplication>, Boolean> {
        val data = ApiClient.apiService.getCreatorApplications(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<CreatorApplication, *> =
        object : AdminAdapter<CreatorApplication, ItemAdminCreatorBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminCreatorBinding =
                ItemAdminCreatorBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminCreatorBinding, item: CreatorApplication, position: Int) {
                b.tvName.text = item.user.displayName()
                b.tvStatus.text = item.statusLabel
                b.tvStatus.styleStatus(item.status)
                b.tvIntro.text = item.intro.ifBlank { "（无申请说明）" }
                b.tvMeta.text = "稿件 ${item.videoCount} · 播放 ${FormatUtils.formatCount(item.totalViews)} · ${FormatUtils.formatRelative(item.createdAt)}"
                b.btnRow.visible = item.status == 0
                if (item.status == 0) {
                    b.btnApprove.setOnClickListener {
                        doAction({ refresh() }) {
                            ApiClient.apiService.reviewCreatorApplication(item.id, ReviewRequest(true))
                        }
                    }
                    b.btnReject.setOnClickListener {
                        inputDialog("驳回原因", "填写驳回理由") { reason ->
                            doAction({ refresh() }) {
                                ApiClient.apiService.reviewCreatorApplication(item.id, ReviewRequest(false, reason))
                            }
                        }
                    }
                }
            }
        }
}
