package com.lingxi.classic.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.PlaybackHelper
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Danmaku
import com.lingxi.classic.data.model.DanmakuSendRequest
import com.lingxi.classic.data.model.HistoryReportRequest
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.ViewReportRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivityPlayerBinding
import com.lingxi.classic.databinding.DialogDanmakuSettingsBinding
import com.lingxi.classic.databinding.ItemPlayerRelatedBinding
import com.lingxi.classic.player.DanmakuSettings
import com.lingxi.classic.player.VideoCacheManager
import com.lingxi.classic.ui.common.CommentsController
import com.lingxi.classic.ui.common.InteractionBar
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.isTablet
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 视频播放页（kind=video）。
 * 手机：16:9 播放器 + 弹幕输入条 + NestedScrollView（信息/相关推荐/评论）；横屏为全屏。
 * 平板横屏：左列 16:9 播放器（限高）+ 右列弹幕/信息双列（参考哔哩哔哩 HD）；全屏时右列隐藏。
 * 平板竖屏：仍按单列（播放器在上、信息在下），不改变手机端与平板竖屏的既有外观。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var player: ExoPlayer? = null
    private var video: Video? = null
    private var videoId: Long = 0
    private var playUrl: String = ""
    private var currentQualityUrl: String? = null
    private var retriedAfterRecover = false
    private var resumePosition: Long = 0
    private var following = false
    private var descExpanded = false
    private var isFullscreen = false
    /** 平板上全屏只是布局状态，不绑定横竖屏（平板横屏打开视频不应被强制全屏） */
    private var tabletFullscreen = false

    private var viewReported = false
    private var viewReportJob: Job? = null
    private var historyJob: Job? = null
    private var viewersJob: Job? = null

    private var commentsController: CommentsController? = null
    private var interactionBar: InteractionBar? = null
    private val relatedAdapter = RelatedAdapter()

    private val handler = Handler(Looper.getMainLooper())
    private val danmakuTicker = object : Runnable {
        override fun run() {
            player?.let { binding.danmakuView.updatePosition(it.currentPosition) }
            handler.postDelayed(this, 200)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.danmakuView.setPlaying(isPlaying)
            if (isPlaying) armViewReport()
        }

        override fun onPlaybackStateChanged(state: Int) {
            binding.loadingBar.visible = state == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlayError(error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 首帧把焦点收回根容器：否则框架会把焦点自动给第一个可聚焦控件（弹幕输入框），
        // 导致一进播放页就弹出输入法
        binding.rootLayout.post {
            binding.rootLayout.requestFocus()
            hideKeyboard()
        }

        videoId = intent.getLongExtra(Navigator.EXTRA_VIDEO_ID, 0)
        if (videoId <= 0) {
            toast("视频不存在")
            finish()
            return
        }
        initViews()
        loadVideo(videoId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra(Navigator.EXTRA_VIDEO_ID, 0)
        if (id > 0 && id != videoId) loadVideo(id)
    }

    private fun initViews() {
        // 进页不抢焦点：焦点先落在根容器，键盘只在用户主动点弹幕输入框时出现
        binding.rootLayout.requestFocus()
        binding.rootLayout.post { hideKeyboard() }

        binding.btnBack.setOnClickListener { onBack() }
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnQualityFs.setOnClickListener {
            video?.let { v -> if (v.qualityOptions.size > 1) showQualityDialog(v) else toast("该视频只有一个清晰度") }
        }
        binding.playerContainer.post {
            applyOrientationLayout(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        }

        binding.btnSendDanmaku.setOnClickListener { sendDanmaku() }
        binding.btnDanmakuToggle.setOnClickListener {
            val enabled = !binding.danmakuView.isDanmakuEnabled()
            binding.danmakuView.setDanmakuEnabled(enabled)
            binding.btnDanmakuToggle.text = if (enabled) "弹幕开" else "弹幕关"
        }
        binding.btnDanmakuSettings.setOnClickListener { showDanmakuSettings() }

        binding.rvRelated.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRelated.adapter = relatedAdapter

        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = binding.scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.bottom - (binding.scrollView.height + scrollY) < 800) {
                commentsController?.maybeLoadMore()
            }
        }

        interactionBar = InteractionBar(this, binding.interactionBar.root)
    }

    // ==================== 加载与绑定 ====================

    private fun loadVideo(id: Long) {
        videoId = id
        stopJobs()
        viewReported = false
        retriedAfterRecover = false
        currentQualityUrl = null
        resumePosition = 0
        releasePlayer()
        binding.danmakuView.setDanmaku(emptyList())
        binding.loadingBar.visible = true
        // 关注状态由详情接口的 following 决定，拿到前不显示 XML 默认的「关注」
        following = false
        binding.btnFollow.visible = false

        lifecycleScope.launch {
            try {
                val detail = ApiClient.apiService.getVideoDetail(id).unwrap(this@PlayerActivity)
                val v = detail?.video
                if (v == null) {
                    toast("视频加载失败")
                    finish()
                    return@launch
                }
                if (!v.isVideo) {
                    Navigator.openVideo(this@PlayerActivity, v.id, v.kind)
                    finish()
                    return@launch
                }
                video = v
                bindDetail(v, detail.following)
                interactionBar?.bind(v)

                val progressMs = parseProgressMs(detail.progress)
                startPlayback(v, if (progressMs > 5000) progressMs else 0)

                loadDanmaku(id)
                loadRelated(id)
                startViewersPolling(id)
                startHistoryReporting(id)

                commentsController = CommentsController(
                    id, v.up?.id ?: v.uid, binding.rvComments, binding.commentInput.root, this@PlayerActivity
                )
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            } finally {
                binding.loadingBar.visible = false
            }
        }
    }

    /** progress 登录时是对象，游客可能是数字，解析前判断类型 */
    private fun parseProgressMs(progress: Any?): Long {
        val map = progress as? Map<*, *> ?: return 0
        return (map["progressMs"] as? Number)?.toLong() ?: 0
    }

    /** 单独重绘 meta 行（播放量 / 弹幕数 / 日期），供弹幕发送后局部刷新用，不碰播放器 */
    private fun renderMeta() {
        val v = video ?: return
        val date = FormatUtils.formatDate(if (v.publishedAt > 0) v.publishedAt else v.createdAt)
        binding.tvMeta.text = "${FormatUtils.formatCount(v.viewCount)} 播放 · " +
            "${FormatUtils.formatCount(v.danmakuCount)} 弹幕 · $date"
    }

    private fun bindDetail(v: Video, followingUp: Boolean) {
        binding.tvTitle.text = v.title
        binding.tvTopTitle.text = v.title
        renderMeta()

        following = followingUp
        val upUid = v.up?.id ?: v.uid
        binding.tvUpName.text = v.up.displayName("UP主")
        ImageLoader.load(binding.ivUpAvatar, v.up?.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
        val openSpace = { if (upUid > 0) Navigator.openSpace(this, upUid) }
        binding.ivUpAvatar.setOnClickListener { openSpace() }
        binding.tvUpName.setOnClickListener { openSpace() }
        binding.btnFollow.visible = upUid > 0 && upUid != (AuthManager.user?.id ?: 0L)
        renderFollow()
        binding.btnFollow.setOnClickListener { doFollow(upUid) }

        if (v.description.isNotBlank()) {
            descExpanded = false
            binding.tvDesc.visible = true
            binding.tvDesc.text = v.description
            binding.tvDesc.maxLines = 2
            binding.tvDesc.setOnClickListener { toggleDesc() }
            binding.tvDescToggle.text = "展开"
            binding.tvDescToggle.setOnClickListener { toggleDesc() }
            // 布局完成后再判断第二行是否被省略号截断，短简介不显示「展开」
            binding.tvDesc.post { updateDescToggle() }
        } else {
            binding.tvDesc.visible = false
            binding.tvDescToggle.visible = false
        }

        when {
            v.qualityBusy -> {
                binding.tvQuality.visible = true
                binding.tvQuality.text = "转码中，暂只能播放默认清晰度"
                binding.tvQuality.setOnClickListener(null)
            }
            v.qualityOptions.size > 1 -> {
                binding.tvQuality.visible = true
                binding.tvQuality.text = "清晰度：默认"
                binding.tvQuality.setOnClickListener { showQualityDialog(v) }
            }
            else -> binding.tvQuality.visible = false
        }
        // 视频加载完成后同步刷新全屏顶栏清晰度入口（先横屏后进详情的情况）
        binding.btnQualityFs.visible = isFullscreen && v.qualityOptions.size > 1
    }

    /** 简介展开/收起：仅切换两行截断的展示，不改其他信息区 */
    private fun toggleDesc() {
        descExpanded = !descExpanded
        binding.tvDesc.maxLines = if (descExpanded) Int.MAX_VALUE else 2
        binding.tvDesc.post { updateDescToggle() }
    }

    /** 「展开/收起」只在简介确实被截断时出现；展开状态始终保留可收起入口 */
    private fun updateDescToggle() {
        if (binding.tvDesc.visibility != View.VISIBLE) {
            binding.tvDescToggle.visible = false
            return
        }
        if (descExpanded) {
            binding.tvDescToggle.text = "收起"
            binding.tvDescToggle.visible = true
            return
        }
        val layout = binding.tvDesc.layout
        binding.tvDescToggle.text = "展开"
        binding.tvDescToggle.visible = layout != null && layout.lineCount > 0 &&
            layout.getEllipsisCount(layout.lineCount - 1) > 0
    }

    // ==================== 播放 ====================

    private fun startPlayback(v: Video, resumeMs: Long = 0) {
        releasePlayer()
        lifecycleScope.launch {
            try {
                val ps = PlaybackHelper.buildPlaySource(this@PlayerActivity, v, currentQualityUrl)
                playUrl = ps.url
                val factory = VideoCacheManager.cacheDataSourceFactory(this@PlayerActivity, ps.headers)
                val item = MediaItem.fromUri(ps.url)
                val source = if (ps.url.contains(".m3u8"))
                    HlsMediaSource.Factory(factory).createMediaSource(item)
                else
                    ProgressiveMediaSource.Factory(factory).createMediaSource(item)

                val p = ExoPlayer.Builder(this@PlayerActivity)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                            .build()
                    )
                    .build()
                binding.playerView.player = p
                p.setMediaSource(source)
                if (resumeMs > 0) p.seekTo(resumeMs)
                p.prepare()
                p.playWhenReady = true
                p.addListener(playerListener)
                player = p

                if (resumeMs > 0) {
                    binding.danmakuView.seekTo(resumeMs)
                    toast("上次看到 ${FormatUtils.formatDurationMs(resumeMs)}")
                }
                handler.removeCallbacks(danmakuTicker)
                handler.post(danmakuTicker)
            } catch (e: Exception) {
                toast("播放失败：${e.message}")
            }
        }
    }

    /** 播放失败：清毒缓存 + 重新节点授权后重试一次，仍失败则报错 */
    private fun onPlayError(error: PlaybackException) {
        binding.loadingBar.visible = false
        val url = playUrl
        if (!retriedAfterRecover) {
            retriedAfterRecover = true
            val pos = player?.currentPosition ?: 0
            lifecycleScope.launch {
                val retry = try {
                    PlaybackHelper.recoverFromPlaybackError(this@PlayerActivity, url)
                } catch (e: Exception) {
                    false
                }
                if (retry) {
                    toast("节点连接失败，正在重新授权…")
                    video?.let { startPlayback(it, pos) }
                } else {
                    toast("播放失败：${error.localizedMessage ?: "未知错误"}")
                }
            }
        } else {
            toast("播放失败：${error.localizedMessage ?: "未知错误"}")
        }
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
        binding.danmakuView.setPlaying(false)
    }

    // ==================== 弹幕 ====================

    private fun loadDanmaku(id: Long) {
        lifecycleScope.launch {
            try {
                val list = ApiClient.apiService.getDanmaku(id, all = 1).unwrap()
                list?.let { binding.danmakuView.setDanmaku(it.items) }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendDanmaku() {
        if (!AuthManager.isLoggedIn) {
            toast("请先登录")
            return
        }
        val content = binding.etDanmaku.text.toString().trim()
        if (content.isEmpty()) return
        val pos = player?.currentPosition ?: 0
        val id = videoId
        lifecycleScope.launch {
            try {
                ApiClient.apiService.sendDanmaku(DanmakuSendRequest(id, content, pos))
                    .unwrap(this@PlayerActivity) ?: return@launch
                binding.etDanmaku.setText("")
                hideKeyboard()
                // 发送成功立即上屏，并只重绘 meta 的弹幕数（不重建播放器、不 seek）
                binding.danmakuView.appendLive(Danmaku(content = content, timeMs = pos, mode = Danmaku.MODE_SCROLL))
                video = video?.copy(danmakuCount = (video?.danmakuCount ?: 0) + 1)
                renderMeta()
            } catch (e: Exception) {
                toast("发送失败：${e.message}")
            }
        }
    }

    private fun showDanmakuSettings() {
        val db = DialogDanmakuSettingsBinding.inflate(layoutInflater)
        db.seekTextScale.progress = ((DanmakuSettings.textScale - 0.5f) * 100).toInt()
        db.seekSpeed.progress = ((DanmakuSettings.speedScale - 0.5f) * 100).toInt()
        db.seekAlpha.progress = (DanmakuSettings.alpha - 30) * 100 / 225
        db.etBlockWords.setText(DanmakuSettings.blockWords.joinToString(","))
        val dialog = AlertDialog.Builder(this).setView(db.root).create()
        db.btnSave.setOnClickListener {
            DanmakuSettings.textScale = 0.5f + db.seekTextScale.progress / 100f
            DanmakuSettings.speedScale = 0.5f + db.seekSpeed.progress / 100f
            DanmakuSettings.alpha = 30 + db.seekAlpha.progress * 225 / 100
            DanmakuSettings.blockWords = db.etBlockWords.text.toString()
                .split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            binding.danmakuView.applySettings()
            dialog.dismiss()
        }
        dialog.show()
    }

    // ==================== 上报 ====================

    /** 播放 8 秒后上报一次播放量（仅一次） */
    private fun armViewReport() {
        if (viewReported || viewReportJob != null) return
        val id = videoId
        viewReportJob = lifecycleScope.launch {
            delay(8_000)
            try {
                val p = player
                ApiClient.apiService.reportView(
                    id, ViewReportRequest(p?.currentPosition ?: 0, (p?.duration ?: 0).coerceAtLeast(0))
                )
            } catch (_: Exception) {
            }
            viewReported = true
        }
    }

    private fun startHistoryReporting(id: Long) {
        historyJob?.cancel()
        if (!AuthManager.isLoggedIn) return
        historyJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                val p = player ?: continue
                val dur = p.duration
                if (dur > 0) {
                    try {
                        ApiClient.apiService.reportHistory(HistoryReportRequest(id, p.currentPosition, dur))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun startViewersPolling(id: Long) {
        viewersJob?.cancel()
        viewersJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val r = ApiClient.apiService.getViewers(id).unwrap()
                    if (r != null && r.count > 0) {
                        binding.tvViewers.visible = true
                        binding.tvViewers.text = "${r.count} 人在看"
                    } else {
                        binding.tvViewers.visible = false
                    }
                } catch (_: Exception) {
                }
                delay(10_000)
            }
        }
    }

    private fun stopJobs() {
        viewReportJob?.cancel()
        viewReportJob = null
        historyJob?.cancel()
        historyJob = null
        viewersJob?.cancel()
        viewersJob = null
    }

    // ==================== 关注 / 画质 / 相关 ====================

    private fun renderFollow() {
        binding.btnFollow.text = if (following) "已关注" else "关注"
    }

    private fun doFollow(upUid: Long) {
        if (!AuthManager.isLoggedIn) {
            toast("请先登录")
            return
        }
        lifecycleScope.launch {
            try {
                val r = ApiClient.apiService.followUser(upUid).unwrap(this@PlayerActivity) ?: return@launch
                following = r.following
                renderFollow()
            } catch (e: Exception) {
                toast("操作失败：${e.message}")
            }
        }
    }

    private fun showQualityDialog(v: Video) {
        val opts = v.qualityOptions
        if (opts.isEmpty()) return
        val labels = opts.map { it.label }.toTypedArray()
        val current = opts.indexOfFirst { it.url == currentQualityUrl }
        AlertDialog.Builder(this)
            .setTitle("选择清晰度")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val q = opts[which]
                currentQualityUrl = q.url
                binding.tvQuality.text = "清晰度：${q.label}"
                val pos = player?.currentPosition ?: 0
                startPlayback(v, pos)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadRelated(id: Long) {
        lifecycleScope.launch {
            try {
                val items = ApiClient.apiService.getRelatedVideos(id).unwrap()?.items.orEmpty()
                binding.tvRelatedTitle.visible = items.isNotEmpty()
                binding.rvRelated.visible = items.isNotEmpty()
                relatedAdapter.submit(items)
            } catch (_: Exception) {
            }
        }
    }

    // ==================== 全屏 ====================

    private fun toggleFullscreen() {
        if (isTablet()) {
            // 平板：不转屏，仅切换播放器铺满窗口/恢复 16:9 嵌入两种布局
            tabletFullscreen = !tabletFullscreen
            applyOrientationLayout(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        } else {
            requestedOrientation = if (isFullscreen)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyOrientationLayout(landscape: Boolean) {
        val fullscreen = if (isTablet()) tabletFullscreen else landscape
        isFullscreen = fullscreen

        val dm = resources.displayMetrics
        val gapPx = 16.dp(this)
        val minRightPx = 320.dp(this)
        // 平板横屏嵌入态 = 双列；平板竖屏与手机保持单列
        val dualColumn = isTablet() && landscape && !fullscreen

        binding.rootLayout.orientation = if (dualColumn) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val columnLp = binding.playerColumn.layoutParams as LinearLayout.LayoutParams
        val infoLp = binding.infoColumn.layoutParams as LinearLayout.LayoutParams
        val playerLp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams

        when {
            fullscreen -> {
                columnLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                columnLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                columnLp.weight = 0f
                columnLp.marginEnd = 0
                infoLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                infoLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                infoLp.weight = 0f
                infoLp.marginStart = 0
                playerLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                playerLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                playerLp.gravity = Gravity.NO_GRAVITY
            }
            dualColumn -> {
                // 左列按 16:9 自然宽度，同时满足：高度不超过屏高 60%、右列不少于 320dp
                val widthByHeight = (dm.heightPixels * 0.60f * 16 / 9).toInt()
                val widthByRightColumn = (dm.widthPixels - gapPx - minRightPx).coerceAtLeast(dm.widthPixels / 2)
                val playerW = minOf(widthByHeight, widthByRightColumn)
                val playerH = playerW * 9 / 16

                columnLp.width = playerW
                columnLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                columnLp.weight = 0f
                columnLp.marginEnd = gapPx
                infoLp.width = 0
                infoLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                infoLp.weight = 1f
                infoLp.marginStart = 0
                playerLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                playerLp.height = playerH
                playerLp.gravity = Gravity.CENTER
            }
            else -> {
                var w = ViewGroup.LayoutParams.MATCH_PARENT
                var h = dm.widthPixels * 9 / 16
                if (isTablet()) {
                    // 平板竖屏：16:9 也不会超过屏高，保留原有兜底约束
                    val maxH = dm.heightPixels * 3 / 4
                    if (h > maxH) {
                        h = maxH
                        w = maxH * 16 / 9
                    }
                }
                columnLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                columnLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnLp.weight = 0f
                columnLp.marginEnd = 0
                infoLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                infoLp.height = 0
                infoLp.weight = 1f
                infoLp.marginStart = 0
                playerLp.width = w
                playerLp.height = h
                playerLp.gravity = if (w == ViewGroup.LayoutParams.MATCH_PARENT)
                    Gravity.NO_GRAVITY else Gravity.CENTER_HORIZONTAL
            }
        }
        binding.playerColumn.layoutParams = columnLp
        binding.infoColumn.layoutParams = infoLp
        binding.playerContainer.layoutParams = playerLp

        binding.infoColumn.visible = !fullscreen
        binding.danmakuBar.visible = !fullscreen
        binding.scrollView.visible = !fullscreen
        binding.btnFullscreen.text = if (fullscreen) "退出" else "全屏"
        // 全屏时在顶栏提供清晰度切换入口（竖屏入口在简介区）
        binding.btnQualityFs.visible = fullscreen && (video?.qualityOptions?.size ?: 0) > 1
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // 点画面唤出媒体控制栏时，系统栏不应被普通点击唤回：
            // 否则顶栏整体下移 50px（状态栏高度），「全屏/退出」按钮的点击位置发生偏移
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        // API 30 以下（G5500 为 Android 6）上面的 systemBarsBehavior 未必落到旧标志位上，
        // 这里再显式写一次沉浸式粘性标志（放在 compat 调用之后，保证是最终生效值）
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
        // 旋转/切换双列后简介可用宽度变化，重新判断是否还显示「展开」
        binding.tvDesc.post { updateDescToggle() }
    }

    private fun onBack() {
        if (isFullscreen) toggleFullscreen() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBack()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // 用根容器的 windowToken：etDanmaku 未必已获得焦点，token 可能为空导致收起失败
        imm.hideSoftInputFromWindow(binding.rootLayout.windowToken, 0)
    }

    // ==================== 生命周期 ====================

    override fun onStart() {
        super.onStart()
        val v = video
        if (player == null && v != null) {
            startPlayback(v, resumePosition)
            startViewersPolling(videoId)
            startHistoryReporting(videoId)
        }
    }

    override fun onStop() {
        super.onStop()
        resumePosition = player?.currentPosition ?: 0
        handler.removeCallbacks(danmakuTicker)
        releasePlayer()
        stopJobs()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // ==================== 相关推荐横向列表 ====================

    private inner class RelatedAdapter : RecyclerView.Adapter<RelatedAdapter.VH>() {

        private val items = mutableListOf<Video>()

        fun submit(list: List<Video>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemPlayerRelatedBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPlayerRelatedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = items[position]
            holder.b.tvTitle.text = v.title
            holder.b.tvMeta.text = "${v.up.displayName()} · ${FormatUtils.formatCount(v.viewCount)}播放"
            ImageLoader.load(holder.b.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))
            holder.b.root.setOnClickListener { Navigator.openVideo(this@PlayerActivity, v) }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            ImageLoader.clear(holder.b.ivCover)
        }
    }
}
