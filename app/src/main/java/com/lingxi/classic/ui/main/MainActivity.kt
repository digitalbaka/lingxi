package com.lingxi.classic.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.databinding.ActivityMainBinding
import com.lingxi.classic.ui.search.SearchActivity
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主框架：自绘 Toolbar + 4 Tab 底部栏，Fragment add/hide/show 保活切换。
 * 顶部在线/在看数据：getSiteStats() 拉一次，之后每 30s presence 上报轮询，不可见时停止。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tabFragments = arrayOfNulls<Fragment>(TAB_COUNT)
    private var currentTab = -1

    private var presenceJob: Job? = null
    private var onlineCount = 0
    private var watchingCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 重建时 FragmentManager 会自动恢复已 add 的 Fragment，这里只取回引用，避免重复 add
        if (savedInstanceState != null) {
            for (i in 0 until TAB_COUNT) {
                tabFragments[i] = supportFragmentManager.findFragmentByTag(tagOf(i))
            }
            currentTab = savedInstanceState.getInt(KEY_TAB, 0)
        }

        initToolbar()
        initBottomBar()
        switchTab(currentTab.coerceIn(0, TAB_COUNT - 1), force = true)
        loadSiteStats()
    }

    override fun onResume() {
        super.onResume()
        // 启动/回到前台时检查一次令牌是否临期，需要就在后台预热续期（阻塞 0，失败不清会话）
        ApiClient.warmUpSessionIfNeeded()
    }

    // ==================== Toolbar ====================

    private fun initToolbar() {
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.tvStats.setOnClickListener {
            toast("当前在线 $onlineCount 人 · 在看 $watchingCount 人")
        }
    }

    private fun loadSiteStats() {
        lifecycleScope.launch {
            try {
                val d = ApiClient.apiService.getSiteStats().unwrap() ?: return@launch
                onlineCount = d.online
                watchingCount = d.watching
                renderStats()
            } catch (_: Exception) {
                // 站点数据失败静默，交给 presence 轮询继续尝试
            }
        }
    }

    // ==================== presence 轮询 ====================

    override fun onStart() {
        super.onStart()
        startPresence()
    }

    override fun onStop() {
        presenceJob?.cancel()
        presenceJob = null
        super.onStop()
    }

    private fun startPresence() {
        if (presenceJob?.isActive == true) return
        presenceJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val d = ApiClient.apiService.presence(mapOf("videoId" to 0L)).unwrap()
                    if (d != null) {
                        onlineCount = d.online
                        watchingCount = d.watching
                        renderStats()
                    }
                } catch (_: Exception) {
                    // 网络抖动静默，等下一轮
                }
                delay(PRESENCE_INTERVAL_MS)
            }
        }
    }

    private fun renderStats() {
        binding.tvStats.text = "在线 $onlineCount · 在看 $watchingCount"
    }

    // ==================== 底部 Tab ====================

    private fun initBottomBar() {
        binding.tabHome.setOnClickListener { switchTab(0) }
        binding.tabPartition.setOnClickListener { switchTab(1) }
        binding.tabDynamics.setOnClickListener { switchTab(2) }
        binding.tabMine.setOnClickListener { switchTab(3) }
        binding.tabPublish.setOnClickListener {
            if (!com.lingxi.classic.data.auth.AuthManager.isLoggedIn) {
                toast("请先登录后再投稿")
                startActivity(Intent(this, com.lingxi.classic.ui.auth.LoginActivity::class.java))
            } else {
                startActivity(Intent(this, com.lingxi.classic.ui.publish.PublishActivity::class.java))
            }
        }
    }

    private fun switchTab(index: Int, force: Boolean = false) {
        if (!force && index == currentTab) return
        val tx = supportFragmentManager.beginTransaction()
        for (i in 0 until TAB_COUNT) {
            if (i != index) tabFragments[i]?.let { tx.hide(it) }
        }
        var target = tabFragments[index]
        if (target == null) {
            target = createTabFragment(index)
            tabFragments[index] = target
            tx.add(R.id.fragmentContainer, target, tagOf(index))
        } else {
            tx.show(target)
        }
        tx.commit()
        currentTab = index
        updateTabColors()
    }

    private fun createTabFragment(index: Int): Fragment = when (index) {
        0 -> HomeFragment.newInstance()
        1 -> PartitionFragment.newInstance()
        2 -> DynamicsFragment.newInstance()
        else -> MineFragment.newInstance()
    }

    private fun updateTabColors() {
        val icons = listOf(binding.iconHome, binding.iconPartition, binding.iconDynamics, binding.iconMine)
        val labels = listOf(binding.labelHome, binding.labelPartition, binding.labelDynamics, binding.labelMine)
        val selectedColor = ContextCompat.getColor(this, R.color.lx_green)
        val normalColor = ContextCompat.getColor(this, R.color.lx_text_sub)
        for (i in 0 until TAB_COUNT) {
            val color = if (i == currentTab) selectedColor else normalColor
            icons[i].setTextColor(color)
            labels[i].setTextColor(color)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    companion object {
        private const val TAB_COUNT = 4
        private const val KEY_TAB = "current_tab"
        private const val PRESENCE_INTERVAL_MS = 30_000L

        private fun tagOf(index: Int) = "tab_$index"
    }
}
