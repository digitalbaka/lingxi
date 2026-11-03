package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lingxi.classic.R
import com.lingxi.classic.databinding.FragmentAdminTabContainerBinding
import com.lingxi.classic.util.dp

/** 商城管理：商品 / 订单 两个子页 */
class AdminShopFragment : Fragment() {

    private var _binding: FragmentAdminTabContainerBinding? = null
    private val binding get() = _binding!!

    private val tabs = listOf("商品", "订单")
    private var current = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminTabContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tabs.forEachIndexed { index, label ->
            val tv = TextView(requireContext()).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = 20.dp(context)
                }
                setPadding(4.dp(context), 0, 4.dp(context), 0)
                setOnClickListener { select(index) }
            }
            binding.tabRow.addView(tv)
        }
        select(0)
    }

    private fun select(index: Int) {
        current = index
        val frag: Fragment = if (index == 0) AdminProductsFragment() else AdminOrdersFragment()
        childFragmentManager.beginTransaction()
            .replace(binding.container.id, frag)
            .commit()
        val ctx = requireContext()
        for (i in 0 until binding.tabRow.childCount) {
            val tv = binding.tabRow.getChildAt(i) as? TextView ?: continue
            if (i == index) {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_green_dark))
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.lx_text_sub))
                tv.typeface = Typeface.DEFAULT
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
