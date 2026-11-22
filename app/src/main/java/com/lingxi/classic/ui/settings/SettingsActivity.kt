package com.lingxi.classic.ui.settings

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lingxi.classic.BuildConfig
import com.lingxi.classic.R
import com.lingxi.classic.databinding.ActivitySettingsBinding
import com.lingxi.classic.player.CacheStorage
import com.lingxi.classic.player.DanmakuSettings
import com.lingxi.classic.player.OfflineCacheManager
import com.lingxi.classic.player.PlaybackPrefs
import com.lingxi.classic.player.VideoCacheManager
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong

/** 设置：视频缓存、离线缓存、存储位置、后台音频、弹幕参数、关于 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** onResume 里回填开关状态时不要触发监听写入/请求权限 */
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rowCacheLimit.setOnClickListener { showCacheLimitDialog() }
        binding.rowClearCache.setOnClickListener { confirmClearCache() }
        binding.rowDanmaku.setOnClickListener { showDanmakuDialog() }
        binding.rowStorage.setOnClickListener { showStorageDialog() }
        binding.rowOffline.setOnClickListener { showOfflineDialog() }
        binding.rowBackgroundAudio.setOnClickListener {
            binding.switchBackgroundAudio.isChecked = !binding.switchBackgroundAudio.isChecked
        }
        binding.switchBackgroundAudio.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            PlaybackPrefs.setBackgroundAudioEnabled(this, checked)
            if (checked) ensureNotificationPermission()
        }

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()
        refreshCacheViews()
        refreshStorageViews()
        suppressSwitchCallback = true
        binding.switchBackgroundAudio.isChecked = PlaybackPrefs.isBackgroundAudioEnabled(this)
        suppressSwitchCallback = false
    }

    private fun refreshCacheViews() {
        binding.tvCacheLimit.text = "${VideoCacheManager.getCacheLimitMb(this)} MB"
        binding.tvCacheSize.text = FormatUtils.formatBytes(VideoCacheManager.getCacheSizeBytes(this))
    }

    private fun refreshStorageViews() {
        binding.tvStorage.text = CacheStorage.describeTarget(this)
        val list = OfflineCacheManager.list(this)
        val size = list.sumOf { it.size }
        binding.tvOfflineSize.text =
            if (list.isEmpty()) "暂无缓存" else "${list.size} 个 · ${OfflineCacheManager.formatSize(size)}"
    }

    // ==================== 缓存存储位置 / 离线缓存 ====================

    private fun showStorageDialog() {
        val externalOk = CacheStorage.isExternalAvailable(this)
        val labels = arrayOf("内部存储", if (externalOk) "SD 卡" else "SD 卡（未检测到）")
        val checked = if (CacheStorage.isExternalTarget(this)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("缓存存储位置")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which == 1 && !externalOk) {
                    toast("未检测到可用的 SD 卡")
                    return@setSingleChoiceItems
                }
                CacheStorage.setTarget(
                    this,
                    if (which == 1) CacheStorage.TARGET_EXTERNAL else CacheStorage.TARGET_INTERNAL
                )
                refreshStorageViews()
                refreshCacheViews()
                toastLong("已切换到${if (which == 1) "SD 卡" else "内部存储"}，之后缓存的视频/音频保存在新位置；已缓存的旧文件仍在原目录，不会出现在「我的缓存」列表里")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOfflineDialog() {
        val list = OfflineCacheManager.list(this)
        if (list.isEmpty()) {
            toast("还没有离线缓存，视频/音频页点「缓存」即可保存到本地")
            return
        }
        val labels = list.map { e ->
            "${if (e.kind == OfflineCacheManager.KIND_AUDIO) "音频" else "视频"} · ${e.title}\n${OfflineCacheManager.formatSize(e.size)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("离线缓存（点击删除）")
            .setItems(labels) { _, which ->
                val e = list[which]
                AlertDialog.Builder(this)
                    .setTitle("删除缓存")
                    .setMessage("删除「${e.title}」的本地文件？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        OfflineCacheManager.delete(this, e)
                        refreshStorageViews()
                        toast("已删除")
                    }
                    .show()
            }
            .setPositiveButton("清空全部") { _, _ ->
                OfflineCacheManager.clearAll(this)
                refreshStorageViews()
                toast("离线缓存已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ==================== 缓存 ====================

    private fun showCacheLimitDialog() {
        val options = VideoCacheManager.CACHE_LIMIT_OPTIONS_MB
        val labels = options.map { "$it MB" }.toTypedArray()
        val current = VideoCacheManager.getCacheLimitMb(this)
        val checked = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("视频缓存上限")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                VideoCacheManager.setCacheLimitMb(this, options[which])
                refreshCacheViews()
                toast("缓存上限已设为 ${options[which]} MB")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("清理视频缓存")
            .setMessage("将删除所有已缓存的视频片段，确定清理吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                VideoCacheManager.clearCache(this)
                refreshCacheViews()
                toast("缓存已清理")
            }
            .show()
    }

    // ==================== 弹幕设置 ====================

    private fun showDanmakuDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(this@SettingsActivity), 8.dp(this@SettingsActivity),
                20.dp(this@SettingsActivity), 0)
        }

        val tvScale = TextView(this).apply { setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.lx_text)) }
        val sbScale = SeekBar(this).apply { max = 150 } // 0.5x ~ 2.0x
        val tvSpeed = TextView(this).apply { setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.lx_text)) }
        val sbSpeed = SeekBar(this).apply { max = 150 } // 0.5x ~ 2.0x
        val tvAlpha = TextView(this).apply { setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.lx_text)) }
        val sbAlpha = SeekBar(this).apply { max = 225 } // 30 ~ 255
        val etBlock = EditText(this).apply {
            hint = "屏蔽词，多个用英文逗号分隔"
            setText(DanmakuSettings.blockWords.joinToString(","))
        }

        fun refreshLabels() {
            tvScale.text = "字号：%.1fx".format(sbScale.progress / 100f + 0.5f)
            tvSpeed.text = "速度：%.1fx".format(sbSpeed.progress / 100f + 0.5f)
            tvAlpha.text = "不透明度：${((sbAlpha.progress + 30) * 100 / 255)}%"
        }

        sbScale.progress = ((DanmakuSettings.textScale - 0.5f) * 100).toInt().coerceIn(0, 150)
        sbSpeed.progress = ((DanmakuSettings.speedScale - 0.5f) * 100).toInt().coerceIn(0, 150)
        sbAlpha.progress = (DanmakuSettings.alpha - 30).coerceIn(0, 225)
        refreshLabels()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbScale.setOnSeekBarChangeListener(listener)
        sbSpeed.setOnSeekBarChangeListener(listener)
        sbAlpha.setOnSeekBarChangeListener(listener)

        container.addView(tvScale)
        container.addView(sbScale)
        container.addView(tvSpeed)
        container.addView(sbSpeed)
        container.addView(tvAlpha)
        container.addView(sbAlpha)
        container.addView(etBlock)

        AlertDialog.Builder(this)
            .setTitle("弹幕设置")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                DanmakuSettings.textScale = sbScale.progress / 100f + 0.5f
                DanmakuSettings.speedScale = sbSpeed.progress / 100f + 0.5f
                DanmakuSettings.alpha = sbAlpha.progress + 30
                DanmakuSettings.blockWords = etBlock.text.toString()
                    .split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                toast("弹幕设置已保存")
            }
            .show()
    }
}
