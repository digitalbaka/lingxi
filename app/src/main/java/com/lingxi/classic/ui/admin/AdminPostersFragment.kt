package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminPoster
import com.lingxi.classic.data.model.PosterReviewRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminPosterBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import java.util.Locale

/** 海报审核列表 */
class AdminPostersFragment : AdminListFragment<AdminPoster>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待审核" to "0", "投放中" to "1", "已驳回" to "2"
    )

    override fun emptyText(): String = "暂无海报申请"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminPoster>, Boolean> {
        val data = ApiClient.apiService.getAdminPosters(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminPoster, *> =
        object : AdminAdapter<AdminPoster, ItemAdminPosterBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminPosterBinding =
                ItemAdminPosterBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminPosterBinding, item: AdminPoster, position: Int) {
                ImageLoader.load(b.ivImage, ApiClient.fullUrl(item.imageUrl))
                b.tvTitle.text = item.title.ifBlank { "未命名海报" }
                b.tvStatus.text = item.statusLabel
                b.tvStatus.styleStatus(item.status)
                b.tvMeta.text = buildString {
                    append("投放人：").append(item.user.displayName())
                    append(" · 计划：").append(item.planName.ifBlank { "-" })
                    append(" · 价格：¥").append(String.format(Locale.getDefault(), "%.2f", item.price / 100f)).append("\n")
                    append("曝光 ${item.impressions} · 点击 ${item.clicks} · ").append(FormatUtils.formatRelative(item.createdAt))
                    if (item.linkUrl.isNotBlank()) append("\n链接：").append(item.linkUrl)
                    if (item.reason.isNotBlank()) append("\n驳回原因：").append(item.reason)
                }
                b.btnRow.visible = item.status == 0
                if (item.status == 0) {
                    b.btnApprove.setOnClickListener {
                        doAction({ refresh() }) {
                            ApiClient.apiService.reviewPoster(item.id, PosterReviewRequest(1))
                        }
                    }
                    b.btnReject.setOnClickListener {
                        inputDialog("驳回原因", "填写驳回理由") { reason ->
                            doAction({ refresh() }) {
                                ApiClient.apiService.reviewPoster(item.id, PosterReviewRequest(2, reason))
                            }
                        }
                    }
                }
            }
        }
}
