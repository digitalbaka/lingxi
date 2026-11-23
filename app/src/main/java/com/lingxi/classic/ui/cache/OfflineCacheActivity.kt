package com.lingxi.classic.ui.cache

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.player.CacheStorage
import com.lingxi.classic.player.OfflineCacheManager
import com.lingxi.classic.player.OfflineCacheManager.CachedMedia
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的缓存」：上方是本地播放器（只读缓存文件，不发起任何网络请求），
 * 下方是已缓存的视频列表；点击条目在上方直接切换播放，菜单里可全屏播放/删除。
 *
 * 列表信息来自 OfflineCacheManager 的索引：旧索引没有 UP 主/时长字段，
 * 时长会回读本地文件元数据补齐，UP 主未知时该行隐藏。
 */
class OfflineCacheActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvClear: TextView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var tvPlayerHint: TextView
    private lateinit var btnFullscreen: TextView
    private lateinit var rv: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private lateinit var toolbarRow: View
    private lateinit var headerRow: View
    private lateinit var contentBox: FrameLayout

    /** 就地全屏层：透明覆盖层，只承载右上角开关与空白处退出点击；画面由预览那台播放器撑满 */
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var btnLoop: TextView
    private lateinit var btnPlayPause: TextView
    private var fullscreen = false
    /** 单条循环：只作用于全屏里的这一条，退出全屏/换条即复位，不持久化 */
    private var loopOne = false

    private var entries: List<CachedMedia> = emptyList()

    private var player: ExoPlayer? = null
    private var currentKey: String? = null
    private var cachedAdapter: CachedAdapter? = null

    /** key → 时长秒数（0 表示正在读取或读不出），供旧索引补齐时长角标 */
    private val durationCache = HashMap<String, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        // 平板常以横屏启动：首帧就按方向算预览区尺寸（横屏 60% 屏高、16:9 居中），
        // 不沿用 buildPlayer 里按竖屏整宽算出的高度
        applyPlayerLayout()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页删除/清空后回来，按索引重读，保证列表与文件一致
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // 本页没有后台播放：退到后台就暂停，避免视频在无通知/无控制条的状态下继续出声
        player?.pause()
    }

    /**
     * 旋转不再重建页面（Manifest 已加 configChanges）：
     * 预览区竖屏 16:9、横屏限高六成；全屏时按方向重判缩放模式，避免非 16:9 素材被裁
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 全屏时保持撑满，预览时才按新方向重算；兄弟的 GONE 状态不在转屏里回填
        applyPlayerLayout()
        // 只处理全屏的缩放模式，预览态恒为 FIT，不在转屏里改动
        if (fullscreen) applyFullscreenResizeMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerView.player = null
        player?.release()
        player = null
    }

    // ==================== 界面 ====================

    private fun buildRoot(): View {
        // 外层用 FrameLayout，方便把全屏播放层直接叠在原页面上（不改布局文件）
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.lx_bg))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.lx_bg))
        }

        toolbarRow = buildToolbar()
        column.addView(toolbarRow)
        column.addView(buildPlayer())
        headerRow = buildHeader()
        column.addView(headerRow)

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentBox = content
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OfflineCacheActivity)
            setBackgroundColor(color(R.color.lx_bg))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        content.addView(rv)
        content.addView(emptyBox)
        column.addView(content)

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(buildFullscreenLayer())
        return root
    }

    private fun buildToolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(R.color.surface))
        setPadding(dp(12), dp(8), dp(12), dp(8))

        addView(TextView(this@OfflineCacheActivity).apply {
            text = "←"
            textSize = 20f
            setTextColor(color(R.color.lx_text))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { finish() }
        })
        addView(TextView(this@OfflineCacheActivity).apply {
            text = "离线缓存"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        tvClear = TextView(this@OfflineCacheActivity).apply {
            text = "清空"
            textSize = 13f
            setTextColor(color(R.color.lx_red))
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { confirmClearAll() }
        }
        addView(tvClear)
    }

    /** 上方播放器：固定 16:9，本地文件直读 */
    private fun buildPlayer(): View {
        val height = resources.displayMetrics.widthPixels * 9 / 16
        playerBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
        playerView = PlayerView(this).apply {
            setUseController(true)
            setControllerShowTimeoutMs(2500)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tvPlayerHint = TextView(this).apply {
            text = "点击下方缓存开始播放"
            textSize = 13f
            setTextColor(Color.parseColor("#B0FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        btnFullscreen = TextView(this).apply {
            text = "全屏"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(8)
                marginEnd = dp(8)
            }
            setOnClickListener { currentVideo()?.let { enterFullscreen(it) } }
        }
        playerBox.addView(playerView)
        playerBox.addView(tvPlayerHint)
        playerBox.addView(btnFullscreen)
        return playerBox
    }

    /**
     * 就地全屏覆盖层：透明底，只承载右上角的「播放/暂停」「单条循环」与空白处退出点击。
     * 画面由预览那台 PlayerView 自己撑满提供，本层不再持有第二台播放器。
     */
    private fun buildFullscreenLayer(): FrameLayout {
        val box = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 空白处只做退出全屏；按钮是子 View，触摸先被按钮消费，不会落到这里
            setOnClickListener { exitFullscreen() }
        }
        fullscreenBox = box
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { exitFullscreen() }
        })
        topBar.addView(TextView(this).apply {
            text = "本地播放"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnPlayPause = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(36)
            gravity = Gravity.CENTER
            setOnClickListener { togglePlayPause() }
        }
        topBar.addView(btnPlayPause)
        btnLoop = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(36)
            gravity = Gravity.CENTER
            setOnClickListener { toggleLoop() }
        }
        topBar.addView(btnLoop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        renderLoopBtn()
        renderPlayPauseBtn()

        box.addView(topBar)
        return box
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(8))
        tvSummary = TextView(this@OfflineCacheActivity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text))
        }
        addView(tvSummary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ==================== 列表 ====================

    private fun refresh() {
        entries = OfflineCacheManager.list(this)
            // HLS 清单文件不是可离线播放的整文件，不进列表
            .filter { !it.fileName.endsWith(".m3u8", ignoreCase = true) }

        val total = entries.sumOf { it.size }
        val videoCount = entries.count { it.kind == OfflineCacheManager.KIND_VIDEO }
        tvSummary.text = if (entries.isEmpty()) {
            "离线缓存（0 个）"
        } else {
            "离线缓存（${entries.size} 个） · ${OfflineCacheManager.formatSize(total)}" +
                (if (videoCount < entries.size) " · 视频 $videoCount" else "")
        }
        tvClear.isEnabled = entries.isNotEmpty()
        tvClear.alpha = if (entries.isEmpty()) 0.4f else 1f

        val hasVideo = videoCount > 0
        playerBox.visibility = if (hasVideo) View.VISIBLE else View.GONE

        if (entries.isEmpty()) {
            rv.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
            showEmptyState()
            return
        }
        rv.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        val ad = cachedAdapter
        if (ad == null) {
            cachedAdapter = CachedAdapter(entries).also { rv.adapter = it }
        } else {
            ad.submit(entries)
        }

        // 当前选中的条目被删掉/文件没了：停掉播放器，回到提示态
        val current = entries.firstOrNull { it.key == currentKey }
        if (current == null || current.kind != OfflineCacheManager.KIND_VIDEO) {
            resetPlayer()
            entries.firstOrNull { it.kind == OfflineCacheManager.KIND_VIDEO }?.let {
                prepareVideo(it, autoPlay = false)
            }
        }
    }

    /** 空状态两种：真正的空 / 缓存位置选的是 SD 卡但当前读不到 */
    private fun showEmptyState() {
        emptyBox.removeAllViews()
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
        emptyBox.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.lx_text))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(72), dp(24), dp(8))
        })
        emptyBox.addView(TextView(this).apply {
            text = hint
            textSize = 13f
            setTextColor(color(R.color.lx_text_sub))
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        })
    }

    // ==================== 播放器 ====================

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    toast("播放失败：本地文件可能已损坏")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    renderPlayPauseBtn()
                }
            })
        }
        playerView.player = p
        player = p
        return p
    }

    private fun prepareVideo(e: CachedMedia, autoPlay: Boolean) {
        val file = OfflineCacheManager.fileFor(this, e)
        if (!file.exists()) {
            refresh()
            toast("本地文件不存在，已从列表移除")
            return
        }
        val p = ensurePlayer()
        if (currentKey != e.key) {
            currentKey = e.key
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            cachedAdapter?.notifyDataSetChanged()
        }
        p.playWhenReady = autoPlay

        playerView.visibility = View.VISIBLE
        tvPlayerHint.visibility = View.GONE
        btnFullscreen.visibility = View.VISIBLE
    }

    private fun resetPlayer() {
        if (fullscreen) exitFullscreen()
        currentKey = null
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        playerView.visibility = View.GONE
        btnFullscreen.visibility = View.GONE
        tvPlayerHint.visibility = View.VISIBLE
    }

    private fun currentVideo(): CachedMedia? =
        entries.firstOrNull { it.key == currentKey && it.kind == OfflineCacheManager.KIND_VIDEO }

    /**
     * 视频条目：就地全屏，把预览那台播放器的容器撑满整屏。
     * 播放器与 surface 全程不摘，不发任何网络请求，也不打开在线详情页/文章页。
     */
    private fun enterFullscreen(e: CachedMedia) {
        if (e.kind != OfflineCacheManager.KIND_VIDEO) return
        val file = OfflineCacheManager.fileFor(this, e)
        if (!file.exists()) {
            refresh()
            toast("本地文件不存在，已从列表移除")
            return
        }
        // 每条进来都从默认关闭开始，不继承上一条的循环状态
        loopOne = false
        prepareVideo(e, autoPlay = true)
        // 重复调用时也保持全屏外观：预览角标的「全屏」按钮不能再露出来
        btnFullscreen.visibility = View.GONE
        // 先关循环再判断是否已全屏：重复调用时按钮状态与实际循环保持一致
        player?.repeatMode = Player.REPEAT_MODE_OFF
        renderLoopBtn()
        if (fullscreen) return
        fullscreen = true
        // 预览容器撑满：竖屏按填充铺满、横屏按原比例；关掉自带控制器，避免全屏浮出进度条等控件
        applyFullscreenResizeMode()
        playerView.setUseController(false)
        toolbarRow.visibility = View.GONE
        headerRow.visibility = View.GONE
        contentBox.visibility = View.GONE
        applyPlayerLayout()
        renderPlayPauseBtn()
        fullscreenBox.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        // 退出播放层回到默认关闭，不持久化
        loopOne = false
        player?.repeatMode = Player.REPEAT_MODE_OFF
        renderLoopBtn()
        fullscreenBox.visibility = View.GONE
        // 先还原预览尺寸、缩放与控制器，再恢复同列兄弟；播放器不摘、不 seek、不重刷
        playerView.setUseController(true)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        applyPlayerLayout()
        btnFullscreen.visibility = View.VISIBLE
        toolbarRow.visibility = View.VISIBLE
        headerRow.visibility = View.VISIBLE
        contentBox.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    /** 预览竖屏 16:9 满宽 / 横屏按画面尺寸（高不超屏高六成、水平居中）；全屏撑满 */
    private fun applyPlayerLayout() {
        playerBox.layoutParams = if (fullscreen) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            val dm = resources.displayMetrics
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // 横屏：容器按 16:9 画面尺寸算（最高六成屏高），水平居中，避免大容器里套小画面留大黑边
                val h = (dm.heightPixels * 0.6f).toInt()
                val w = minOf(dm.widthPixels, h * 16 / 9)
                LinearLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER_HORIZONTAL }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dm.widthPixels * 9 / 16
                )
            }
        }
        playerBox.requestLayout()
    }

    /** 全屏缩放：竖屏按填充铺满（ZOOM），横屏保持原比例（FIT），非 16:9 素材横屏不被裁 */
    private fun applyFullscreenResizeMode() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        playerView.resizeMode = if (landscape) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    /** 全屏期只做播放/暂停切换，不加进度条 */
    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        renderPlayPauseBtn()
    }

    private fun renderPlayPauseBtn() {
        if (!::btnPlayPause.isInitialized) return
        btnPlayPause.text = if (player?.isPlaying == true) "暂停" else "播放"
    }

    /** 单条循环：只作用于当前这一条，播完从 0 重播；关闭后播完停在结尾 */
    private fun toggleLoop() {
        loopOne = !loopOne
        player?.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        renderLoopBtn()
    }

    private fun renderLoopBtn() {
        btnLoop.text = if (loopOne) "单条循环：开" else "单条循环：关"
        btnLoop.setTextColor(if (loopOne) color(R.color.lx_green_dark) else Color.WHITE)
        btnLoop.setBackgroundColor(
            if (loopOne) Color.parseColor("#CCFFFFFF") else Color.parseColor("#66000000")
        )
    }

    override fun onBackPressed() {
        // 全屏时不退出页面，只退回列表
        if (fullscreen) {
            exitFullscreen()
            return
        }
        super.onBackPressed()
    }

    // ==================== 条目操作 ====================

    private fun onItemClick(e: CachedMedia) {
        if (e.kind != OfflineCacheManager.KIND_VIDEO) {
            // 音频缓存交给文章页的只播本地分支，本页不内嵌音频播放器
            if (!OfflineCacheManager.fileFor(this, e).exists()) {
                refresh()
                toast("本地文件不存在，已从列表移除")
                return
            }
            Navigator.openLocal(this, e.videoId, e.kind)
            return
        }
        val p = player
        if (p != null && currentKey == e.key) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        prepareVideo(e, autoPlay = true)
    }

    private fun showItemMenu(e: CachedMedia) {
        val isVideo = e.kind == OfflineCacheManager.KIND_VIDEO
        val actions = if (isVideo) {
            arrayOf("全屏播放", "打开音频", "删除缓存")
        } else {
            arrayOf("打开音频", "删除缓存")
        }
        AlertDialog.Builder(this)
            .setTitle(e.title.ifBlank { "未命名" })
            .setItems(actions) { _, which ->
                when {
                    isVideo && which == 0 -> enterFullscreen(e)
                    isVideo && which == 1 -> openAudioOnly(e)
                    isVideo -> confirmDelete(e)
                    which == 0 -> openAudioOnly(e)
                    else -> confirmDelete(e)
                }
            }
            .show()
    }

    /** 只播这条本地文件的声音：视频容器也交给文章页的本地播放器，只出声不显画面 */
    private fun openAudioOnly(e: CachedMedia) {
        if (!OfflineCacheManager.fileFor(this, e).exists()) {
            refresh()
            toast("本地文件不存在，已从列表移除")
            return
        }
        Navigator.openLocalAudio(this, e.videoId)
    }

    private fun confirmDelete(e: CachedMedia) {
        AlertDialog.Builder(this)
            .setTitle("删除缓存")
            .setMessage("删除「${e.title.ifBlank { "未命名" }}」的本地文件？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (e.key == currentKey) resetPlayer()
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
                resetPlayer()
                OfflineCacheManager.clearAll(this)
                refresh()
                toast("缓存已清空")
            }
            .show()
    }

    // ==================== 适配器 ====================

    private inner class CachedAdapter(initial: List<CachedMedia>) :
        RecyclerView.Adapter<CachedAdapter.Holder>() {

        private var items: List<CachedMedia> = initial

        fun submit(newItems: List<CachedMedia>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(
            itemView: View,
            val row: LinearLayout,
            val ivCover: ImageView,
            val tvDuration: TextView,
            val tvTitle: TextView,
            val avUp: ImageView,
            val tvUp: TextView,
            val tvMeta: TextView,
            val btnMore: TextView
        ) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@OfflineCacheActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10))
                isClickable = true
            }

            val coverBox = FrameLayout(this@OfflineCacheActivity).apply {
                setBackgroundColor(color(R.color.surface))
            }
            val ivCover = ImageView(this@OfflineCacheActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val tvDuration = TextView(this@OfflineCacheActivity).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#66000000"))
                setPadding(dp(4), dp(1), dp(4), dp(1))
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            }
            coverBox.addView(ivCover)
            coverBox.addView(tvDuration)

            val info = LinearLayout(this@OfflineCacheActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val tvTitle = TextView(this@OfflineCacheActivity).apply {
                textSize = 15f
                setTextColor(color(R.color.lx_text))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val upRow = LinearLayout(this@OfflineCacheActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val avUp = ImageView(this@OfflineCacheActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            }
            val tvUp = TextView(this@OfflineCacheActivity).apply {
                textSize = 12f
                setTextColor(color(R.color.lx_text_sub))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, 0, 0)
            }
            upRow.addView(avUp)
            upRow.addView(tvUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val tvMeta = TextView(this@OfflineCacheActivity).apply {
                textSize = 11f
                setTextColor(color(R.color.lx_text_sub))
                setPadding(0, dp(3), 0, 0)
            }
            info.addView(tvTitle)
            info.addView(upRow)
            info.addView(tvMeta)

            val btnMore = TextView(this@OfflineCacheActivity).apply {
                text = "⋮"
                textSize = 18f
                setTextColor(color(R.color.lx_text_sub))
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }

            row.addView(coverBox, LinearLayout.LayoutParams(dp(120), dp(68)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            row.addView(btnMore)

            val container = LinearLayout(this@OfflineCacheActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            container.addView(View(this@OfflineCacheActivity).apply {
                setBackgroundColor(color(R.color.stroke))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
                ).apply {
                    marginStart = dp(14)
                    marginEnd = dp(14)
                }
            })

            return Holder(container, row, ivCover, tvDuration, tvTitle, avUp, tvUp, tvMeta, btnMore)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val selected = e.key == currentKey

            holder.row.setBackgroundColor(
                if (selected) color(R.color.surface) else Color.TRANSPARENT
            )
            holder.tvTitle.text = e.title.ifBlank { "未命名" }
            ImageLoader.load(holder.ivCover, ApiClient.fullUrl(e.cover))

            if (e.author.isBlank() && e.authorAvatar.isBlank()) {
                holder.avUp.visibility = View.GONE
                holder.tvUp.visibility = View.GONE
            } else {
                holder.avUp.visibility = View.VISIBLE
                holder.tvUp.visibility = View.VISIBLE
                if (e.authorAvatar.isBlank()) {
                    holder.avUp.visibility = View.GONE
                } else {
                    ImageLoader.load(holder.avUp, ApiClient.fullUrl(e.authorAvatar), circle = true)
                }
                holder.tvUp.text = e.author.ifBlank { "未知 UP 主" }
            }

            val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.createdAt))
            val file = OfflineCacheManager.fileFor(this@OfflineCacheActivity, e)
            val complete = file.exists() && e.size > 0 && file.length() == e.size
            val status = when {
                !file.exists() -> "文件缺失"
                !complete -> "文件不完整"
                else -> "已缓存"
            }
            val kindLabel = if (e.kind == OfflineCacheManager.KIND_AUDIO) "音频 · " else ""
            holder.tvMeta.text = "$kindLabel${OfflineCacheManager.formatSize(e.size)} · $date · $status"
            holder.tvMeta.setTextColor(color(if (complete) R.color.lx_text_sub else R.color.lx_red))

            bindDuration(holder.tvDuration, e)

            holder.row.setOnClickListener { onItemClick(e) }
            holder.btnMore.setOnClickListener { showItemMenu(e) }
        }
    }

    /** 时长角标：新索引直接有值；旧索引回读本地文件元数据，读一次缓存一次 */
    private fun bindDuration(tv: TextView, e: CachedMedia) {
        if (e.durationSec > 0) {
            tv.text = FormatUtils.formatDuration(e.durationSec)
            tv.visibility = View.VISIBLE
            return
        }
        val cached = durationCache[e.key]
        if (cached != null && cached > 0) {
            tv.text = FormatUtils.formatDuration(cached)
            tv.visibility = View.VISIBLE
            return
        }
        tv.visibility = View.GONE
        if (durationCache.containsKey(e.key)) return
        // 先占位，避免同一行反复触发读取
        durationCache[e.key] = 0L
        lifecycleScope.launch {
            val sec = withContext(Dispatchers.IO) {
                runCatching {
                    val f = OfflineCacheManager.fileFor(this@OfflineCacheActivity, e)
                    if (!f.exists()) return@runCatching 0L
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(f.absolutePath)
                        (retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L) / 1000L
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(0L)
            }
            durationCache[e.key] = sec
            if (sec > 0) cachedAdapter?.notifyDataSetChanged()
        }
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int = value.dp(this)
}
