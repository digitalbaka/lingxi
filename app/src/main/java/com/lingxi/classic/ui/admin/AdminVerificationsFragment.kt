package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminVerification
import com.lingxi.classic.data.model.ReviewRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminVerificationBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 认证审核列表 */
class AdminVerificationsFragment : AdminListFragment<AdminVerification>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待审核" to "0", "已通过" to "1", "已驳回" to "2"
    )

    override fun emptyText(): String = "暂无认证申请"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminVerification>, Boolean> {
        val data = ApiClient.apiService.getAdminVerifications(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminVerification, *> =
        object : AdminAdapter<AdminVerification, ItemAdminVerificationBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminVerificationBinding =
                ItemAdminVerificationBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminVerificationBinding, item: AdminVerification, position: Int) {
                b.tvName.text = item.user.displayName()
                b.tvStatus.text = item.statusLabel
                b.tvStatus.styleStatus(item.status)
                b.tvInfo.text = buildString {
                    append("类型：").append(item.type.ifBlank { "-" })
                    append(" · 分类：").append(item.category.ifBlank { "-" })
                    append(" · 标识：").append(item.label.ifBlank { "-" }).append("\n")
                    append("真实姓名：").append(item.realName.ifBlank { "-" }).append("\n")
                    append("联系方式：").append(item.contact.ifBlank { "-" }).append("\n")
                    append("证明材料：").append(item.evidence.ifBlank { "-" }).append("\n")
                    append("申请时间：").append(FormatUtils.formatRelative(item.createdAt))
                    if (item.reason.isNotBlank()) append("\n驳回原因：").append(item.reason)
                }
                b.btnRow.visible = item.status == 0
                if (item.status == 0) {
                    b.btnApprove.setOnClickListener {
                        doAction({ refresh() }) {
                            ApiClient.apiService.reviewVerification(item.id, ReviewRequest(true))
                        }
                    }
                    b.btnReject.setOnClickListener {
                        inputDialog("驳回原因", "填写驳回理由") { reason ->
                            doAction({ refresh() }) {
                                ApiClient.apiService.reviewVerification(item.id, ReviewRequest(false, reason))
                            }
                        }
                    }
                }
            }
        }
}
