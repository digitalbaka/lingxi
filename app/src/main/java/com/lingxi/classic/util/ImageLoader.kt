package com.lingxi.classic.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.lingxi.classic.R

/** Glide 统一入口：RGB_565 省一半位图内存，1GB 设备友好 */
object ImageLoader {

    private val OPTS = RequestOptions()
        .format(DecodeFormat.PREFER_RGB_565)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)

    fun load(iv: ImageView, url: String?, circle: Boolean = false) {
        if (iv.context == null) return
        if (url.isNullOrEmpty()) {
            // 无图：直接显示内置默认图，避免灰底占位造成"加载失败"观感
            Glide.with(iv).clear(iv)
            iv.setImageResource(if (circle) R.drawable.ic_default_avatar else R.drawable.ic_default_cover)
            return
        }
        var req = Glide.with(iv).load(url).apply(OPTS)
        req = if (circle) req.circleCrop().placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
        else req.placeholder(R.drawable.ic_default_cover).error(R.drawable.ic_default_cover)
        req.into(iv)
    }

    fun clear(iv: ImageView) = Glide.with(iv).clear(iv)
}
