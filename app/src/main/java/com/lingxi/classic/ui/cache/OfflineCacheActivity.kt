package com.lingxi.classic.ui.cache

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lingxi.classic.R
import com.lingxi.classic.player.CacheStorage
import com.lingxi.classic.player.OfflineCacheManager
import com.lingxi.classic.player.OfflineCacheManager.CachedMedia
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的缓存」：只读 OfflineCacheManager 的现有索引，视频/音频分组显示。
 * 点条目走只播本地分支（不请求任何接口）；删除即删文件 + 删索引，删完立即重读列表。
 */
class OfflineCacheActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvClear: TextView
    private lateinit var container: LinearLayout

    private var entries: List<CachedMedia> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页删除/清空后回来，按索引重读，保证列表与文件一致
        refresh()
    }

    // ==================== 界面 ====================

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.lx_bg))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(color(R.color.lx_text))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "我的缓存"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text))
        }
        tvClear = TextView(this).apply {
            text = "清空"
            textSize = 13f
            setTextColor(color(R.color.lx_red))
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { confirmClearAll() }
        }
        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        bar.addView(tvClear)

        tvSummary = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.lx_text_sub))
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        scroll.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(bar)
        root.addView(tvSummary)
        root.addView(scroll)
        return root
    }

    private fun refresh() {
        entries = OfflineCacheManager.list(this)
            // HLS 清单文件不是可离线播放的整文件，不进列表
            .filter { !it.fileName.endsWith(".m3u8", ignoreCase = true) }

        val total = entries.sumOf { it.size }
        tvSummary.text = if (entries.isEmpty()) {
            "共 0 个"
        } else {
            "共 ${entries.size} 个 · ${OfflineCacheManager.formatSize(total)}" +
                "（视频 ${countOf(OfflineCacheManager.KIND_VIDEO)} · 音频 ${countOf(OfflineCacheManager.KIND_AUDIO)}）"
        }
        tvClear.isEnabled = entries.isNotEmpty()
        tvClear.alpha = if (entries.isEmpty()) 0.4f else 1f

        container.removeAllViews()
        if (entries.isEmpty()) {
            addEmptyState()
            return
        }
        addGroup("视频", OfflineCacheManager.KIND_VIDEO)
        addGroup("音频", OfflineCacheManager.KIND_AUDIO)
    }

    private fun countOf(kind: String): Int = entries.count { it.kind == kind }

    /** 空状态两种：真正的空 / 缓存位置选的是 SD 卡但当前读不到 */
    private fun addEmptyState() {
        val sdUnavailable = CacheStorage.isExternalTarget(this) && !CacheStorage.isExternalAvailable(this)
        if (sdUnavailable) {
            addEmptyText(
                "缓存目录不可用",
                "当前缓存位置为 SD 卡，但没有检测到可用的存储卡。\n" +
                    "插好存储卡后重新进入本页，或到「设置 → 缓存存储位置」切回内部存储。"
            )
        } else {
            addEmptyText("还没有缓存", "在视频或音频播放页点「缓存」，就能在这里离线播放")
        }
    }

    private fun addEmptyText(title: String, hint: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(72), dp(24), dp(8))
        })
        container.addView(TextView(this).apply {
            text = hint
            textSize = 13f
            setTextColor(color(R.color.lx_text_sub))
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        })
    }

    private fun addGroup(label: String, kind: String) {
        val group = entries.filter { it.kind == kind }
        if (group.isEmpty()) return
        container.addView(TextView(this).apply {
            text = "$label（${group.size}）"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text_sub))
            setPadding(dp(14), dp(12), dp(14), dp(6))
        })
        group.forEach { addRow(it) }
    }

    private fun addRow(e: CachedMedia) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { openLocal(e) }
        }

        val badge = TextView(this).apply {
            text = if (e.kind == OfflineCacheManager.KIND_AUDIO) "音频" else "视频"
            textSize = 11f
            setTextColor(color(R.color.accent))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setBackgroundColor(color(R.color.surface))
        }

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = e.title.ifBlank { "未命名" }
            textSize = 15f
            setTextColor(color(R.color.lx_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        // 完整性：索引里记了 size，和本地文件实际长度对一下
        val file = OfflineCacheManager.fileFor(this, e)
        val complete = file.exists() && e.size > 0 && file.length() == e.size
        val status = when {
            !file.exists() -> "文件缺失"
            !complete -> "文件不完整"
            else -> "已缓存"
        }
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.createdAt))
        info.addView(TextView(this).apply {
            text = "${OfflineCacheManager.formatSize(e.size)} · $date · $status"
            textSize = 12f
            setTextColor(color(if (complete) R.color.lx_text_sub else R.color.lx_red))
            setPadding(0, dp(2), 0, 0)
        })

        val btnDelete = TextView(this).apply {
            text = "删除"
            textSize = 12f
            setTextColor(color(R.color.lx_red))
            setPadding(dp(12), dp(8), dp(6), dp(8))
            setOnClickListener { confirmDelete(e) }
        }

        row.addView(badge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnDelete)

        container.addView(row)
        container.addView(View(this).apply {
            setBackgroundColor(color(R.color.stroke))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            }
        })
    }

    // ==================== 操作 ====================

    private fun openLocal(e: CachedMedia) {
        if (!OfflineCacheManager.fileFor(this, e).exists()) {
            refresh()
            toast("本地文件不存在，已从列表移除")
            return
        }
        Navigator.openLocal(this, e.videoId, e.kind)
    }

    private fun confirmDelete(e: CachedMedia) {
        AlertDialog.Builder(this)
            .setTitle("删除缓存")
            .setMessage("删除「${e.title.ifBlank { "未命名" }}」的本地文件？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                // 文件与索引一起删，删完立刻重读索引刷新列表
                OfflineCacheManager.delete(this, e)
                refresh()
                toast("已删除")
            }
            .show()
    }

    private fun confirmClearAll() {
        if (entries.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空缓存")
            .setMessage("删除全部 ${entries.size} 个离线缓存文件？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                OfflineCacheManager.clearAll(this)
                refresh()
                toast("缓存已清空")
            }
            .show()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int = value.dp(this)
}
