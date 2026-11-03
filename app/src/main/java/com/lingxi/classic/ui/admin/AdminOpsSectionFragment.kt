package com.lingxi.classic.ui.admin

/** 运营管理板块：站务数据 / 广告管理 / 商城管理 */
class AdminOpsSectionFragment : AdminSectionFragment() {
    override val entries = listOf(
        AdminEntry("📊", "站务数据") { AdminOverviewFragment() },
        AdminEntry("📢", "广告管理") { AdminAdsFragment() },
        AdminEntry("🛒", "商城管理") { AdminShopFragment() }
    )
}
