package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminReport
import com.lingxi.classic.data.model.HandleReportRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminReportBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 举报处理列表 */
class AdminReportsFragment : AdminListFragment<AdminReport>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待处理" to "pending", "已处理" to "resolved", "已驳回" to "rejected"
    )

    override fun emptyText(): String = "暂无举报"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminReport>, Boolean> {
        val data = ApiClient.apiService.getAdminReports(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminReport, *> =
        object : AdminAdapter<AdminReport, ItemAdminReportBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminReportBinding =
                ItemAdminReportBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminReportBinding, item: AdminReport, position: Int) {
                b.tvReporter.text = "举报人：${item.reporter.displayName()}"
                b.tvStatus.styleReportStatus(item.status)
                val targetText = item.target?.let {
                    it.label.ifBlank { it.title.ifBlank { it.content } }
                } ?: "内容已删除"
                b.tvTarget.text = "${targetTypeLabel(item.targetType)}：$targetText"
                b.tvReason.text = "原因：${item.reason}${if (item.detail.isNotBlank()) "\n补充：${item.detail}" else ""}"
                b.tvTime.text = FormatUtils.formatRelative(item.createdAt)
                b.btnRow.visible = item.status == "pending"
                if (item.status == "pending") {
                    b.btnRemove.setOnClickListener { handle(item, "remove", "下架内容") }
                    b.btnResolve.setOnClickListener { handle(item, "resolve", "标记已处理") }
                    b.btnRejectReport.setOnClickListener { handle(item, "reject", "驳回举报") }
                }
            }

            private fun handle(item: AdminReport, action: String, label: String) {
                confirm(label, "确定对该举报执行「$label」吗？", "确定") {
                    doAction({ refresh() }) {
                        ApiClient.apiService.handleReport(item.id, HandleReportRequest(action))
                    }
                }
            }
        }
}
