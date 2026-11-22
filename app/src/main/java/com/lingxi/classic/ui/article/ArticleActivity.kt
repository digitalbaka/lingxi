package com.lingxi.classic.ui.article

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lingxi.classic.R
import com.lingxi.classic.data.PlaybackHelper
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.HistoryReportRequest
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.ViewReportRequest
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ActivityArticleBinding
import com.lingxi.classic.player.AudioPositionStore
import com.lingxi.classic.player.BackgroundAudioService
import com.lingxi.classic.player.OfflineCacheManager
import com.lingxi.classic.player.PlaybackPrefs
import com.lingxi.classic.player.VideoCacheManager
import com.lingxi.classic.ui.common.CommentsController
import com.lingxi.classic.ui.common.InteractionBar
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.MarkdownRenderer
import com.lingxi.classic.util.centerContentOnTablet
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 阅读页：kind=article（Markdown 正文）与 kind=audio（精简音频播放条）共用。
 */
class ArticleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleBinding

    private var videoId: Long = 0
    private var video: Video? = null
    private var following = false
    /** 只播本地：入口参数 Navigator.EXTRA_LOCAL_ONLY，命中缓存后不发起任何网络请求 */
    private var localOnly = false

    private var commentsController: CommentsController? = null
    private var interactionBar: InteractionBar? = null

    private var audioPlayer: ExoPlayer? = null
    private var audioUrl = ""
    /** 与 audioUrl 配套的请求头，后台交接与离线缓存复用 */
    private var audioHeaders: Map<String, String> = emptyMap()
    private var retriedAfterRecover = false
    /** 建源中标志：网络慢时连点播放键不能并发建多台播放器（会同时出声且只释放得掉最后一台） */
    private var preparing = false
    private var prepareJob: Job? = null
    /** 建源代次：被取消的旧任务不能在 finally 里把新一轮的 preparing 清掉 */
    private var prepareToken = 0

    // 播放器设置：player 会被 onStop 释放，因此状态存在字段里，新建 player 后重新应用；
    // 字段值会落盘到 SharedPreferences，退出重进（新实例）也能回填，且跨稿件共享
    private var repeatOne = false
    private var playbackSpeed = 1.0f
    private var audioVolume: Float? = null

    companion object {
        private const val SEEK_STEP_MS = 15_000L
        private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        // 音频播放器偏好（全局共享，不按稿件区分）
        private const val PREFS_NAME = "lingxi_audio_prefs"
        private const val KEY_REPEAT_ONE = "repeat_one"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_VOLUME = "volume"
    }

    private val audioPrefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var viewReported = false
    private var viewReportJob: Job? = null
    private var historyJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    /** 进度 ticker 计数：每 10 次（约 5 秒）落盘一次，避免每帧写盘 */
    private var tickCount = 0
    private val seekTicker = object : Runnable {
        override fun run() {
            audioPlayer?.let { p ->
                if (p.duration > 0) {
                    binding.seekBar.progress = (p.currentPosition * 1000 / p.duration).toInt()
                    binding.tvTime.text = "${FormatUtils.formatDurationMs(p.currentPosition)} / ${FormatUtils.formatDurationMs(p.duration)}"
                }
            }
            if (++tickCount % 10 == 0) persistLocalPosition()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 平板：正文列限宽居中，控制阅读行宽
        binding.articleColumn.centerContentOnTablet(760)

        videoId = intent.getLongExtra(Navigator.EXTRA_VIDEO_ID, 0)
        if (videoId <= 0) {
            toast("内容不存在")
            finish()
            return
        }
        loadAudioPrefs()
        initViews()
        localOnly = intent.getBooleanExtra(Navigator.EXTRA_LOCAL_ONLY, false)
        if (localOnly) loadLocalAudio() else loadDetail(videoId)
    }

    /** 读回上次的循环/倍速/音量（跨稿件共享），控件与 player 都用这份值 */
    private fun loadAudioPrefs() {
        repeatOne = audioPrefs.getBoolean(KEY_REPEAT_ONE, false)
        playbackSpeed = audioPrefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        audioVolume = audioPrefs.getFloat(KEY_VOLUME, 1.0f).coerceIn(0f, 1f)
    }

    private fun initViews() {
        // 与播放页同因：根容器不可聚焦时，框架会把首个 focusableInTouchMode 控件（评论输入框）
        // 选为初始焦点，导致进页面就弹键盘并滚到评论区。这里让根容器先拿到焦点。
        binding.articleRoot.requestFocus()
        binding.articleRoot.post { hideKeyboard() }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { toggleAudio() }
        binding.btnCache.setOnClickListener { cacheCurrentAudio() }
        binding.btnRewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.btnForward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.btnRepeat.setOnClickListener { toggleRepeat() }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioVolume = progress / 100f
                audioPlayer?.volume = audioVolume ?: 1.0f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 拖动结束才落盘，避免每一帧都写一次
                audioPrefs.edit().putFloat(KEY_VOLUME, audioVolume ?: 1.0f).apply()
            }
        })
        // 初始化音量滑块（fromUser=false，不会误写字段）
        binding.seekVolume.progress = ((audioVolume ?: 1.0f) * 100).toInt()
        renderRepeat()
        renderSpeed()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioPlayer?.let { p ->
                    if (p.duration > 0) p.seekTo(p.duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = binding.scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.bottom - (binding.scrollView.height + scrollY) < 800) {
                commentsController?.maybeLoadMore()
            }
        }
        interactionBar = InteractionBar(this, binding.interactionBar.root)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.articleRoot.windowToken, 0)
    }

    private fun loadDetail(id: Long) {
        // 切到另一篇内容：丢弃上一页的音频地址与播放器，避免把旧内容续播进来
        if (audioUrl.isNotBlank()) {
            releaseAudio()
            audioUrl = ""
            audioHeaders = emptyMap()
        }
        // 关注状态由详情接口的 following 决定，拿到前不显示 XML 默认的「关注」
        following = false
        binding.btnFollow.visible = false
        lifecycleScope.launch {
            try {
                val detail = ApiClient.apiService.getVideoDetail(id).unwrap(this@ArticleActivity)
                val v = detail?.video
                if (v == null) {
                    toast("内容加载失败")
                    finish()
                    return@launch
                }
                if (v.isVideo) {
                    Navigator.openVideo(this@ArticleActivity, v.id, v.kind)
                    finish()
                    return@launch
                }
                video = v
                // 新建页面实例时：同一条内容从后台音频续播，不同内容则停掉后台音频
                val bgPos = BackgroundAudioService.takePosition(this@ArticleActivity, "", v.id)
                if (bgPos != null) prepareAudio(bgPos) else BackgroundAudioService.stop(this@ArticleActivity)
                bindDetail(v, detail.following)
                interactionBar?.bind(v)
                commentsController = CommentsController(
                    id, v.up?.id ?: v.uid, binding.rvComments, binding.commentInput.root, this@ArticleActivity
                )
                if (v.isArticle) {
                    renderBody(v.content.ifBlank { v.description })
                    armViewReport() // 文章打开 8s 后上报
                }
                startHistoryReporting(id)
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            }
        }
    }

    /**
     * 只播本地：只查离线缓存索引拿标题，不请求详情/评论，直接起播本地音频文件。
     * 索引里没有这条（文件被删）就退出，不回退到网络播放。
     */
    private fun loadLocalAudio() {
        val entry = OfflineCacheManager.find(
            this, OfflineCacheManager.keyOf(videoId, OfflineCacheManager.KIND_AUDIO)
        )
        if (entry == null) {
            toast("本地缓存不存在，可能已被删除")
            finish()
            return
        }
        val v = Video(
            id = entry.videoId,
            title = entry.title,
            cover = entry.cover,
            kind = OfflineCacheManager.KIND_AUDIO
        )
        video = v
        bindLocalDetail(v)
        // 先取后台服务里的实时进度；服务没在播或内容对不上，再读持久化进度（key 为同一条内容 id）
        val bgPos = BackgroundAudioService.takePosition(this, "", v.id)
        prepareAudio(bgPos ?: AudioPositionStore.load(this, v.id) ?: 0L)
    }

    /**
     * 只播本地的详情区：详情、UP 信息拿不到，控件保留并置灰，不隐藏、不空白。
     * 封面只显示占位底色，不加载网络图片。
     */
    private fun bindLocalDetail(v: Video) {
        binding.tvTopTitle.text = "音频"
        binding.tvTitle.text = v.title
        binding.tvMeta.text = "离线播放 · 详情暂不可用"
        binding.tvMeta.alpha = 0.5f

        // 封面保留位置但不发图片请求，只露出占位底色
        binding.ivCover.visible = true
        binding.ivCover.alpha = 0.5f

        // UP 信息：头像占位、名字置灰、关注不可点
        binding.ivUpAvatar.alpha = 0.5f
        binding.ivUpAvatar.setOnClickListener(null)
        binding.tvUpName.text = "UP主信息不可用"
        binding.tvUpName.alpha = 0.5f
        binding.tvUpName.setOnClickListener(null)
        binding.btnFollow.visible = true
        binding.btnFollow.alpha = 0.5f
        binding.btnFollow.isEnabled = false

        // 互动栏与评论区：置灰且整体不可点，避免误触发起网络请求
        binding.interactionBar.root.alpha = 0.4f
        disableAll(binding.interactionBar.root)
        binding.commentInput.root.alpha = 0.4f
        disableAll(binding.commentInput.root)
        binding.rvComments.alpha = 0.4f

        binding.audioBar.visible = true
        binding.llContent.visible = false
        refreshCacheButton()
    }

    /** 递归设为不可用：置灰后的按钮不再响应点击，也就不会发起请求 */
    private fun disableAll(view: View) {
        view.isEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) disableAll(view.getChildAt(i))
        }
    }

    private fun bindDetail(v: Video, followingUp: Boolean) {
        binding.tvTopTitle.text = if (v.isAudio) "音频" else "文章"
        binding.tvTitle.text = v.title
        val date = FormatUtils.formatDate(if (v.publishedAt > 0) v.publishedAt else v.createdAt)
        binding.tvMeta.text = "$date · ${FormatUtils.formatCount(v.viewCount)} ${if (v.isAudio) "播放" else "阅读"}"

        val coverUrl = v.getFullCoverUrl(ApiClient.BASE_URL)
        binding.ivCover.visible = coverUrl.isNotEmpty()
        if (coverUrl.isNotEmpty()) ImageLoader.load(binding.ivCover, coverUrl)

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

        binding.audioBar.visible = v.isAudio
        binding.llContent.visible = v.isArticle
    }

    /** Markdown → TextView（可选择复制）/ ImageView（限高防 OOM）序列 */
    private fun renderBody(markdown: String) {
        val container = binding.llContent
        container.removeAllViews()
        if (markdown.isBlank()) return
        val maxImgHeight = (resources.displayMetrics.widthPixels * 1.5f).toInt()
        for (block in MarkdownRenderer.parseBlocks(markdown, ApiClient.BASE_URL)) {
            when (block) {
                is MarkdownRenderer.Block.Text -> {
                    val tv = TextView(this)
                    tv.text = block.text
                    tv.textSize = 16f
                    tv.setTextColor(ContextCompat.getColor(this, R.color.lx_text))
                    tv.setTextIsSelectable(true)
                    tv.setLineSpacing(4f, 1.15f)
                    container.addView(tv)
                }
                is MarkdownRenderer.Block.Image -> {
                    val iv = ImageView(this)
                    iv.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 8.dp(this@ArticleActivity)
                        bottomMargin = 8.dp(this@ArticleActivity)
                    }
                    iv.adjustViewBounds = true
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.maxHeight = maxImgHeight
                    ImageLoader.load(iv, block.url)
                    container.addView(iv)
                }
            }
        }
    }

    // ==================== 音频播放 ====================

    /** 缓存状态观察者：只在页面可见期间注册，回调不再绑定发起下载的 Activity 实例 */
    private val cacheListener = object : OfflineCacheManager.Listener {
        override fun onProgress(key: String, pct: Int) {
            val v = video ?: return
            if (key != OfflineCacheManager.keyOf(v.id, OfflineCacheManager.KIND_AUDIO)) return
            binding.btnCache.text = "$pct%"
        }

        override fun onFinished(key: String, ok: Boolean, message: String) {
            val v = video ?: return
            if (key != OfflineCacheManager.keyOf(v.id, OfflineCacheManager.KIND_AUDIO)) return
            // 完成后重算按钮状态（保留原有逻辑），失败同样回到「缓存」
            refreshCacheButton()
            toast(if (ok) "已缓存到本地，可在设置里管理" else "缓存失败：$message")
        }
    }

    private fun refreshCacheButton() {
        val v = video ?: return
        val key = OfflineCacheManager.keyOf(v.id, OfflineCacheManager.KIND_AUDIO)
        binding.btnCache.text = when {
            OfflineCacheManager.localFile(this, v.id, OfflineCacheManager.KIND_AUDIO) != null -> "已缓存"
            OfflineCacheManager.isRunning(key) -> "${OfflineCacheManager.progressOf(key)}%"
            else -> "缓存"
        }
    }

    private fun cacheCurrentAudio() {
        val v = video ?: return
        if (OfflineCacheManager.localFile(this, v.id, OfflineCacheManager.KIND_AUDIO) != null) {
            toast("已缓存到本地")
            refreshCacheButton()
            return
        }
        if (audioUrl.isBlank()) {
            toast("音频还没加载好，先点播放再缓存")
            return
        }
        if (audioUrl.startsWith("file://")) {
            toast("该音频已在本地")
            refreshCacheButton()
            return
        }
        if (audioUrl.contains(".m3u8", ignoreCase = true)) {
            toast("该音频为 HLS 流，暂不支持离线缓存")
            return
        }
        val key = OfflineCacheManager.keyOf(v.id, OfflineCacheManager.KIND_AUDIO)
        if (OfflineCacheManager.isRunning(key)) {
            toast("正在缓存 ${OfflineCacheManager.progressOf(key)}%")
            return
        }
        binding.btnCache.text = "0%"
        val started = OfflineCacheManager.start(
            context = this,
            videoId = v.id,
            title = v.title,
            kind = OfflineCacheManager.KIND_AUDIO,
            url = audioUrl,
            cover = v.getFullCoverUrl(com.lingxi.classic.data.api.ApiClient.BASE_URL),
            headers = audioHeaders
        )
        if (!started) refreshCacheButton()
    }

    private fun toggleAudio() {
        val p = audioPlayer
        if (p == null) {
            // 建源未完成时忽略连点：等这一台建好后再响应暂停/播放
            if (preparing) return
            prepareAudio()
        } else if (p.isPlaying) p.pause()
        else p.play()
    }

    /** 快进/快退：裁剪到 [0, duration]，未就绪（duration<=0）直接忽略 */
    private fun seekBy(deltaMs: Long) {
        val p = audioPlayer ?: return
        val dur = p.duration
        if (dur <= 0) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        if (target == p.currentPosition) return
        p.seekTo(target)
        binding.seekBar.progress = (target * 1000 / dur).toInt()
        binding.tvTime.text = "${FormatUtils.formatDurationMs(target)} / ${FormatUtils.formatDurationMs(dur)}"
    }

    private fun toggleRepeat() {
        repeatOne = !repeatOne
        audioPlayer?.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        audioPrefs.edit().putBoolean(KEY_REPEAT_ONE, repeatOne).apply()
        renderRepeat()
    }

    private fun renderRepeat() {
        binding.btnRepeat.text = if (repeatOne) "单曲" else "循环"
        binding.btnRepeat.setBackgroundResource(
            if (repeatOne) R.drawable.bg_audio_ctrl_on else R.drawable.bg_audio_ctrl
        )
        binding.btnRepeat.setTextColor(
            ContextCompat.getColor(this, if (repeatOne) R.color.accent_pressed else R.color.text_secondary)
        )
    }

    private fun cycleSpeed() {
        // 存值可能不在固定档位表里（例如旧版本写入的 0.5x）：匹配不上也要按原值继续，
        // 取「比当前值大的最近一档」，找不到才回最小档，不能把存值当默认值丢掉
        val idx = SPEED_OPTIONS.indexOfFirst { abs(it - playbackSpeed) < 0.001f }
        playbackSpeed = if (idx >= 0) {
            SPEED_OPTIONS[(idx + 1) % SPEED_OPTIONS.size]
        } else {
            SPEED_OPTIONS.firstOrNull { it > playbackSpeed } ?: SPEED_OPTIONS.first()
        }
        audioPlayer?.setPlaybackSpeed(playbackSpeed)
        audioPrefs.edit().putFloat(KEY_PLAYBACK_SPEED, playbackSpeed).apply()
        renderSpeed()
    }

    private fun renderSpeed() {
        binding.btnSpeed.text = when (playbackSpeed) {
            0.5f -> "0.5x"
            1.0f -> "1.0x"
            1.5f -> "1.5x"
            2.0f -> "2.0x"
            else -> "%.2fx".format(playbackSpeed)
        }
        val on = playbackSpeed != 1.0f
        binding.btnSpeed.setBackgroundResource(
            if (on) R.drawable.bg_audio_ctrl_on else R.drawable.bg_audio_ctrl
        )
        binding.btnSpeed.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.accent_pressed else R.color.text_secondary)
        )
    }

    /** 新建 player 后把循环/倍速/音量重新应用，保证与按钮状态一致 */
    private fun applyAudioPrefs(p: Player) {
        p.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setPlaybackSpeed(playbackSpeed)
        val vol = audioVolume ?: p.volume.also { audioVolume = it }
        p.volume = vol
    }

    private fun prepareAudio(startAt: Long = 0L) {
        val v = video ?: return
        if (preparing) return
        preparing = true
        val token = ++prepareToken
        // 本页接管播放前，先掐掉可能还在后台响的上一条内容
        BackgroundAudioService.stop(this)
        // 兜底：任何情况下都先释放旧实例，保证全局只有一台音频播放器
        releaseAudio()
        prepareJob = lifecycleScope.launch {
            var p: ExoPlayer? = null
            try {
                // 先看本地是否有整文件缓存：命中就直接读文件，连建源（节点授权/拉流）都不发起
                val local = OfflineCacheManager.localFile(this@ArticleActivity, v.id, OfflineCacheManager.KIND_AUDIO)
                val url: String
                var headers: Map<String, String> = emptyMap()
                if (local != null) {
                    url = Uri.fromFile(local).toString()
                } else if (localOnly) {
                    // 只播本地分支下文件恰好被删：直接退出，绝不回退到网络拉流
                    toast("本地缓存文件不存在")
                    return@launch
                } else {
                    val ps = PlaybackHelper.buildPlaySource(this@ArticleActivity, v)
                    url = ps.url
                    headers = ps.headers
                }
                audioUrl = url
                audioHeaders = headers
                val factory = if (local != null) {
                    DefaultDataSource.Factory(this@ArticleActivity)
                } else {
                    VideoCacheManager.cacheDataSourceFactory(this@ArticleActivity, headers)
                }
                val source = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(audioUrl))
                p = ExoPlayer.Builder(this@ArticleActivity)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                            .build()
                    )
                    .build()
                // 建源期间页面已退出/被新一次播放打断：直接释放，不留给后台
                if (!isActive) {
                    p.release()
                    return@launch
                }
                p.setMediaSource(source)
                if (startAt > 0) p.seekTo(startAt)
                p.prepare()
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.text = if (isPlaying) "暂停" else "播放"
                        if (isPlaying) {
                            handler.post(seekTicker)
                            // 只播本地不上报播放量
                            if (!localOnly) armViewReport()
                        } else {
                            handler.removeCallbacks(seekTicker)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onAudioError(error)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // 本地音频播到结尾：清掉持久化，避免下次进来接在结尾
                        if (playbackState == Player.STATE_ENDED && isLocalAudio()) {
                            handler.removeCallbacks(seekTicker)
                            AudioPositionStore.clear(this@ArticleActivity, videoId)
                        }
                    }
                })
                applyAudioPrefs(p)
                audioPlayer = p
                p.playWhenReady = true
                refreshCacheButton()
            } catch (e: kotlinx.coroutines.CancellationException) {
                p?.release()
                throw e
            } catch (e: Exception) {
                p?.release()
                toast("播放失败：${e.message}")
            } finally {
                if (token == prepareToken) preparing = false
            }
        }
    }

    private fun onAudioError(error: PlaybackException) {
        // 本地文件不走节点：重授权救不回来，也不该再发请求
        if (localOnly) {
            toast("本地文件播放失败：${error.localizedMessage ?: "文件可能已损坏"}")
            return
        }
        if (!retriedAfterRecover) {
            retriedAfterRecover = true
            val pos = audioPlayer?.currentPosition ?: 0
            lifecycleScope.launch {
                val retry = try {
                    PlaybackHelper.recoverFromPlaybackError(this@ArticleActivity, audioUrl)
                } catch (e: Exception) {
                    false
                }
                if (retry) {
                    releaseAudio()
                    // 建源是异步的：进度直接作为参数传入，不能建完前 seekTo
                    prepareAudio(pos)
                } else {
                    toast("播放失败：${error.localizedMessage ?: "未知错误"}")
                }
            }
        } else {
            toast("播放失败：${error.localizedMessage ?: "未知错误"}")
        }
    }

    private fun releaseAudio() {
        // 释放/切换内容前落一次进度（含 onDestroy），本地音频重进才能接续
        persistLocalPosition()
        // 注意：这里不复位 preparing。prepareAudio 先置位再调本方法，
        // 复位交给建源任务自己的 finally（按 prepareToken 判断代次），
        // 否则守卫会被立刻清掉，连点仍能并发建出多台播放器。
        prepareJob?.cancel()
        prepareJob = null
        handler.removeCallbacks(seekTicker)
        audioPlayer?.pause()
        audioPlayer?.release()
        audioPlayer = null
        binding.btnPlayPause.text = "播放"
    }

    /** 当前是否在播本地音频：只播本地入口，或命中整文件缓存后播放 file: 地址 */
    private fun isLocalAudio(): Boolean = localOnly || audioUrl.startsWith("file:")

    /** 本地音频进度落盘；已播到结尾则清掉本条，避免下次进来接在结尾 */
    private fun persistLocalPosition() {
        if (videoId <= 0L || !isLocalAudio()) return
        val p = audioPlayer ?: return
        val pos = p.currentPosition
        if (pos <= 0L) return
        val duration = p.duration
        if (duration > 0L && pos >= duration - 1_500L) {
            AudioPositionStore.clear(this, videoId)
        } else {
            AudioPositionStore.save(this, videoId, pos)
        }
    }

    // ==================== 上报 ====================

    /** 文章打开 8s / 音频播放 8s 后上报一次播放量 */
    private fun armViewReport() {
        if (viewReported || viewReportJob != null) return
        val id = videoId
        viewReportJob = lifecycleScope.launch {
            delay(8_000)
            try {
                val p = audioPlayer
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
                val p = audioPlayer ?: continue
                if (p.isPlaying && p.duration > 0) {
                    try {
                        ApiClient.apiService.reportHistory(HistoryReportRequest(id, p.currentPosition, p.duration))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ==================== 关注 ====================

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
                val r = ApiClient.apiService.followUser(upUid).unwrap(this@ArticleActivity) ?: return@launch
                following = r.following
                renderFollow()
            } catch (e: Exception) {
                toast("操作失败：${e.message}")
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onStart() {
        super.onStart()
        // 注册即回放当前快照：重进页面立刻显示真实百分比，不用等下一次回调
        OfflineCacheManager.addListener(cacheListener)
        refreshCacheButton()
        // 从后台音频服务回来：取回进度并停服务，继续本地播放；
        // 服务没在播（如已被关闭）且是本地音频时，退回持久化进度
        if (audioPlayer == null && !preparing && audioUrl.isNotBlank()) {
            val bgPos = BackgroundAudioService.takePosition(this, audioUrl, video?.id ?: 0L)
            val restored = bgPos ?: if (isLocalAudio()) AudioPositionStore.load(this, videoId) else null
            if (restored != null) prepareAudio(restored)
        }
    }

    override fun onStop() {
        super.onStop()
        OfflineCacheManager.removeListener(cacheListener)
        // 页面退到后台/离开前落一次进度，与后台开关无关
        persistLocalPosition()
        val p = audioPlayer
        // 退到后台：按设置把播放交接给前台音频服务，通知栏/锁屏可控制
        // 用 playWhenReady 判断「用户想听」：网络慢处于缓冲时 isPlaying 为 false，也要交接
        if (!isFinishing && p != null && p.playWhenReady &&
            PlaybackPrefs.isBackgroundAudioEnabled(this) && audioUrl.isNotBlank()
        ) {
            BackgroundAudioService.handoff(
                this,
                video?.id ?: 0L,
                audioUrl,
                video?.title ?: "",
                // 封面有就带（本地缓存条目也存了封面地址），没有则由服务留空
                video?.getFullCoverUrl(com.lingxi.classic.data.api.ApiClient.BASE_URL) ?: "",
                p.currentPosition,
                audioHeaders,
                // 通知点击时按类型回到音频页
                BackgroundAudioService.KIND_AUDIO,
                // 作者名取页面上已有的 UP 主；本地播放拿不到时为 ""
                video?.up.displayName(""),
                // 实际在读本地文件（只播本地或命中整文件缓存）：通知点击要回只播本地入口
                localOnly || audioUrl.startsWith("file:")
            )
        }
        releaseAudio()
        viewReportJob?.cancel()
        viewReportJob = null
        historyJob?.cancel()
        historyJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releaseAudio()
    }
}
