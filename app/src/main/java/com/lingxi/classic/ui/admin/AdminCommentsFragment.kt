package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminCommentBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 评论/弹幕统一条目 */
data class AdminTextItem(
    val id: Long,
    val content: String,
    val author: String,
    val videoTitle: String,
    val extra: String,
    val createdAt: Long
)

/** 评论弹幕管理：顶部 Tab 切换 评论 / 弹幕 */
class AdminCommentsFragment : AdminListFragment<AdminTextItem>() {

    private var mode = 0 // 0 评论，1 弹幕

    override fun searchable(): Boolean = true
    override fun emptyText(): String = "暂无内容"

    override fun onViewReady() {
        binding.topTabScroll.visible = true
        buildTabs(binding.topTabRow, listOf("评论", "弹幕"), mode) { index ->
            mode = index
            styleTabs(binding.topTabRow, index)
            refresh()
        }
    }

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminTextItem>, Boolean> {
        return if (mode == 0) {
            val data = ApiClient.apiService.getAdminComments(keyword.ifBlank { null }, page).unwrap(context)
            val list = data?.items?.map {
                AdminTextItem(
                    id = it.id,
                    content = it.content,
                    author = it.author.displayName(),
                    videoTitle = it.video?.title ?: "",
                    extra = if (it.isReply) "楼中楼 · 赞 ${it.likeCount}" else "赞 ${it.likeCount}",
                    createdAt = it.createdAt
                )
            } ?: emptyList()
            list to (data?.hasMore ?: false)
        } else {
            val data = ApiClient.apiService.getAdminDanmaku(keyword.ifBlank { null }, page).unwrap(context)
            val list = data?.items?.map {
                AdminTextItem(
                    id = it.id,
                    content = it.content,
                    author = it.author.displayName(),
                    videoTitle = it.video?.title ?: "",
                    extra = "视频内 ${FormatUtils.formatDurationMs(it.timeMs)}",
                    createdAt = it.createdAt
                )
            } ?: emptyList()
            list to (data?.hasMore ?: false)
        }
    }

    override fun createAdapter(): AdminAdapter<AdminTextItem, *> =
        object : AdminAdapter<AdminTextItem, ItemAdminCommentBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminCommentBinding =
                ItemAdminCommentBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminCommentBinding, item: AdminTextItem, position: Int) {
                b.tvContent.text = item.content
                b.tvMeta.text = "${item.author} · ${item.videoTitle.ifBlank { "未知稿件" }} · ${FormatUtils.formatRelative(item.createdAt)}"
                b.tvExtra.text = item.extra
                b.btnDelete.setOnClickListener {
                    confirm("删除", "确定删除这条${if (mode == 0) "评论" else "弹幕"}吗？", "删除") {
                        doAction({ refresh() }) {
                            if (mode == 0) ApiClient.apiService.deleteAdminComment(item.id)
                            else ApiClient.apiService.deleteAdminDanmaku(item.id)
                        }
                    }
                }
            }
        }
}
