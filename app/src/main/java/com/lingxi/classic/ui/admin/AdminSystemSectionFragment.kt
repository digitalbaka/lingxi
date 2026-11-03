package com.lingxi.classic.ui.admin

/** 系统管理板块：用户管理 / 提现处理 / 存储管理 / 接入节点 */
class AdminSystemSectionFragment : AdminSectionFragment() {
    override val entries = listOf(
        AdminEntry("👥", "用户管理") { AdminUsersFragment() },
        AdminEntry("💰", "提现处理", "withdrawals") { AdminWithdrawalsFragment() },
        AdminEntry("💾", "存储管理") { AdminStorageFragment() },
        AdminEntry("🌐", "接入节点") { AdminGatewayFragment() }
    )
}
