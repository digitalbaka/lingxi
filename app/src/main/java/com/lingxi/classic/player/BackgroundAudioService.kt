package com.lingxi.classic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lingxi.classic.LingXiApp
import com.lingxi.classic.ui.article.ArticleActivity
import com.lingxi.classic.ui.common.Navigator
import com.lingxi.classic.ui.main.MainActivity
import com.lingxi.classic.ui.player.PlayerActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后台音频播放：视频/音频页退到后台时，把当前内容与进度交接给本服务，
 * 用系统 MediaSession + MediaStyle 通知提供暂停/播放/拖动进度/关闭。
 * 只播音频轨，不渲染画面；重新回到页面时由页面取回进度并继续本地播放。
 */
class BackgroundAudioService : Service() {

    companion object {
        private const val TAG = "LxBgAudio"
        private const val CHANNEL_ID = "lingxi_background_audio"
        private const val NOTIFICATION_ID = 0x1A2B

        const val ACTION_START = "com.lingxi.classic.action.BG_AUDIO_START"
        const val ACTION_TOGGLE = "com.lingxi.classic.action.BG_AUDIO_TOGGLE"
        const val ACTION_STOP = "com.lingxi.classic.action.BG_AUDIO_STOP"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        const val EXTRA_POSITION = "position"
        const val EXTRA_HANDOFF_ID = "handoff_id"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ARTIST = "artist"
        /** 交接内容是否来自本地缓存文件：通知/桌面图标回到的页面要走「只播本地」入口 */
        const val EXTRA_LOCAL = "local"
        /** 媒体卡里「关闭」自定义 action 的 id（11+ 系统媒体卡从 PlaybackState 取自定义按钮） */
        const val ACTION_CLOSE = "com.lingxi.classic.action.CLOSE"

        const val KIND_VIDEO = "video"
        const val KIND_AUDIO = "audio"

        /** 当前服务负责的内容，供页面判断能否取回进度 */
        @Volatile
        var activeUrl: String? = null
            private set

        /** 当前服务负责的内容 id：页面重建时即使 url 还没建好也能对上 */
        @Volatile
        var activeVideoId: Long = 0L
            private set

        /** 当前内容类型（video/audio）：通知点击要据此回到对应播放页 */
        @Volatile
        var activeKind: String = KIND_VIDEO
            private set

        /** 当前内容是否在播本地文件：通知点击/桌面图标回跳要据此走只播本地入口 */
        @Volatile
        var activeLocal: Boolean = false
            private set

        /** 服务实例：页面取回进度时优先读播放器的实时位置，而不是事件快照 */
        @Volatile
        private var instance: BackgroundAudioService? = null

        @Volatile
        private var lastPosition: Long = 0L

        /** 页面退到后台时交接播放 */
        fun handoff(
            context: Context,
            videoId: Long,
            url: String,
            title: String,
            cover: String,
            position: Long,
            headers: Map<String, String>,
            kind: String = KIND_VIDEO,
            artist: String = "",
            /** 本次交接的内容是否在播本地文件（离线缓存）：决定点击通知回到哪个入口 */
            local: Boolean = false
        ) {
            // onStop 在应用内跳页时也会触发：只有整个应用退到后台才把声音接到服务，
            // 避免「从播放页点进 UP 主页，旧视频声音还在响」
            if (!LingXiApp.isGoingBackground()) return
            activeUrl = url
            activeVideoId = videoId
            activeKind = kind
            activeLocal = local
            lastPosition = position
            val intent = Intent(context, BackgroundAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_COVER, cover)
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_HANDOFF_ID, videoId)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_LOCAL, local)
                putExtra(EXTRA_HEADERS, HashMap(headers))
            }
            val viaFgs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            try {
                if (viaFgs) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 真机上「没通知也没声音」的唯一线索：31+ 后台启动前台服务被拒等异常都在这里
                Log.w(
                    TAG,
                    "handoff start failed: ${e.javaClass.name}, appBackground=${LingXiApp.isGoingBackground()}, start=${if (viaFgs) "startForegroundService" else "startService"}"
                )
                activeUrl = null
                activeVideoId = 0L
                activeKind = KIND_VIDEO
                activeLocal = false
            }
        }

        /** 服务是否在播放（含缓冲中「想听」）：桌面图标回跳据此决定要不要回到播放页 */
        fun isPlaying(): Boolean = instance?.player?.playWhenReady == true

        /**
         * 正在播放内容的页面入口：按类型选视频页/音频页，本地内容带上「只播本地」参数。
         * 通知点击与桌面图标回跳共用这一份，避免两处各写一套落点。
         * 没有在播内容时返回 null（调用方自己决定落到哪）。
         */
        fun nowPlayingIntent(context: Context): Intent? {
            val id = activeVideoId
            if (id <= 0L) return null
            val cls = if (activeKind == KIND_AUDIO) ArticleActivity::class.java else PlayerActivity::class.java
            return Intent(context, cls).apply {
                putExtra(Navigator.EXTRA_VIDEO_ID, id)
                // 本地文件：走只播本地入口，断网也能直接起播，且不会落到在线详情页
                if (activeLocal) putExtra(Navigator.EXTRA_LOCAL_ONLY, true)
            }
        }

        /**
         * 页面回到前台：若服务在播同一内容（url 或内容 id 命中），返回其进度并停止服务；
         * 否则返回 null。
         */
        fun takePosition(context: Context, url: String, videoId: Long = 0L): Long? {
            val sameUrl = url.isNotBlank() && activeUrl == url
            val sameId = videoId != 0L && activeVideoId == videoId
            if (!sameUrl && !sameId) return null
            // 读播放器实时位置：lastPosition 只是离散事件的快照，后台播放期间会落后
            val pos = instance?.livePosition() ?: lastPosition
            try {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            } catch (_: Exception) {}
            activeUrl = null
            activeVideoId = 0L
            activeKind = KIND_VIDEO
            activeLocal = false
            return pos
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            } catch (_: Exception) {}
            activeUrl = null
            activeVideoId = 0L
            activeKind = KIND_VIDEO
            activeLocal = false
        }
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var title: String = ""
    private var cover: String = ""
    private var artist: String = ""
    /** 已下载的封面位图：21–25 的锁屏/通知只认 bitmap，高版本则 bitmap 与 uri 都能用 */
    private var artwork: Bitmap? = null
    private var artworkLoadedFor: String = ""
    /** 无封面时的透明占位：避免系统退回应用图标/MIME 图标 */
    private val blankArtwork: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    /** 已经写进 MediaMetadata 的时长，避免每秒重复 setMetadata */
    private var metadataDurationMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 每秒把播放器的实时位置同步到 PlaybackState/MediaSession。
     * 只在事件点 setPlaybackState，系统媒体控件拿到的位置会停在快照上；
     * 时长也等播放器 READY 后才拿得到，一并在这里补进 metadata。
     */
    private val sessionTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            // 只同步位置/时长，不重复 notify：缓冲时 wantPlay != isPlaying，逐秒 notify 会刷屏
            updateSessionState(syncNotification = false)
            if (p.isPlaying) handler.postDelayed(this, 1000L)
        }
    }

    /** 给页面取回进度用：直接读播放器当前位置，拿不到时才退回事件快照 */
    private fun livePosition(): Long = player?.currentPosition ?: lastPosition

    override fun onBind(intent: Intent?): IBinder? = null

    // 这两个 FLAG 在 26+ 已废弃（默认就会处理媒体键），但 21–25 必须显式打开，不能删
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        session = MediaSession(this, "LingXiAudio").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 平台签名是 setPlaybackToLocal(android.media.AudioAttributes)，不是 int 流类型；
            // 这里必须用平台 AudioAttributes，不能用上面的 media3 AudioAttributes
            setPlaybackToLocal(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isActive = true
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    player?.play()
                }

                override fun onPause() {
                    player?.pause()
                }

                override fun onStop() {
                    stopPlayback()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    // 11+ 系统媒体卡里的「关闭」：直接停播并移除通知，不回到 App
                    if (action == ACTION_CLOSE) stopPlayback()
                }

                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos)
                    updateSessionState()
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val p = player ?: return START_NOT_STICKY
                if (p.isPlaying) p.pause() else p.play()
                return START_NOT_STICKY
            }
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopPlayback()
            return START_NOT_STICKY
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: "凌汐"
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        // 作者取自发起页的 UP 主；本地缓存没有作者信息时为空串，不写死应用名
        artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        // 33+ 有带类型的 getSerializableExtra，但它同样要强转、且 21–32 只能用旧版，
        // 这里统一走旧 API，行为一致
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
            ?: HashMap()

        // 切到另一条内容之前，先把上一条本地音频的进度存下来
        val newId = intent.getLongExtra(EXTRA_HANDOFF_ID, 0L)
        if (player != null && activeVideoId > 0L && newId != activeVideoId &&
            activeKind == KIND_AUDIO && activeLocal
        ) {
            val oldPos = player?.currentPosition ?: lastPosition
            if (oldPos > 0L) AudioPositionStore.save(this, activeVideoId, oldPos)
        }

        activeUrl = url
        activeVideoId = newId
        activeKind = intent.getStringExtra(EXTRA_KIND) ?: KIND_VIDEO
        activeLocal = intent.getBooleanExtra(EXTRA_LOCAL, false)
        lastPosition = position
        // 换内容后旧时长/旧封面不能沿用
        metadataDurationMs = 0L
        artwork = null
        artworkLoadedFor = ""
        startForeground(NOTIFICATION_ID, buildNotification(false))
        startPlaying(url, position, headers)
        return START_NOT_STICKY
    }

    private fun startPlaying(url: String, position: Long, headers: Map<String, String>) {
        val p = player ?: ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also {
                player = it
                it.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateSessionState()
                        // 网络差时缓冲中 isPlaying=false，但用户仍然想听：按钮沿用 playWhenReady
                        refreshNotification(player?.playWhenReady == true)
                        if (isPlaying) {
                            handler.removeCallbacks(sessionTicker)
                            handler.postDelayed(sessionTicker, 1000L)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateSessionState()
                        // 播到结尾：清掉本条本地进度，下次进页从 0 起
                        if (playbackState == Player.STATE_ENDED) stopPlayback(clearPosition = true)
                    }
                })
            }

        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(this, http)
        val item = if (url.startsWith("/")) {
            MediaItem.fromUri(Uri.fromFile(File(url)))
        } else {
            MediaItem.fromUri(url)
        }
        val source = if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSource).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
        }
        p.setMediaSource(source)
        if (position > 0) p.seekTo(position)
        p.prepare()
        p.playWhenReady = true

        session?.setMetadata(buildMetadata())
        loadArtwork(cover)
        updateSessionState()
    }

    /**
     * 封面：先写 uri（现代系统媒体卡片直接按 uri 取图），同时自行下载一份 bitmap，
     * 供 21–25 只认 METADATA_KEY_ART/ALBUM_ART 的锁屏与通知使用。
     * 没有封面就不写 uri，并用透明位图占位，让系统留空，而不是回退成应用图标。
     */
    private fun loadArtwork(url: String) {
        if (url.isBlank()) return
        if (artworkLoadedFor == url && artwork != null) return
        artworkLoadedFor = url
        Thread {
            val bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            if (bmp == null) return@Thread
            handler.post {
                // 期间换了内容或服务已销毁：丢弃这次结果
                if (session == null || artworkLoadedFor != url) return@post
                artwork = bmp
                session?.setMetadata(buildMetadata())
                refreshNotification(player?.playWhenReady == true)
            }
        }.start()
    }

    private fun buildMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            // 作者名：可为空串；不写死应用名
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (cover.isNotBlank()) {
            // 高版本系统媒体卡片/媒体恢复直接读 uri
            builder.putString(MediaMetadata.METADATA_KEY_ART_URI, cover)
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, cover)
        }
        val art = artwork
        when {
            art != null -> {
                // 21–25 的锁屏与通知只认 bitmap
                builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            }
            cover.isBlank() -> {
                // 没有封面：放透明占位，保持空白，不让系统用应用图标/MIME 图标顶替
                builder.putBitmap(MediaMetadata.METADATA_KEY_ART, blankArtwork)
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, blankArtwork)
            }
            // 有封面但位图还没下载好：先只留 uri，等下载完再补 bitmap
        }
        // 系统媒体控件（通知/锁屏）的进度条总时长取自这里；缺了它只会显示 00:00/00:00
        if (metadataDurationMs > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, metadataDurationMs)
        }
        return builder.build()
    }

    /** 播放器 READY 后才有时长：补写一次 metadata，不必每秒重复 */
    private fun syncMetadataDuration() {
        val d = player?.duration ?: return
        if (d == C.TIME_UNSET || d <= 0L || d == metadataDurationMs) return
        metadataDurationMs = d
        session?.setMetadata(buildMetadata())
    }

    private fun updateSessionState(syncNotification: Boolean = true) {
        val p = player ?: return
        lastPosition = p.currentPosition
        val playing = p.isPlaying
        val wantPlay = p.playWhenReady
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                when {
                    p.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
                    playing -> PlaybackState.STATE_PLAYING
                    else -> PlaybackState.STATE_PAUSED
                },
                p.currentPosition,
                if (playing) 1f else 0f
            )
            // 通知里已有「关闭」按钮（Android 12 及以下走这条）；13+ 的系统媒体卡
            // 以 PlaybackState 为准，这个自定义 action 才会出现在卡片操作区
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_CLOSE,
                    "关闭",
                    android.R.drawable.ic_menu_close_clear_cancel
                ).build()
            )
            .build()
        session?.setPlaybackState(state)
        syncMetadataDuration()
        // 暂停时通知不再常驻；播放/缓冲中保持常驻，避免被系统一并清掉
        if (syncNotification && wantPlay != playing) refreshNotification(wantPlay)
    }

    private fun refreshNotification(playing: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(playing))
        } catch (_: Exception) {}
    }

    // Notification.Builder(Context) 与 Notification.Action.Builder(int, ...) 在 26/23 已废弃，
    // 但 21–25 只能用它们（26+ 的渠道构造、23+ 的 Icon 构造在低版本不存在），故保留并抑制
    @Suppress("DEPRECATION")
    private fun buildNotification(playing: Boolean): Notification {
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // FLAG_IMMUTABLE 是 API 23 才有的常量：依赖这句 >= M 的分支，21–22 走下面的旧 flag 写法
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, BackgroundAudioService::class.java).setAction(ACTION_TOGGLE),
            piFlags
        )
        val close = PendingIntent.getService(
            this, 2,
            Intent(this, BackgroundAudioService::class.java).setAction(ACTION_STOP),
            piFlags
        )
        // 点通知回到正在播放的内容页，而不是首页：本地内容带上只播本地参数，不会落回在线详情页
        val targetIntent = nowPlayingIntent(this)?.apply {
            // 复用已有任务栈并把目标页之前的页面清掉：视频页是 singleTop，会走 onNewIntent；
            // 音频页是标准模式，会按 id 重建，避免落在旧的同名页面上
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(this, MainActivity::class.java)
        val content = PendingIntent.getActivity(this, 3, targetIntent, piFlags)
        // 11+ 的系统媒体卡片/媒体恢复不一定用通知的 contentIntent，而是读 sessionActivity
        session?.setSessionActivity(content)
        val icon = android.R.drawable.ic_media_play
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "凌汐" })
            // 第二行显示作者；没有作者信息时留空，不写应用名
            .setContentText(artist)
            .setLargeIcon(artwork ?: blankArtwork)
            .setContentIntent(content)
            .setDeleteIntent(close)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(
                Notification.Action.Builder(icon, if (playing) "暂停" else "播放", toggle).build()
            )
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "关闭", close).build()
            )
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            // IMPORTANCE_LOW 是 API 24 常量：能执行到这里必然 >= 26（方法入口已按 O 拦掉低版本），
            // 不要把这行挪到入口判断之外，否则 21–23 会直接 NoSuchFieldError
            NotificationChannel(CHANNEL_ID, "后台音频播放", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    /** 21–23 只有 stopForeground(boolean)；24 起用 STOP_FOREGROUND_REMOVE，避免 33+ 的废弃警告 */
    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun stopPlayback(clearPosition: Boolean = false) {
        handler.removeCallbacks(sessionTicker)
        // 停止/销毁前把本地音频进度落盘；播到结尾时改为清除
        val audioId = activeVideoId
        if (audioId > 0L && activeKind == KIND_AUDIO && activeLocal) {
            val pos = player?.currentPosition ?: lastPosition
            if (clearPosition) {
                AudioPositionStore.clear(this, audioId)
            } else if (pos > 0L) {
                AudioPositionStore.save(this, audioId, pos)
            }
        }
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        activeUrl = null
        activeVideoId = 0L
        activeKind = KIND_VIDEO
        activeLocal = false
        lastPosition = 0L
        metadataDurationMs = 0L
        artwork = null
        artworkLoadedFor = ""
        try { stopForegroundCompat() } catch (_: Exception) {}
        // 部分 ROM 上 24+ 的 STOP_FOREGROUND_REMOVE 不会立刻抹掉媒体通知，再 cancel 一次
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sessionTicker)
        // 服务销毁：在播标记一起复位，避免桌面图标/通知拿旧 id、旧「本地」标记去开页
        if (instance === this) {
            // 非 stopPlayback 路径（系统回收等）：补一次本地音频进度落盘
            val audioId = activeVideoId
            val pos = player?.currentPosition ?: lastPosition
            if (audioId > 0L && activeKind == KIND_AUDIO && activeLocal && pos > 0L) {
                AudioPositionStore.save(this, audioId, pos)
            }
            instance = null
            activeVideoId = 0L
            activeLocal = false
        }
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { stopForegroundCompat() } catch (_: Exception) {}
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {}
        session = null
        super.onDestroy()
    }
}
