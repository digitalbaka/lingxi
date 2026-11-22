package com.lingxi.classic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.lingxi.classic.data.api.TotpGate
import com.lingxi.classic.player.VideoCacheManager

class LingXiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 记录前台 Activity：403 TOTP_REQUIRED 门禁命中时要直接跳账号安全页
        TotpGate.attach(this)
        // 启动时修剪视频缓存：超出用户所选上限则整体清理（必须在 SimpleCache 创建前）
        VideoCacheManager.trimIfOverLimit(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedCount > 0) startedCount--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private lateinit var instance: LingXiApp

        /** 已 started 的 Activity 数量（主线程读写） */
        private var startedCount = 0

        /**
         * 页面 onStop 里调用：此时新页面（若为应用内跳转）已经 started，自己还没被计数减掉。
         * 只剩自己一台，说明用户是把整个应用退到后台，而不是应用内切换页面。
         */
        @JvmStatic
        fun isGoingBackground(): Boolean = startedCount <= 1

        @JvmStatic
        fun get(): Context = instance
    }
}
