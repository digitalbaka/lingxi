package com.lingxi.classic

import android.app.Application
import android.content.Context
import com.lingxi.classic.player.VideoCacheManager

class LingXiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 启动时修剪视频缓存：超出用户所选上限则整体清理（必须在 SimpleCache 创建前）
        VideoCacheManager.trimIfOverLimit(this)
    }

    companion object {
        private lateinit var instance: LingXiApp

        @JvmStatic
        fun get(): Context = instance
    }
}
