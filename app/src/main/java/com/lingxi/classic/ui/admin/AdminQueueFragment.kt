package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminVideo
import com.lingxi.classic.data.model.PinRequest
import com.lingxi.classic.data.model.ReviewRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminVideoBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 稿件审核列表 */
class AdminQueueFragment : AdminListFragment<AdminVideo>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待审核" to "0", "已发布" to "1", "未通过" to "2"
    )

    override fun emptyText(): String = "暂无稿件"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminVideo>, Boolean> {
        val data = ApiClient.apiService.getAdminQueue(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminVideo, *> =
        object : AdminAdapter<AdminVideo, ItemAdminVideoBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminVideoBinding =
                ItemAdminVideoBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminVideoBinding, item: AdminVideo, position: Int) {
                ImageLoader.load(b.ivCover, ApiClient.fullUrl(item.cover))
                b.tvTitle.text = item.title
                b.tvUp.text = "UP：${item.up.displayName()}"
                b.tvMeta.text = listOf(
                    FormatUtils.formatDuration(item.duration),
                    item.partition.ifBlank { "未分区" },
                    item.qualityLabel.ifBlank { "-" }
                ).joinToString(" · ")
                b.tvStatus.text = item.statusLabel
                b.tvStatus.styleStatus(item.status)
                b.tvPinned.visible = item.pinned
                b.root.setOnClickListener { showDetail(item) }
            }
        }

    private fun showDetail(item: AdminVideo) {
        val ctx = context ?: return
        val pad = 14.dp(ctx)
        val cover = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200.dp(ctx))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        ImageLoader.load(cover, ApiClient.fullUrl(item.cover))
        val body = TextView(ctx).apply {
            textSize = 13f
            setPadding(pad, pad, pad, pad)
            text = buildString {
                append("标题：").append(item.title).append("\n")
                append("UP：").append(item.up.displayName()).append("\n")
                append("分区：").append(item.partition.ifBlank { "未分区" })
                append(" · 时长：").append(FormatUtils.formatDuration(item.duration))
                append(" · 画质：").append(item.qualityLabel.ifBlank { "-" }).append("\n")
                append("投稿时间：").append(FormatUtils.formatRelative(item.createdAt)).append("\n\n")
                append(item.description.ifBlank { "（无简介）" })
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(cover)
            addView(body)
        }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val btnPin = if (item.pinned) "取消置顶" else "置顶"
        AlertDialog.Builder(ctx)
            .setTitle("稿件详情")
            .setView(scroll)
            .setPositiveButton("通过") { _, _ ->
                doAction({ refresh() }) {
                    ApiClient.apiService.reviewVideo(item.id, ReviewRequest(true))
                }
            }
            .setNegativeButton("拒绝") { _, _ ->
                inputDialog("拒绝原因", "填写拒绝理由") { reason ->
                    doAction({ refresh() }) {
                        ApiClient.apiService.reviewVideo(item.id, ReviewRequest(false, reason))
                    }
                }
            }
            .setNeutralButton(btnPin) { _, _ ->
                doAction({ refresh() }) {
                    ApiClient.apiService.pinVideo(item.id, PinRequest(!item.pinned))
                }
            }
            .show()
    }
}
