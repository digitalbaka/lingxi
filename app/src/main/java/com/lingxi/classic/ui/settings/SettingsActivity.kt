package com.lingxi.classic.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lingxi.classic.BuildConfig
import com.lingxi.classic.R
import com.lingxi.classic.databinding.ActivitySettingsBinding
import com.lingxi.classic.player.ButtonSizeSettings
import com.lingxi.classic.player.DanmakuSettings
import com.lingxi.classic.player.VideoCacheManager
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast

/** 设置：视频缓存、弹幕参数、关于 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rowCacheLimit.setOnClickListener { showCacheLimitDialog() }
        binding.rowClearCache.setOnClickListener { confirmClearCache() }
        binding.rowDanmaku.setOnClickListener { showDanmakuDialog() }
        binding.rowButtonSize.setOnClickListener { showButtonSizeDialog() }

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.tvButtonSize.text = ButtonSizeSettings.label()
    }

    override fun onResume() {
        super.onResume()
        refreshCacheViews()
        binding.tvButtonSize.text = ButtonSizeSettings.label()
    }

    private fun refreshCacheViews() {
        binding.tvCacheLimit.text = "${VideoCacheManager.getCacheLimitMb(this)} MB"
        binding.tvCacheSize.text = FormatUtils.formatBytes(VideoCacheManager.getCacheSizeBytes(this))
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

    // ==================== 按钮大小 ====================

    private fun showButtonSizeDialog() {
        val current = ButtonSizeSettings.level
        AlertDialog.Builder(this)
            .setTitle("播放页按钮大小")
            .setSingleChoiceItems(ButtonSizeSettings.labels, current) { dialog, which ->
                ButtonSizeSettings.level = which
                binding.tvButtonSize.text = ButtonSizeSettings.label()
                toast("按钮大小已设为「${ButtonSizeSettings.label()}」")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
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
