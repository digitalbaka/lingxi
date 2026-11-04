package com.lingxi.classic.ui.article

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * 阅读页：kind=article（Markdown 正文）与 kind=audio（精简音频播放条）共用。
 */
class ArticleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArticleBinding

    private var videoId: Long = 0
    private var video: Video? = null
    private var following = false

    private var commentsController: CommentsController? = null
    private var interactionBar: InteractionBar? = null

    private var audioPlayer: ExoPlayer? = null
    private var audioUrl = ""
    private var retriedAfterRecover = false

    private var viewReported = false
    private var viewReportJob: Job? = null
    private var historyJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val seekTicker = object : Runnable {
        override fun run() {
            audioPlayer?.let { p ->
                if (p.duration > 0) {
                    binding.seekBar.progress = (p.currentPosition * 1000 / p.duration).toInt()
                    binding.tvTime.text = "${FormatUtils.formatDurationMs(p.currentPosition)} / ${FormatUtils.formatDurationMs(p.duration)}"
                }
            }
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
        initViews()
        loadDetail(videoId)
    }

    private fun initViews() {
        // 与播放页同因：根容器不可聚焦时，框架会把首个 focusableInTouchMode 控件（评论输入框）
        // 选为初始焦点，导致进页面就弹键盘并滚到评论区。这里让根容器先拿到焦点。
        binding.articleRoot.requestFocus()
        binding.articleRoot.post { hideKeyboard() }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { toggleAudio() }
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

    private fun toggleAudio() {
        val p = audioPlayer
        if (p == null) prepareAudio()
        else if (p.isPlaying) p.pause()
        else p.play()
    }

    private fun prepareAudio() {
        val v = video ?: return
        lifecycleScope.launch {
            try {
                val ps = PlaybackHelper.buildPlaySource(this@ArticleActivity, v)
                audioUrl = ps.url
                val factory = VideoCacheManager.cacheDataSourceFactory(this@ArticleActivity, ps.headers)
                val source = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(ps.url))
                val p = ExoPlayer.Builder(this@ArticleActivity)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                            .build()
                    )
                    .build()
                p.setMediaSource(source)
                p.prepare()
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.text = if (isPlaying) "暂停" else "播放"
                        if (isPlaying) {
                            handler.post(seekTicker)
                            armViewReport()
                        } else {
                            handler.removeCallbacks(seekTicker)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onAudioError(error)
                    }
                })
                p.playWhenReady = true
                audioPlayer = p
            } catch (e: Exception) {
                toast("播放失败：${e.message}")
            }
        }
    }

    private fun onAudioError(error: PlaybackException) {
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
                    prepareAudio()
                    audioPlayer?.seekTo(pos)
                } else {
                    toast("播放失败：${error.localizedMessage ?: "未知错误"}")
                }
            }
        } else {
            toast("播放失败：${error.localizedMessage ?: "未知错误"}")
        }
    }

    private fun releaseAudio() {
        handler.removeCallbacks(seekTicker)
        audioPlayer?.release()
        audioPlayer = null
        binding.btnPlayPause.text = "播放"
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

    override fun onStop() {
        super.onStop()
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
