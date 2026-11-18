package com.lingxi.classic.ui.settings

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.TotpSetupResult
import com.lingxi.classic.data.model.TotpStatus
import com.lingxi.classic.databinding.ActivitySecurityBinding
import com.lingxi.classic.util.SvgCaptchaView
import com.lingxi.classic.util.apiMessage
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号安全：绑定 / 解绑认证器（TOTP）、查看与重新生成恢复码。
 *
 * 也被 403 TOTP_REQUIRED 门禁直接拉起（EXTRA_FORCE_2FA），此时绑定成功即解除拦截，不需要重新登录。
 */
class SecurityActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_2FA = "force_2fa"
        const val EXTRA_GATE_DEADLINE = "gate_deadline"
        const val EXTRA_GATE_DAYS_LEFT = "gate_days_left"
    }

    private lateinit var binding: ActivitySecurityBinding

    private var regenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBind.setOnClickListener { showBindPasswordDialog() }
        binding.btnUnbind.setOnClickListener { showUnbindDialog() }
        binding.btnRegen.setOnClickListener { confirmRegenerateRecoveryCodes() }

        val forced = intent.getBooleanExtra(EXTRA_FORCE_2FA, false)
        binding.tvGateBanner.visible = forced
        if (forced) {
            val gateDays = intent.getIntExtra(EXTRA_GATE_DAYS_LEFT, 0)
            binding.tvGateBanner.text = buildString {
                append("站点已启用强制双因素认证，请先绑定认证器后再继续使用。")
                if (gateDays > 0) append("宽限期还剩 $gateDays 天。")
                append("绑定成功后拦截立即解除，无需重新登录。")
            }
        }

        loadStatus()
    }

    // ==================== 状态 ====================

    private fun loadStatus() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getTotpStatus()
                val data = resp.data
                if (!resp.isSuccess || data == null) {
                    binding.tvStatus.text = "状态获取失败"
                    binding.tvPolicy.text = resp.message.ifBlank { "请稍后重试" }
                    binding.btnBind.visible = false
                    binding.btnUnbind.visible = false
                    binding.btnRegen.visible = false
                    return@launch
                }
                render(data)
            } catch (e: Exception) {
                binding.tvStatus.text = "状态获取失败"
                binding.tvPolicy.text = e.apiMessage("网络错误，请稍后重试")
            }
        }
    }

    private fun render(s: TotpStatus) {
        binding.tvStatus.text = if (s.enabled) "已开启（认证器 TOTP）" else "未开启"
        binding.tvPolicy.text = when {
            !s.required -> "站点当前未强制两步验证，建议主动开启以保护账号。"
            s.blocked -> "站点已启用强制两步验证，宽限期已结束：未绑定认证器前业务接口会被拦截。"
            s.daysLeft > 0 -> "站点已启用强制两步验证，还剩 ${s.daysLeft} 天" +
                "（截止 ${formatDeadline(s.deadline)}），请尽快绑定。"
            else -> "站点已启用强制两步验证，请尽快绑定认证器。"
        }
        binding.tvRecovery.visible = s.enabled
        binding.tvRecovery.text = if (s.recoveryRemaining <= 2) {
            "剩余恢复码：${s.recoveryRemaining} 个，建议重新生成（旧码会作废）。"
        } else {
            "剩余恢复码：${s.recoveryRemaining} 个"
        }
        // 已绑定只给解绑/换绑入口（服务端不允许重复 setup）
        binding.btnBind.visible = !s.enabled
        binding.btnUnbind.visible = s.enabled
        binding.btnRegen.visible = s.enabled
    }

    private fun formatDeadline(ms: Long): String {
        if (ms <= 0L) return "未知"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (e: Exception) {
            "未知"
        }
    }

    // ==================== 绑定：setup → enable → 展示恢复码 ====================

    private fun showBindPasswordDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 0)
        }
        container.addView(TextView(ctx).apply {
            text = "请先输入当前登录密码。仅凭被盗的登录态就能绑定认证器，等于帮攻击者上锁，所以这一步必须验证密码。"
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text_sub))
        })
        val etPassword = EditText(ctx).apply {
            hint = "当前密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        container.addView(etPassword)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("绑定认证器")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("获取二维码", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val password = etPassword.text.toString()
                if (password.isEmpty()) { toast("请输入当前密码"); return@setOnClickListener }
                positive.isEnabled = false
                positive.text = "获取中…"
                lifecycleScope.launch {
                    try {
                        val resp = ApiClient.apiService.setupTotp(mapOf("password" to password))
                        val data = resp.data
                        if (resp.isSuccess && data != null) {
                            dialog.dismiss()
                            showSetupDialog(data)
                        } else {
                            toast(resp.message.ifBlank { "获取二维码失败" })
                            positive.isEnabled = true
                            positive.text = "获取二维码"
                        }
                    } catch (e: Exception) {
                        toast(e.apiMessage("获取二维码失败，请稍后重试"))
                        positive.isEnabled = true
                        positive.text = "获取二维码"
                    }
                }
            }
        }
        dialog.show()
    }

    /** 展示二维码（qr 是 SVG 源码，复用 SvgCaptchaView 自绘）+ 密钥手输兜底，等待 6 位码确认 */
    private fun showSetupDialog(setup: TotpSetupResult) {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 0)
        }

        container.addView(TextView(ctx).apply {
            text = "1. 打开 Google / Microsoft Authenticator 等认证器 App 扫描二维码\n" +
                "2. 扫不了就手动输入下方密钥\n3. 输入 App 上显示的 6 位验证码完成绑定"
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text_sub))
        })

        // 二维码外边留白（静区）：扫描器需要四周留白，直接铺满容易扫不出
        val qrBox = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(200.dp(ctx), 200.dp(ctx)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 12.dp(ctx)
            }
            if (setup.qr.isNotBlank()) setBackgroundColor(Color.WHITE)
        }
        val qrView = SvgCaptchaView(ctx).apply {
            emptyHint = "二维码解析失败，请使用下方密钥手输"
        }
        qrView.setSvg(setup.qr)
        qrBox.addView(qrView, FrameLayout.LayoutParams(176.dp(ctx), 176.dp(ctx), Gravity.CENTER))
        container.addView(qrBox)

        container.addView(TextView(ctx).apply {
            text = "密钥：${setup.secretGrouped}"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text))
            setTextIsSelectable(true)
            setPadding(0, 12.dp(ctx), 0, 0)
        })
        container.addView(TextView(ctx).apply {
            text = "账号：${setup.account.ifBlank { "当前账号" }}"
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text_sub))
        })

        val etCode = EditText(ctx).apply {
            hint = "App 显示的 6 位验证码"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setPadding(0, 14.dp(ctx), 0, 0)
        }
        container.addView(etCode)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("绑定认证器")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setNegativeButton("取消", null)
            .setPositiveButton("确认绑定", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val code = etCode.text.toString().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    toast("请输入 6 位数字验证码")
                    return@setOnClickListener
                }
                positive.isEnabled = false
                positive.text = "绑定中…"
                lifecycleScope.launch {
                    try {
                        val resp = ApiClient.apiService.enableTotp(mapOf("code" to code))
                        val data = resp.data
                        if (resp.isSuccess && data != null) {
                            dialog.dismiss()
                            // 绑定用掉的这个时间步已被服务端记账，紧接着登录不能再用同一个码
                            toastLong("绑定成功。同一个验证码不能立即用于登录，请等下一个码")
                            showRecoveryCodes(data.codes ?: emptyList())
                            loadStatus()
                        } else {
                            toast(resp.message.ifBlank { "绑定失败" })
                            positive.isEnabled = true
                            positive.text = "确认绑定"
                        }
                    } catch (e: Exception) {
                        toast(e.apiMessage("绑定失败，请稍后重试"))
                        positive.isEnabled = true
                        positive.text = "确认绑定"
                    }
                }
            }
        }
        dialog.show()
    }

    // ==================== 解绑 ====================

    private fun showUnbindDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 0)
        }
        container.addView(TextView(ctx).apply {
            text = "解绑需要当前密码 + 一枚有效验证码。\n\n" +
                "注意：解绑后该账号仅凭密码即可登录；之后换绑会重新生成一批恢复码，现有恢复码全部作废。"
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text_sub))
        })
        val etPassword = EditText(ctx).apply {
            hint = "当前密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etCode = EditText(ctx).apply {
            hint = "认证器 6 位验证码"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        container.addView(etPassword)
        container.addView(etCode)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("解绑认证器")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认解绑", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val password = etPassword.text.toString()
                val code = etCode.text.toString().trim()
                if (password.isEmpty()) { toast("请输入当前密码"); return@setOnClickListener }
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    toast("请输入 6 位数字验证码")
                    return@setOnClickListener
                }
                positive.isEnabled = false
                positive.text = "解绑中…"
                lifecycleScope.launch {
                    try {
                        val resp = ApiClient.apiService.disableTotp(
                            mapOf("password" to password, "code" to code)
                        )
                        if (resp.isSuccess) {
                            toast("已解绑认证器")
                            dialog.dismiss()
                            loadStatus()
                        } else {
                            toast(resp.message.ifBlank { "解绑失败" })
                            positive.isEnabled = true
                            positive.text = "确认解绑"
                        }
                    } catch (e: Exception) {
                        toast(e.apiMessage("解绑失败，请稍后重试"))
                        positive.isEnabled = true
                        positive.text = "确认解绑"
                    }
                }
            }
        }
        dialog.show()
    }

    // ==================== 恢复码 ====================

    private fun confirmRegenerateRecoveryCodes() {
        if (regenerating) return
        AlertDialog.Builder(this)
            .setTitle("重新生成恢复码")
            .setMessage("生成后旧的一批恢复码立即作废、无法找回。确定继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ -> regenerateRecoveryCodes() }
            .show()
    }

    private fun regenerateRecoveryCodes() {
        if (regenerating) return
        regenerating = true
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.regenerateRecoveryCodes(emptyMap<String, String>())
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    showRecoveryCodes(data.codes ?: emptyList())
                    loadStatus()
                } else {
                    toast(resp.message.ifBlank { "生成失败" })
                }
            } catch (e: Exception) {
                toast(e.apiMessage("生成失败，请稍后重试"))
            } finally {
                regenerating = false
            }
        }
    }

    /**
     * 恢复码明文只出现这一次：必须展示完整一批、并且勾选「我已抄写」才能关闭。
     * 这是用户换手机后唯一的自救路径，不能一点就过。
     */
    private fun showRecoveryCodes(codes: List<String>) {
        if (codes.isEmpty()) {
            toast("操作成功")
            return
        }
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 0)
        }
        container.addView(TextView(ctx).apply {
            text = "以下 ${codes.size} 个恢复码只显示这一次（服务端只存摘要，关闭后无法找回）。" +
                "手机丢失时用它登录，请立刻抄写并离线保管："
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_red))
        })

        val listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dp(ctx), 0, 6.dp(ctx))
        }
        codes.forEach { code ->
            listBox.addView(TextView(ctx).apply {
                text = code
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text))
                setTextIsSelectable(true)
                setPadding(0, 6.dp(ctx), 0, 6.dp(ctx))
            })
        }
        container.addView(listBox)

        val confirmBox = CheckBox(ctx).apply {
            text = "我已抄写并妥善保管这 ${codes.size} 个恢复码"
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.lx_text))
        }
        container.addView(confirmBox)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("一次性恢复码")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("我已抄写完毕", null)
            // 未确认前不允许返回键/点外部关闭，避免明文丢失
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = confirmBox.isChecked
            confirmBox.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
            positive.setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }
}
