package com.lingxi.classic.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import com.lingxi.classic.R
import com.lingxi.classic.data.PlaybackHelper
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Danmaku
import com.lingxi.classic.data.model.DanmakuSendRequest
import com.lingxi.classic.data.model.HistoryReportRequest
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.VideoQuality
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
import com.lingxi.classic.util.isTablet
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频播放页（kind=video）。
 * 竖屏：16:9 播放器 + 弹幕输入条 + NestedScrollView（信息/相关推荐/评论）。
 * 横屏全屏：隐藏滚动区并沉浸式。
 */
class PlayerActivity : AppCompatActivity() {

    private companion object {
        /** 首帧超时阈值：超过该时长仍无画面即视为当前清晰度不可播 */
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L

        // ---- 触屏手势 ----
        const val GESTURE_NONE = 0
        const val GESTURE_VERTICAL = 1
        const val GESTURE_HORIZONTAL = 2
        /** 方向锁阈值（dp）：位移超过它才锁定方向，避免误触 */
        const val GESTURE_SLOP_DP = 24f
        /** 横滑满宽对应的进度跨度上限（秒） */
        const val SEEK_FULL_WIDTH_SEC = 120
        /** 竖滑满量程所需的高度比例：70% 播放区高度 ≈ 亮度 0→100% / 音量 0→最大 */
        const val VERTICAL_RANGE_RATIO = 0.7f
        /** 控制条底部区域高度兜底（dp）：优先用实测控件位置，找不到控件时按它算 */
        const val CONTROLS_GUARD_DP = 100
        /** 双击判定窗口：两击间隔超过它视为新的一次点击序列（GestureDetector 双击上限 300ms） */
        const val TAP_SEQUENCE_GAP_MS = 400L
        /** 控制条按钮首击的暂存时长：略大于双击窗口，等双击判定后再决定是否补发单击 */
        const val BUTTON_TAP_DELAY_MS = 320L
        /** 双击快进/快退步长（毫秒） */
        const val DOUBLE_TAP_SEEK_MS = 10_000L
        /** 降档后新档位的首帧窗口（毫秒）：新档位要重新建源/解码，不能沿用已过期的首次窗口 */
        const val DOWNGRADE_FIRST_FRAME_TIMEOUT_MS = 6_000L
    }

    private lateinit var binding: ActivityPlayerBinding

    private var player: ExoPlayer? = null
    private var video: Video? = null
    private var videoId: Long = 0
    private var playUrl: String = ""
    private var currentQualityUrl: String? = null
    private var retriedAfterRecover = false
    /** 节点重授权重试任务：到点降档时要能中断它，避免两条链路各起一次播放 */
    private var recoverJob: Job? = null
    /** 建源+建播放器的任务：被新的清晰度/降档/重授权打断时取消，防止旧任务把播放器建成两份 */
    private var playbackJob: Job? = null
    private var resumePosition: Long = 0
    private var following = false
    private var descExpanded = false
    private var isFullscreen = false
    /** 平板上全屏只是布局状态，不绑定横竖屏（平板横屏打开视频不应被强制全屏） */
    private var tabletFullscreen = false

    // ---- 清晰度自动降档（站点部分高码率/4K 无法编码，客户端不黑屏干等）----
    /** 本次播放是否已经出过画面；未出画面且超时则视为失败 */
    private var firstFrameRendered = false
    /**
     * 当前清晰度首帧截止时刻（elapsedRealtime）。
     * 节点重授权后的重试只续用剩余时间、不重新计时，否则每授权一次就把降档窗口往后推一次。
     */
    private var firstFrameDeadlineMs = 0L
    /** 本视频已尝试失败的清晰度 URL，防止同一档反复重试 */
    private val failedQualityUrls = mutableSetOf<String>()
    /** 本视频已提示过的降档目标，避免同一提示反复弹 */
    private val notifiedDowngradeTargets = mutableSetOf<String>()

    // ---- 触屏手势（左亮度 / 右音量 / 横滑松手 seek）----
    private lateinit var audioManager: AudioManager
    /** 进入页面时的亮度覆盖值，-1=跟随系统；离开页面恢复 */
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var gestureMode = GESTURE_NONE
    /** 本次触摸落在顶栏/控制条区域，整个手势序列都不接管 */
    private var gestureBlocked = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureOnLeftSide = false
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var startPositionMs = 0L
    private var previewPositionMs = 0L
    /** 双击识别：在分发层旁路喂事件，只做 ±10 秒，不改单击语义 */
    private lateinit var tapDetector: GestureDetector
    /** 最近一次 ACTION_DOWN 是否落在播放器内，以及换算到播放器坐标系后的落点 */
    private var lastDownInPlayer = false
    private var lastDownX = 0f
    private var lastDownY = 0f
    /** 新一次点击序列开始时（第一击按下之前）控制条是否可见的快照 */
    private var seqControllerVisible = false
    /** 最近一次 ACTION_DOWN 的时间戳，用于判断是否开启新的一次点击序列 */
    private var lastTapDownTime = 0L
    /** 控制条保护区的实测顶边（playerView 局部坐标），-1 表示尚未测量 */
    private var controlsGuardTop = -1f
    /** 首击是否落在 media3 控制按钮上：是则这一串双击不 seek */
    private var lastDownOnControlButton = false
    /** 落在控制条按钮上的首击先暂存，等双击判定后再补发单击（双击则不触发按钮动作） */
    private var pendingButton: View? = null
    private var pendingButtonRunnable: Runnable? = null
    private var pendingButtonRawX = 0f
    private var pendingButtonRawY = 0f
    /** 从控制条按钮开始的一串触摸：整串由分发层接管，不再交给 media3 */
    private var swallowTouchSequence = false
    /** 首击是否落在控制条可交互区（按钮/进度条）：整串双击都不 seek，且不触发按钮动作 */
    private var seqDownOnControlArea = false
    /** 首击是否落在进度条上：DefaultTimeBar 按下即 seek，必须先扣住再决定单击/拖动/双击 */
    private var lastDownOnProgress = false
    private var pendingProgress: View? = null
    private var pendingProgressRunnable: Runnable? = null
    private var progressDownRawX = 0f
    /** 进度条首击后已产生拖动：拖动期间实时 seek，松手不再补发单击 */
    private var progressDragging = false
    private lateinit var gestureOverlay: LinearLayout
    private lateinit var gestureLabel: TextView
    private lateinit var gestureBar: ProgressBar

    private val hideGestureOverlay = Runnable { gestureOverlay.visibility = View.GONE }

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

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            handler.removeCallbacks(firstFrameWatchdog)
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlayError(error)
        }
    }

    /** 首帧看门狗：一直没画面（黑屏/解码失败/长时间无首帧）也按播放失败处理，触发降档 */
    private val firstFrameWatchdog = Runnable {
        if (firstFrameRendered) return@Runnable
        val p = player
        // 重授权 / 建源期间 player 可能为 null 或已报错：照样按窗口到点降档，
        // 不把降档判定挂到「节点恢复成功」上，否则节点慢多久降档就晚多久
        if (p != null && (p.playbackState == Player.STATE_ENDED || !p.playWhenReady)) return@Runnable
        onQualityUnplayable(null)
    }

    /** 按剩余窗口挂看门狗；窗口已过则立即（0 延迟）触发 */
    private fun scheduleFirstFrameWatchdog() {
        handler.removeCallbacks(firstFrameWatchdog)
        val remain = (firstFrameDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(firstFrameWatchdog, remain)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 首帧把焦点收回根容器：否则框架会把焦点自动给第一个可聚焦控件（弹幕输入框），
        // 导致一进播放页就弹出输入法
        binding.root.post {
            binding.root.requestFocus()
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
        setupGestureOverlay()
        setupGestures()
    }

    // ==================== 加载与绑定 ====================

    private fun loadVideo(id: Long) {
        videoId = id
        stopJobs()
        viewReported = false
        retriedAfterRecover = false
        currentQualityUrl = null
        resumePosition = 0
        firstFrameRendered = false
        failedQualityUrls.clear()
        notifiedDowngradeTargets.clear()
        // 首帧窗口从「进入播放页」起算：节点授权、建源、解密/解封装都算在 10 秒里，
        // 不等到建源或首帧才起算，否则节点授权一慢降档窗口就被整体后移
        firstFrameDeadlineMs = SystemClock.elapsedRealtime() + FIRST_FRAME_TIMEOUT_MS
        releasePlayer()
        binding.danmakuView.setDanmaku(emptyList())
        binding.loadingBar.visible = true
        // 关注状态要等详情接口返回才知道（detail.following），
        // 未拿到前不摆出 XML 默认的「关注」，避免已关注用户看到可点的「关注」
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
            binding.tvDesc.setOnClickListener {
                descExpanded = !descExpanded
                binding.tvDesc.maxLines = if (descExpanded) Int.MAX_VALUE else 2
            }
        } else {
            binding.tvDesc.visible = false
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

    // ==================== 播放 ====================

    private fun startPlayback(
        v: Video,
        resumeMs: Long = 0,
        /** 重开首帧窗口的时长：0 表示沿用 loadVideo（进入页面）时定的窗口；降档/手动换档才重开 */
        resetFirstFrameWindowMs: Long = 0L,
        /** 降档重开时跳过节点授权：本次播放链路已经确认授权失败，再等它只会把出画时间往后拖 */
        skipNodeHandoff: Boolean = false
    ) {
        releasePlayer()
        firstFrameRendered = false
        if (resetFirstFrameWindowMs > 0L) {
            firstFrameDeadlineMs = SystemClock.elapsedRealtime() + resetFirstFrameWindowMs
        }
        // 放在建源（内含 handoff 授权）之前：建源/重授权耗时也计入同一个窗口
        scheduleFirstFrameWatchdog()
        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            try {
                val ps = PlaybackHelper.buildPlaySource(this@PlayerActivity, v, currentQualityUrl, skipNodeHandoff)
                // 建源期间若已被降档/切清晰度/离开页面打断，不要再建这台播放器
                if (!isActive) return@launch
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("播放失败：${e.message}")
            }
        }
    }

    /** 播放失败：先清毒缓存 + 重新节点授权重试一次（节点问题换档没用），仍失败则自动降档；降无可降才报错 */
    private fun onPlayError(error: PlaybackException) {
        binding.loadingBar.visible = false
        // 解码器/格式不支持与节点无关：重授权救不回来，直接降档，不白等一轮节点重试
        if (isCodecFailure(error)) {
            onQualityUnplayable(error)
            return
        }
        val url = playUrl
        if (!retriedAfterRecover) {
            retriedAfterRecover = true
            val pos = player?.currentPosition ?: 0
            // 注意：这里不摘掉首帧看门狗。窗口从本清晰度首次尝试开始计时，
            // 重授权期间照常到点判定，避免节点恢复把降档无限推迟。
            recoverJob?.cancel()
            recoverJob = lifecycleScope.launch {
                val retry = try {
                    PlaybackHelper.recoverFromPlaybackError(this@PlayerActivity, url)
                } catch (e: Exception) {
                    false
                }
                if (!isActive) return@launch
                if (retry) {
                    toast("节点连接失败，正在重新授权…")
                    // 沿用同一个首帧窗口，不因为重授权成功就重新给 10 秒
                    video?.let { startPlayback(it, pos) }
                } else {
                    onQualityUnplayable(error)
                }
            }
        } else {
            onQualityUnplayable(error)
        }
    }

    /**
     * 解码器初始化/解码失败、格式超出能力或不被支持：这台设备的硬解就是放不了这一档，
     * 换节点、重授权都不会改变结论。识别出来直接降档，省掉一轮无意义的重试等待。
     */
    private fun isCodecFailure(error: PlaybackException): Boolean {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> return true
        }
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is MediaCodec.CodecException) return true
            cause = cause.cause
        }
        return false
    }

    // ==================== 清晰度自动降档 ====================

    /** 清晰度阶梯：默认档（video.src）视作最高档，其余按 height 从高到低；同 URL 只保留一条 */
    private fun qualityLadder(): List<VideoQuality> {
        val v = video ?: return emptyList()
        val options = v.qualityOptions.filter { it.url.isNotBlank() }
        val srcMatch = options.firstOrNull { it.url == v.src }
        val head = srcMatch ?: VideoQuality(
            label = v.qualityLabel.ifBlank { "默认" },
            height = v.height,
            fps = v.fps,
            url = v.src
        )
        if (head.url.isBlank()) return options.sortedByDescending { it.height }
        val rest = options.filter { it.url != head.url }.sortedByDescending { it.height }
        return listOf(head) + rest
    }

    private fun currentQualityIndex(ladder: List<VideoQuality>): Int {
        val cur = currentQualityUrl ?: video?.src ?: return 0
        val i = ladder.indexOfFirst { it.url == cur }
        return if (i >= 0) i else 0
    }

    /**
     * 当前清晰度播不出来（onPlayerError / 首帧超时）时调用：
     * 按阶梯降一档重播，最多降到最低档，同一档不重复尝试、同一提示不重复弹。
     * 位置在播放器重建时带入，用户无感。
     */
    private fun onQualityUnplayable(error: PlaybackException?) {
        // 到点降档不再等节点重授权结果：中断仍在进行的重试，避免两条链路各起一次播放
        recoverJob?.cancel()
        recoverJob = null
        val v = video ?: return
        val ladder = qualityLadder()
        val curIdx = currentQualityIndex(ladder)
        ladder.getOrNull(curIdx)?.let { failedQualityUrls.add(it.url) }

        val nextIdx = (curIdx + 1 until ladder.size).firstOrNull { ladder[it].url !in failedQualityUrls }
        if (nextIdx == null) {
            toast("播放失败：${error?.localizedMessage ?: "当前清晰度均无法播放"}")
            return
        }
        val next = ladder[nextIdx]
        val fromLabel = ladder.getOrNull(curIdx)?.label ?: "默认"
        val pos = player?.currentPosition ?: 0

        currentQualityUrl = next.url
        binding.tvQuality.text = "清晰度：${next.label}"
        // 降档重开：新档位给一个独立的较短窗口（不沿用已过期的首次窗口），并跳过已失败的节点授权
        startPlayback(v, pos, DOWNGRADE_FIRST_FRAME_TIMEOUT_MS, skipNodeHandoff = true)

        if (notifiedDowngradeTargets.add(next.label)) {
            toast("「$fromLabel」无法播放，已切换到「${next.label}」")
        }
    }

    // ==================== 触屏手势 ====================

    /** 浮层：纯色半透明底 + 一行文字 + 细进度条（不随手指每帧闪，统一 2 秒后自动消失） */
    private fun setupGestureOverlay() {
        gestureOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@PlayerActivity, R.color.scrim_60))
            }
            visibility = View.GONE
        }
        gestureLabel = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.white))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        gestureBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@PlayerActivity, R.color.accent))
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(6)).apply { topMargin = dp(8) }
        }
        gestureOverlay.addView(gestureLabel)
        gestureOverlay.addView(gestureBar)
        binding.playerContainer.addView(
            gestureOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    private fun setupGestures() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalBrightness = window.attributes.screenBrightness
        // 双击快进/快退：左半区 -10s、右半区 +10s。事件由 dispatchTouchEvent 旁路喂入，
        // 不消费事件；单击显隐控制条、按钮点击仍由 media3 默认处理
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 第二击到达：取消首击暂存的控制条按钮/进度条单击，双击不再触发按钮动作或 seek
                pendingButtonRunnable?.let { handler.removeCallbacks(it) }
                pendingButtonRunnable = null
                pendingButton = null
                pendingProgressRunnable?.let { handler.removeCallbacks(it) }
                pendingProgressRunnable = null
                pendingProgress = null
                progressDragging = false
                // 用第二次按下的落点判定左右半区与拦截区（分发层事件是窗口坐标，不能直接用 e.x）
                if (!isDoubleTapBlocked(lastDownX, lastDownY)) seekByDoubleTap(lastDownX)
                return true
            }
        })
        // 滑动手势挂在 PlayerView 上并始终返回 false：单击继续交给默认控制条，
        // 只有位移超过阈值后才由这里接手（控制条按钮自身会消费事件，天然不冲突）
        binding.playerView.setOnTouchListener { _, ev -> handleGestureTouch(ev) }
    }

    /**
     * 双击识别放到 Activity 分发层：控制条可见时，落在其上的第二次按下会被 media3 的
     * PlayerControlView 先消费，挂在 PlayerView 上的监听器根本收不到第二击，
     * 结果就是「控制条一露出来双击就失效」。这里只旁路喂给 GestureDetector，不拦截事件：
     * 单击显隐、按钮点击、上下滑亮度/音量、横滑 seek 全部保持原样。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = ev.eventTime
            // 新的一次点击序列：在派发给子 View 之前快照控制条可见性，
            // 否则第一击会把控制条隐藏/唤出，第二击再查就不是「双击开始时」的状态了
            val newSequence = now - lastTapDownTime > TAP_SEQUENCE_GAP_MS
            if (newSequence) {
                seqControllerVisible = binding.playerView.isControllerFullyVisible
                controlsGuardTop = measureControlsGuardTop()
            }
            lastTapDownTime = now

            val loc = IntArray(2)
            binding.playerView.getLocationOnScreen(loc)
            lastDownX = ev.rawX - loc[0]
            lastDownY = ev.rawY - loc[1]
            lastDownInPlayer = lastDownX >= 0f && lastDownY >= 0f &&
                lastDownX <= binding.playerView.width && lastDownY <= binding.playerView.height
            lastDownOnControlButton = lastDownInPlayer && seqControllerVisible &&
                findControlButtonAt(ev.rawX, ev.rawY) != null
            // 进度条（DefaultTimeBar）按下即 seek，必须当成和按钮一样的「控制条内部」首击：
            // 先扣住整串触摸，单击在 320ms 后补发（seek 到点击点），拖动则实时跟随，双击直接吞掉
            val progress = if (lastDownInPlayer && seqControllerVisible && !lastDownOnControlButton)
                findProgressBarAt(ev.rawX, ev.rawY) else null
            lastDownOnProgress = progress != null
            progressDragging = false
            // 双击拦截看的是「序列第一击」的落点：第二击就算挪到画面中部也不能解除拦截
            if (newSequence) seqDownOnControlArea = lastDownOnControlButton || lastDownOnProgress

            // 落在控制条按钮/进度条上的首击先扣住：是单击就按 media3 原逻辑补发，是双击就整串吞掉
            pendingButtonRunnable?.let { handler.removeCallbacks(it) }
            pendingButtonRunnable = null
            pendingButton = if (lastDownOnControlButton) findControlButtonAt(ev.rawX, ev.rawY) else null
            pendingButtonRawX = ev.rawX
            pendingButtonRawY = ev.rawY
            pendingProgressRunnable?.let { handler.removeCallbacks(it) }
            pendingProgressRunnable = null
            pendingProgress = progress
            progressDownRawX = ev.rawX
            // 从按钮/进度条上开始的这一串触摸不再交给子 View，避免 MOVE 丢失 DOWN 后手势/点击打架
            swallowTouchSequence = lastDownOnControlButton || lastDownOnProgress
        } else if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val slop = GESTURE_SLOP_DP * resources.displayMetrics.density
            // 一移动就不再是点击（按钮不做长按/拖动），但整串触摸仍吞掉，防止误触发亮度/音量
            if (pendingButton != null &&
                (abs(ev.rawX - pendingButtonRawX) > slop || abs(ev.rawY - pendingButtonRawY) > slop)
            ) {
                pendingButton = null
            }
            // 进度条：位移超过阈值即进入拖动，实时跟随手指 seek（保持 media3 拖动 seek 的原行为）
            val bar = pendingProgress
            if (bar != null) {
                if (!progressDragging && abs(ev.rawX - progressDownRawX) > slop) progressDragging = true
                if (progressDragging) seekToProgressPoint(bar, ev.rawX)
            }
        }
        // 一次手势只在起点落在播放器内时才喂给检测器，避免弹幕条/评论区的触摸混进双击序列
        if (lastDownInPlayer) tapDetector.onTouchEvent(ev)
        // 暂存未决的首击：吞掉事件，等双击窗口过去再补发单击
        if (swallowTouchSequence) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                if (pendingButton != null) {
                    val btn = pendingButton
                    val r = Runnable {
                        if (pendingButton === btn) {
                            pendingButton = null
                            btn?.performClick()
                        }
                    }
                    pendingButtonRunnable = r
                    handler.postDelayed(r, BUTTON_TAP_DELAY_MS)
                }
                // 进度条单击：补发 media3 原有的「点到哪跳到哪」；拖动过的已实时 seek，不再补发
                val bar = pendingProgress
                if (bar != null && !progressDragging) {
                    val r = Runnable {
                        if (pendingProgress === bar) {
                            pendingProgress = null
                            seekToProgressPoint(bar, progressDownRawX)
                        }
                    }
                    pendingProgressRunnable = r
                    handler.postDelayed(r, BUTTON_TAP_DELAY_MS)
                } else {
                    pendingProgress = null
                }
            } else if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pendingButton = null
                pendingProgress = null
                progressDragging = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleGestureTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = GESTURE_NONE
                gestureBlocked = false
                // 先重置起点：即便本次被拦截，MOVE 也不会沿用上一次手势的起点
                gestureStartX = ev.x
                gestureStartY = ev.y
                // 顶栏（返回/标题/全屏）与底部控制条区域不接管手势（用双击时的同一把尺子）
                val topBar = binding.btnBack.parent as? View
                if (topBar != null && ev.y < topBar.bottom) {
                    gestureBlocked = true
                } else if (seqControllerVisible && ev.y >= controlsGuardTopOf(binding.playerView)) {
                    gestureBlocked = true
                }
            }
            MotionEvent.ACTION_MOVE -> if (!gestureBlocked) handleGestureMove(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gestureBlocked && gestureMode == GESTURE_HORIZONTAL &&
                    ev.actionMasked == MotionEvent.ACTION_UP
                ) {
                    commitHorizontalSeek()
                }
                gestureMode = GESTURE_NONE
                gestureBlocked = false
            }
        }
        return false
    }

    /** 顶栏整行、控制条按钮、以及底部整条控制条：这些位置的双击不 seek */
    private fun isDoubleTapBlocked(x: Float, y: Float): Boolean {
        val pv = binding.playerView
        if (x < 0f || y < 0f || x > pv.width || y > pv.height) return true
        val topBar = binding.btnBack.parent as? View
        if (topBar != null && topBar.visibility == View.VISIBLE) {
            val barLoc = IntArray(2)
            topBar.getLocationOnScreen(barLoc)
            val pvLoc = IntArray(2)
            pv.getLocationOnScreen(pvLoc)
            if (y < barLoc[1] - pvLoc[1] + topBar.height) return true
        }
        // 序列首击压在控制条按钮/进度条上：这一串双击一律不 seek
        // （放在「可见性 + 保护区」判断之前，第二击挪出保护区也不能解除拦截）
        if (seqDownOnControlArea) return true
        // 是否可见用双击序列开始时的快照：控制条隐藏时底部就是纯画面，照常双击快进退
        return seqControllerVisible && y >= controlsGuardTopOf(pv)
    }

    /** 控制条保护区顶边（playerView 局部坐标）：优先实测，控件还没挂上时按兜底高度算 */
    private fun controlsGuardTopOf(pv: View): Float =
        if (controlsGuardTop > 0f) controlsGuardTop else (pv.height - dp(CONTROLS_GUARD_DP)).toFloat()

    /**
     * 实测控制条保护区的顶边：进度条（含触摸热区）与底部栏里更靠上的那个。
     * media3 的 id 由 AAR 资源合并进本包，用 getIdentifier 取，取不到就退回 100dp。
     */
    private fun measureControlsGuardTop(): Float {
        val pv = binding.playerView
        val pvLoc = IntArray(2)
        pv.getLocationOnScreen(pvLoc)
        var top = Int.MAX_VALUE
        val names = arrayOf("exo_progress", "exo_progress_placeholder", "exo_bottom_bar")
        for (name in names) {
            val rid = resources.getIdentifier(name, "id", packageName)
            if (rid == 0) continue
            val v = pv.findViewById<View>(rid) ?: continue
            if (v.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            top = min(top, loc[1] - pvLoc[1])
        }
        return if (top == Int.MAX_VALUE) (pv.height - dp(CONTROLS_GUARD_DP)).toFloat() else top.toFloat()
    }

    /**
     * 找到落点处的进度条（exo_progress 优先，退到 exo_progress_placeholder）。
     * 进度条不是 ImageButton，需要单独判定，否则它的「按下即 seek」会绕过双击守卫。
     */
    private fun findProgressBarAt(rawX: Float, rawY: Float): View? {
        val pv = binding.playerView
        val loc = IntArray(2)
        for (name in arrayOf("exo_progress", "exo_progress_placeholder")) {
            val rid = resources.getIdentifier(name, "id", packageName)
            if (rid == 0) continue
            val v = pv.findViewById<View>(rid) ?: continue
            if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + v.width &&
                rawY >= loc[1] && rawY <= loc[1] + v.height
            ) {
                return v
            }
        }
        return null
    }

    /** 把进度条上的屏幕 x 换算成播放位置并 seek：单击补发与拖动共用同一套换算 */
    private fun seekToProgressPoint(bar: View, rawX: Float) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0L || bar.width <= 0) return
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        val ratio = ((rawX - loc[0]) / bar.width.toFloat()).coerceIn(0f, 1f)
        p.seekTo((duration * ratio).toLong())
    }

    /**
     * 找到落点处的控制条可点击控件。
     *
     * media3 控制条里的可点击项不止 ImageButton：快退/快进（exo_rew_with_amount /
     * exo_ffwd_with_amount）是带「-10 / +15」文字的 Button，属于 TextView 系。
     * 上一版只认 ImageButton，这两个按钮的双击既没被吞掉、又叠了一次 ±10s seek。
     * 这里改成「控制条容器内、可点击的 TextView/ImageButton」通用判定，不按 id 硬编码，
     * 齿轮/播放/上一个/下一个/字幕等同类控件一并覆盖。
     */
    private fun findControlButtonAt(rawX: Float, rawY: Float): View? {
        val loc = IntArray(2)
        return findClickableControl(binding.playerView, rawX, rawY, loc)
    }

    /** 控制条容器 id：用来把「控制条」与播放器里的其它可点击控件区分开 */
    private val controlContainerIds: Set<Int> by lazy {
        listOf(
            "exo_center_controls",
            "exo_bottom_bar",
            "exo_basic_controls",
            "exo_extra_controls",
            "exo_minimal_controls",
            "exo_time"
        ).mapNotNull { name ->
            resources.getIdentifier(name, "id", packageName).takeIf { it != 0 }
        }.toSet()
    }

    private fun findClickableControl(v: View, rawX: Float, rawY: Float, loc: IntArray): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val found = findClickableControl(v.getChildAt(i), rawX, rawY, loc)
                if (found != null) return found
            }
            return null
        }
        // ImageButton 是 ImageView 子类，Button 是 TextView 子类，两族都要覆盖
        val isClickableType = v is android.widget.ImageButton || v is android.widget.TextView
        if (!isClickableType || !v.isClickable || !v.isEnabled) return null
        // 只认挂在控制条容器内的控件：顶栏标题/返回、弹幕条等可点击文字不会被误吞
        if (controlContainerIds.isNotEmpty() && !isInsideControls(v, binding.playerView)) return null
        v.getLocationOnScreen(loc)
        return if (rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height) v else null
    }

    /** 沿父链确认控件在控制条容器里；走到 PlayerView 根仍未命中即判定不在控制条内 */
    private fun isInsideControls(v: View, root: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p is View) {
            if (p === root) return false
            if (p.id != View.NO_ID && p.id in controlContainerIds) return true
            p = (p as View).parent
        }
        return false
    }

    private fun handleGestureMove(ev: MotionEvent) {
        val threshold = GESTURE_SLOP_DP * resources.displayMetrics.density
        if (gestureMode == GESTURE_NONE) {
            val dx = ev.x - gestureStartX
            val dy = ev.y - gestureStartY
            if (abs(dx) <= threshold && abs(dy) <= threshold) return
            if (abs(dy) > abs(dx)) {
                gestureMode = GESTURE_VERTICAL
                gestureOnLeftSide = gestureStartX < binding.playerView.width / 2f
                startBrightness = effectiveBrightness()
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                // 从锁定那一刻重新起算，避免阈值内的位移造成跳变
                gestureStartY = ev.y
            } else {
                val dur = player?.duration ?: 0L
                if (dur <= 0) return
                gestureMode = GESTURE_HORIZONTAL
                startPositionMs = (player?.currentPosition ?: 0L).coerceAtLeast(0)
                previewPositionMs = startPositionMs
                gestureStartX = ev.x
            }
        }
        when (gestureMode) {
            GESTURE_VERTICAL -> applyVerticalDelta(gestureStartY - ev.y)
            GESTURE_HORIZONTAL -> previewHorizontalDelta(ev.x - gestureStartX)
        }
    }

    /** 纵向：左半区调亮度、右半区调媒体音量，实时生效 */
    private fun applyVerticalDelta(dy: Float) {
        val height = binding.playerView.height.coerceAtLeast(1)
        val ratio = dy / (height * VERTICAL_RANGE_RATIO)
        if (gestureOnLeftSide) {
            val value = (startBrightness + ratio).coerceIn(0f, 1f)
            val lp = window.attributes
            lp.screenBrightness = value
            window.attributes = lp
            val pct = (value * 100).roundToInt()
            showGestureOverlay("亮度 $pct%", pct, 100)
        } else {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (startVolume + ratio * max).roundToInt().coerceIn(0, max)
            if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            val pct = target * 100 / max
            showGestureOverlay("音量 $pct%", pct, 100)
        }
    }

    /** 横向：只预览目标时间，松手才真正 seek */
    private fun previewHorizontalDelta(dx: Float) {
        val dur = player?.duration ?: 0L
        if (dur <= 0) return
        val width = binding.playerView.width.coerceAtLeast(1)
        val spanSec = min(dur / 1000, SEEK_FULL_WIDTH_SEC.toLong())
        val deltaMs = (dx / width * spanSec * 1000).toLong()
        previewPositionMs = (startPositionMs + deltaMs).coerceIn(0, dur)
        val sec = abs(deltaMs) / 1000
        val arrow = if (deltaMs >= 0) "▶" else "◀"
        val action = if (deltaMs >= 0) "快进" else "快退"
        showGestureOverlay(
            "$arrow $action ${sec}s  ${FormatUtils.formatDurationMs(previewPositionMs)} / ${FormatUtils.formatDurationMs(dur)}",
            previewPositionMs.toInt(),
            dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    private fun commitHorizontalSeek() {
        val p = player ?: return
        if (p.duration <= 0) return
        val delta = previewPositionMs - startPositionMs
        p.seekTo(previewPositionMs)
        binding.danmakuView.seekTo(previewPositionMs)
        val sec = abs(delta) / 1000
        val action = if (delta >= 0) "▶ 快进" else "◀ 快退"
        showGestureOverlay("$action ${sec}s", 100, 100)
    }

    /** 双击：左半区后退 10s、右半区前进 10s；不改播放状态，落点裁剪到 [0, duration] */
    private fun seekByDoubleTap(x: Float) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val delta = if (x < binding.playerView.width / 2f) -DOUBLE_TAP_SEEK_MS else DOUBLE_TAP_SEEK_MS
        val target = (p.currentPosition + delta).coerceIn(0L, dur)
        if (target == p.currentPosition) return
        p.seekTo(target)
        binding.danmakuView.seekTo(target)
        showGestureOverlay(
            if (delta < 0) "« 10 秒" else "10 秒 »", 100, 100, showBar = false
        )
    }

    /** 尚未设置覆盖值时用系统亮度作起点（只读，不申请 WRITE_SETTINGS） */
    private fun effectiveBrightness(): Float {
        val attr = window.attributes.screenBrightness
        if (attr >= 0f) return attr
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0f, 1f)
    }

    private fun showGestureOverlay(label: String, progress: Int, max: Int, showBar: Boolean = true) {
        handler.removeCallbacks(hideGestureOverlay)
        // 复用同一个浮层：连续操作只刷新文字，不会叠出多个
        gestureOverlay.visibility = View.VISIBLE
        gestureLabel.text = label
        gestureBar.visibility = if (showBar) View.VISIBLE else View.GONE
        gestureBar.max = max.coerceAtLeast(1)
        gestureBar.progress = progress.coerceIn(0, gestureBar.max)
        handler.postDelayed(hideGestureOverlay, 2_000)
    }

    /** 离开页面把亮度还给系统（原值通常是 -1 跟随系统） */
    private fun restoreBrightness() {
        val lp = window.attributes
        if (lp.screenBrightness != originalBrightness) {
            lp.screenBrightness = originalBrightness
            window.attributes = lp
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun releasePlayer() {
        handler.removeCallbacks(firstFrameWatchdog)
        playbackJob?.cancel()
        playbackJob = null
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
                // 发送成功立即上屏
                binding.danmakuView.appendLive(Danmaku(content = content, timeMs = pos, mode = Danmaku.MODE_SCROLL))
                // 本地 +1 并只重绘 meta：不重新拉详情，避免重建播放器/重置进度
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
        recoverJob?.cancel()
        recoverJob = null
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
                // 用户主动换档：重新给一个完整首帧窗口
                startPlayback(v, pos, FIRST_FRAME_TIMEOUT_MS)
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
        val lp = binding.playerContainer.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
        } else {
            var w = ViewGroup.LayoutParams.MATCH_PARENT
            var h = dm.widthPixels * 9 / 16
            if (isTablet()) {
                // 平板横屏下 16:9 全宽会占满甚至超出屏幕高度，按 3/4 屏高约束并水平居中
                val maxH = dm.heightPixels * 3 / 4
                if (h > maxH) {
                    h = maxH
                    w = maxH * 16 / 9
                }
            }
            lp.width = w
            lp.height = h
            lp.gravity = if (w == ViewGroup.LayoutParams.MATCH_PARENT)
                Gravity.NO_GRAVITY else Gravity.CENTER_HORIZONTAL
        }
        binding.playerContainer.layoutParams = lp
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
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
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
        handler.removeCallbacks(hideGestureOverlay)
        releasePlayer()
        restoreBrightness()
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
