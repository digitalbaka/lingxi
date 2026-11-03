package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lingxi.classic.R
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.databinding.ActivityAdminBinding
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast

/** 管理后台主页：四大板块 Tab + show/hide 切换 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding

    private val titles = listOf("内容审核", "站务处理", "运营管理", "系统管理")
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isAdmin) {
            toast("无权限")
            finish()
            return
        }
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { onBackPressed() }
        buildTabs()
        select(0)
    }

    private fun buildTabs() {
        titles.forEachIndexed { index, title ->
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
            }
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(24.dp(this@AdminActivity), 3.dp(this@AdminActivity)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = 2.dp(this@AdminActivity)
                }
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(tv)
                addView(indicator)
                setOnClickListener { select(index) }
            }
            binding.tabRow.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun styleTabs() {
        for (i in 0 until binding.tabRow.childCount) {
            val cell = binding.tabRow.getChildAt(i) as? LinearLayout ?: continue
            val tv = cell.getChildAt(0) as? TextView ?: continue
            val indicator = cell.getChildAt(1) ?: continue
            if (i == current) {
                tv.setTextColor(ContextCompat.getColor(this, R.color.lx_green_dark))
                tv.typeface = Typeface.DEFAULT_BOLD
                indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.lx_green))
                indicator.visibility = View.VISIBLE
            } else {
                tv.setTextColor(ContextCompat.getColor(this, R.color.lx_text_sub))
                tv.typeface = Typeface.DEFAULT
                indicator.visibility = View.INVISIBLE
            }
        }
    }

    private fun createSection(index: Int): Fragment = when (index) {
        0 -> AdminAuditSectionFragment()
        1 -> AdminReportsSectionFragment()
        2 -> AdminOpsSectionFragment()
        else -> AdminSystemSectionFragment()
    }

    private fun select(index: Int) {
        current = index
        binding.tvSectionTitle.text = titles[index]
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (i in titles.indices) {
            val tag = "sec_$i"
            var frag = fm.findFragmentByTag(tag)
            if (i == index) {
                if (frag == null) {
                    frag = createSection(i)
                    tx.add(binding.container.id, frag, tag)
                } else {
                    tx.show(frag)
                }
            } else if (frag != null) {
                tx.hide(frag)
            }
        }
        tx.commit()
        styleTabs()
    }

    override fun onBackPressed() {
        val frag = supportFragmentManager.findFragmentByTag("sec_$current") as? AdminSectionFragment
        if (frag?.handleBack() == true) return
        super.onBackPressed()
    }
}
