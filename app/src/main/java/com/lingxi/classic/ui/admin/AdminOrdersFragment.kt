package com.lingxi.classic.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.ShopOrder
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemAdminOrderBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import java.util.Locale

/** 订单管理 */
class AdminOrdersFragment : AdminListFragment<ShopOrder>() {

    override fun filters(): List<Pair<String, String>> = listOf(
        "全部" to "", "待发货" to "0", "已发货" to "1", "已取消" to "2"
    )

    override fun emptyText(): String = "暂无订单"

    override suspend fun fetch(status: String, keyword: String, page: Int): Pair<List<ShopOrder>, Boolean> {
        val data = ApiClient.apiService.getAdminOrders(status.ifBlank { null }, page).unwrap(context)
        return (data?.items ?: emptyList()) to (data?.hasMore ?: false)
    }

    override fun createAdapter(): AdminAdapter<ShopOrder, *> =
        object : AdminAdapter<ShopOrder, ItemAdminOrderBinding>() {
            override fun onCreateBinding(inflater: LayoutInflater, parent: ViewGroup): ItemAdminOrderBinding =
                ItemAdminOrderBinding.inflate(inflater, parent, false)

            override fun onBind(b: ItemAdminOrderBinding, item: ShopOrder, position: Int) {
                b.tvTitle.text = "${item.productTitle} ×${item.quantity}"
                b.tvStatus.text = when (item.status) {
                    0 -> "待发货"
                    1 -> "已发货"
                    2 -> "已取消"
                    else -> "状态 ${item.status}"
                }
                b.tvStatus.styleStatus(item.status)
                b.tvPrice.text = "合计 ¥" + String.format(Locale.getDefault(), "%.2f", item.totalPrice / 100f)
                b.tvBuyer.text = "买家：${item.user.displayName()}"
                b.tvReceiver.text = "收货：${item.receiverName} ${item.receiverPhone}\n${item.receiverAddr}"
                b.tvTime.text = FormatUtils.formatRelative(item.createdAt)
                b.btnShip.visible = item.status == 0
                if (item.status == 0) {
                    b.btnShip.setOnClickListener {
                        confirm("确认发货", "确定对订单「${item.productTitle}」执行发货吗？", "发货") {
                            doAction({ refresh() }) {
                                ApiClient.apiService.handleOrder(item.id, mapOf("action" to "ship"))
                            }
                        }
                    }
                }
            }
        }
}
