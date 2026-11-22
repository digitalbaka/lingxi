package com.lingxi.classic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
import com.lingxi.classic.ui.main.MainActivity
import java.io.File

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

        /** 当前服务负责的内容，供页面判断能否取回进度 */
        @Volatile
        var activeUrl: String? = null
            private set

        /** 当前服务负责的内容 id：页面重建时即使 url 还没建好也能对上 */
        @Volatile
        var activeVideoId: Long = 0L
            private set

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
            headers: Map<String, String>
        ) {
            // onStop 在应用内跳页时也会触发：只有整个应用退到后台才把声音接到服务，
            // 避免「从播放页点进 UP 主页，旧视频声音还在响」
            if (!LingXiApp.isGoingBackground()) return
            activeUrl = url
            activeVideoId = videoId
            lastPosition = position
            val intent = Intent(context, BackgroundAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_COVER, cover)
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_HANDOFF_ID, videoId)
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
            val pos = lastPosition
            try {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            } catch (_: Exception) {}
            activeUrl = null
            activeVideoId = 0L
            return pos
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BackgroundAudioService::class.java))
            } catch (_: Exception) {}
            activeUrl = null
            activeVideoId = 0L
        }
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var title: String = ""
    private var cover: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    // 这两个 FLAG 在 26+ 已废弃（默认就会处理媒体键），但 21–25 必须显式打开，不能删
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
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
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        // 33+ 有带类型的 getSerializableExtra，但它同样要强转、且 21–32 只能用旧版，
        // 这里统一走旧 API，行为一致
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
            ?: HashMap()

        activeUrl = url
        activeVideoId = intent.getLongExtra(EXTRA_HANDOFF_ID, 0L)
        lastPosition = position
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
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateSessionState()
                        if (playbackState == Player.STATE_ENDED) stopPlayback()
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

        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "凌汐")
                .apply { if (cover.isNotBlank()) putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, cover) }
                .build()
        )
        updateSessionState()
    }

    private fun updateSessionState() {
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
            .build()
        session?.setPlaybackState(state)
        // 暂停时通知不再常驻；播放/缓冲中保持常驻，避免被系统一并清掉
        if (wantPlay != playing) refreshNotification(wantPlay)
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
        val content = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            piFlags
        )
        val icon = android.R.drawable.ic_media_play
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "凌汐" })
            .setContentText(if (playing) "正在后台播放音频" else "已暂停")
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

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        activeUrl = null
        activeVideoId = 0L
        lastPosition = 0L
        try { stopForegroundCompat() } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
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
