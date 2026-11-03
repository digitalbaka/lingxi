package com.lingxi.classic.ui.common

import android.content.Context
import android.content.Intent
import com.lingxi.classic.data.model.Video

/** 全局导航契约：视频 → 播放详情；文章/音频 → 阅读详情 */
object Navigator {

    const val EXTRA_VIDEO_ID = "extra_video_id"
    const val EXTRA_UID = "extra_uid"

    fun openVideo(context: Context, video: Video) {
        openVideo(context, video.id, video.kind)
    }

    fun openVideo(context: Context, id: Long, kind: String = "video") {
        val cls = if (kind == "article" || kind == "audio")
            com.lingxi.classic.ui.article.ArticleActivity::class.java
        else
            com.lingxi.classic.ui.player.PlayerActivity::class.java
        context.startActivity(Intent(context, cls).putExtra(EXTRA_VIDEO_ID, id))
    }

    fun openSpace(context: Context, uid: Long) {
        context.startActivity(
            Intent(context, com.lingxi.classic.ui.space.UserSpaceActivity::class.java)
                .putExtra(EXTRA_UID, uid)
        )
    }
}
