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
        var req = Glide.with(iv).load(url).apply(OPTS)
        if (circle) req = req.circleCrop()
        else req = req.placeholder(R.drawable.bg_cover_placeholder)
        req.into(iv)
    }

    fun clear(iv: ImageView) = Glide.with(iv).clear(iv)
}
