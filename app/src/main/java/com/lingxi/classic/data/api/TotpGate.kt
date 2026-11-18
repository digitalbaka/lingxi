package com.lingxi.classic.data.api

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lingxi.classic.ui.settings.SecurityActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * 门禁事件：HTTP 403 且 data.code === "TOTP_REQUIRED"。
 *
 * 注意 403 有两种含义（封禁 / 门禁），只能靠 data.code 区分；
 * 命中后直接引导到账号安全页绑定认证器，而不是提示「没有权限」。
 * 被拦状态下 /users/me/totp、recovery-codes 以及 auth 系列接口仍放行，绑定成功即解除。
 */
object TotpGate {

    private const val TAG = "LingXiTotpGate"

    /** 同一个门禁在短时间内会被多个并发请求同时命中，只跳一次 */
    private const val COOLDOWN_MS = 8_000L

    private var current: WeakReference<Activity?> = WeakReference(null)
    private val lastNotifyAt = AtomicLong(0L)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 在 Application.onCreate 里调用一次，用于记录当前前台 Activity */
    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current = WeakReference<Activity?>(activity)
            }

            // 只记录最近一次 resumed 的 Activity；pause 不清空，避免「请求在页面切换瞬间返回」时丢事件
            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (current.get() === activity) current = WeakReference(null)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /**
     * 由网络层调用。带冷却与「已在安全页」判断，避免多请求连击反复弹页。
     * deadline / daysLeft 来自 403 响应体（门禁里的 daysLeft 恒为 0，真实剩余天数由安全页自查）。
     */
    fun notifyRequired(deadline: Long, daysLeft: Int) {
        val now = System.currentTimeMillis()
        val last = lastNotifyAt.get()
        if (now - last < COOLDOWN_MS) return
        if (!lastNotifyAt.compareAndSet(last, now)) return
        // 拦截器在 OkHttp 工作线程上，启动 Activity 必须切回主线程
        mainHandler.post { openSecurityPage(deadline, daysLeft) }
    }

    private fun openSecurityPage(deadline: Long, daysLeft: Int) {
        val activity = current.get() ?: return
        if (activity.isFinishing) return
        if (activity is SecurityActivity) return

        try {
            activity.startActivity(
                Intent(activity, SecurityActivity::class.java)
                    .putExtra(SecurityActivity.EXTRA_FORCE_2FA, true)
                    .putExtra(SecurityActivity.EXTRA_GATE_DEADLINE, deadline)
                    .putExtra(SecurityActivity.EXTRA_GATE_DAYS_LEFT, daysLeft)
            )
        } catch (e: Exception) {
            Log.w(TAG, "open security page failed: ${e.message}")
        }
    }
}
