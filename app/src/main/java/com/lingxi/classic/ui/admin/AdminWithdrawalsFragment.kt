package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminWithdrawal
import com.lingxi.classic.data.model.ReviewRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminWithdrawalBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import java.util.Locale

/** 提现处理 */
class AdminWithdrawalsFragment : AdminListFragment<AdminWithdrawal>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待审核" to "0", "待打款" to "1", "已打款" to "2", "已驳回" to "3"
    )

    override fun emptyText(): String = "暂无提现申请"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminWithdrawal>, Boolean> {
        val data = ApiClient.apiService.getAdminWithdrawals(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminWithdrawal, *> =
        object : AdminAdapter<AdminWithdrawal, ItemAdminWithdrawalBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminWithdrawalBinding =
                ItemAdminWithdrawalBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminWithdrawalBinding, item: AdminWithdrawal, position: Int) {
                b.tvName.text = item.user.displayName()
                b.tvAmount.text = "¥" + String.format(Locale.getDefault(), "%.2f", item.mills / 100f)
                b.tvInfo.text = "渠道：${item.channel.ifBlank { "-" }} · 收款人：${item.payeeName.ifBlank { "-" }} · ${item.contact.ifBlank { "-" }}" +
                    if (item.reason.isNotBlank()) "\n驳回原因：${item.reason}" else ""
                b.tvTime.text = FormatUtils.formatRelative(item.createdAt)
                b.tvStatus.text = item.statusLabel
                styleWithdrawalStatus(b.tvStatus, item.status)

                b.btnRow.visible = item.status == 0 || item.status == 1
                b.btnApprove.visible = item.status == 0
                b.btnReject.visible = item.status == 0
                b.btnPay.visible = item.status == 1
                b.btnApprove.setOnClickListener {
                    doAction({ refresh() }) {
                        ApiClient.apiService.reviewWithdrawal(item.id, ReviewRequest(true))
                    }
                }
                b.btnReject.setOnClickListener {
                    inputDialog("驳回原因", "填写驳回理由") { reason ->
                        doAction({ refresh() }) {
                            ApiClient.apiService.reviewWithdrawal(item.id, ReviewRequest(false, reason))
                        }
                    }
                }
                b.btnPay.setOnClickListener {
                    confirm("确认打款", "确定已线下打款 ¥" + String.format(Locale.getDefault(), "%.2f", item.mills / 100f) + " 吗？", "确认打款") {
                        doAction({ refresh() }) {
                            ApiClient.apiService.payWithdrawal(item.id, mapOf("note" to "管理员确认打款"))
                        }
                    }
                }
            }

            private fun styleWithdrawalStatus(tv: TextView, status: Int) {
                val ctx = tv.context
                when (status) {
                    0, 1 -> {
                        tv.setBackgroundResource(R.drawable.admin_tag_orange)
                        tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_orange))
                    }
                    2 -> {
                        tv.setBackgroundResource(R.drawable.bg_tag)
                        tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_green_dark))
                    }
                    else -> {
                        tv.setBackgroundResource(R.drawable.admin_tag_red)
                        tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_red))
                    }
                }
                tv.typeface = Typeface.DEFAULT_BOLD
            }
        }
}
