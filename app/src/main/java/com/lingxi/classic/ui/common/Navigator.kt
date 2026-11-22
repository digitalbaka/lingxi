package com.lingxi.classic.ui.common

import android.content.Context
import android.content.Intent
import com.lingxi.classic.data.model.Video

/** 全局导航契约：视频 → 播放详情；文章/音频 → 阅读详情 */
object Navigator {

    const val EXTRA_VIDEO_ID = "extra_video_id"
    const val EXTRA_UID = "extra_uid"
    /** 只播本地：命中离线缓存时直接读本地文件，跳过详情/弹幕/评论等所有网络请求 */
    const val EXTRA_LOCAL_ONLY = "extra_local_only"

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

    /** 打开已缓存内容的只播本地分支：只查本地索引与文件，不请求任何接口 */
    fun openLocal(context: Context, id: Long, kind: String) {
        val cls = if (kind == "audio")
            com.lingxi.classic.ui.article.ArticleActivity::class.java
        else
            com.lingxi.classic.ui.player.PlayerActivity::class.java
        context.startActivity(
            Intent(context, cls)
                .putExtra(EXTRA_VIDEO_ID, id)
                .putExtra(EXTRA_LOCAL_ONLY, true)
        )
    }

    fun openSpace(context: Context, uid: Long) {
        context.startActivity(
            Intent(context, com.lingxi.classic.ui.space.UserSpaceActivity::class.java)
                .putExtra(EXTRA_UID, uid)
        )
    }
}
