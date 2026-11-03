package com.lingxi.classic.ui.admin

/** 内容审核板块：稿件审核 / 评论弹幕 / 创作者申请 */
class AdminAuditSectionFragment : AdminSectionFragment() {
    override val entries = listOf(
        AdminEntry("🎬", "稿件审核", "queue") { AdminQueueFragment() },
        AdminEntry("💬", "评论弹幕", "comments") { AdminCommentsFragment() },
        AdminEntry("🧑‍🎨", "创作者申请", "creator") { AdminCreatorFragment() }
    )
}
