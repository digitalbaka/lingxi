package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.AdminUser
import com.lingxi.classic.data.model.BadgeRequest
import com.lingxi.classic.data.model.BanRequest
import com.lingxi.classic.data.model.CoinsRequest
import com.lingxi.classic.data.model.ExpRequest
import com.lingxi.classic.data.model.RoleRequest
import com.lingxi.classic.databinding.ItemAdminUserBinding
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible

/** 用户管理 */
class AdminUsersFragment : AdminListFragment<AdminUser>() {

    override fun searchable(): Boolean = true
    override fun emptyText(): String = "暂无用户"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<AdminUser>, Boolean> {
        val data = ApiClient.apiService.getAdminUsers(keyword.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<AdminUser, *> =
        object : AdminAdapter<AdminUser, ItemAdminUserBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminUserBinding =
                ItemAdminUserBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminUserBinding, item: AdminUser, position: Int) {
                ImageLoader.load(b.ivAvatar, item.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
                b.tvName.text = item.nickname.ifBlank { item.username }
                b.tvUid.text = "UID：${item.id} · @${item.username}"
                b.tvMeta.text = "Lv${item.level} · 经验 ${item.exp} · 小鱼干 ${item.coins} · 稿件 ${item.videoCount} · 粉丝 ${item.followerCount} · 被举报 ${item.reportedCount}"
                b.tvAdmin.visible = item.isAdmin
                b.tvBanned.visible = item.banned
                b.root.setOnClickListener { showActions(item) }
            }
        }

    private fun showActions(item: AdminUser) {
        val ctx = context ?: return
        val labels = arrayOf(
            if (item.banned) "解除封禁" else "封禁账号",
            if (item.isAdmin) "取消管理员" else "设为管理员",
            "调整经验",
            "调整小鱼干",
            "授予徽章",
            "删除用户"
        )
        AlertDialog.Builder(ctx)
            .setTitle(item.nickname.ifBlank { item.username })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> doAction({ refresh() }) {
                        ApiClient.apiService.banUser(item.id, BanRequest(!item.banned))
                    }
                    1 -> doAction({ refresh() }) {
                        ApiClient.apiService.setUserRole(item.id, RoleRequest(!item.isAdmin, scopeAll = true))
                    }
                    2 -> twoInputDialog("调整经验", "经验变动（可为负数）", "原因", isNumber = true) { delta, reason ->
                        doAction({ refresh() }) {
                            ApiClient.apiService.adjustExp(item.id, ExpRequest(delta = delta.toLongOrNull(), reason = reason))
                        }
                    }
                    3 -> twoInputDialog("调整小鱼干", "变动数量（可为负数）", "原因", isNumber = true) { delta, reason ->
                        val d = delta.toLongOrNull() ?: return@twoInputDialog
                        doAction({ refresh() }) {
                            ApiClient.apiService.adjustCoins(item.id, CoinsRequest(d, reason))
                        }
                    }
                    4 -> twoInputDialog("授予徽章", "徽章类型", "徽章文案") { type, label ->
                        doAction({ refresh() }) {
                            ApiClient.apiService.grantBadge(item.id, BadgeRequest(type, label))
                        }
                    }
                    5 -> confirm("删除用户", "该操作不可恢复，确定删除用户「${item.username}」吗？", "删除") {
                        doAction({ refresh() }) { ApiClient.apiService.deleteUser(item.id) }
                    }
                }
            }
            .show()
    }
}
