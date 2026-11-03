package com.lingxi.classic.ui.admin

/** 站务处理板块：举报处理 / 认证审核 / 海报审核 */
class AdminReportsSectionFragment : AdminSectionFragment() {
    override val entries = listOf(
        AdminEntry("🚨", "举报处理", "reports") { AdminReportsFragment() },
        AdminEntry("✅", "认证审核", "verifications") { AdminVerificationsFragment() },
        AdminEntry("🖼️", "海报审核", "posters") { AdminPostersFragment() }
    )
}
