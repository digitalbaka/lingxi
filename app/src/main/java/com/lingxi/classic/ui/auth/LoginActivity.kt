package com.lingxi.classic.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Captcha
import com.lingxi.classic.data.model.LoginRequest
import com.lingxi.classic.data.model.SendCodeRequest
import com.lingxi.classic.data.model.Verify2faRequest
import com.lingxi.classic.databinding.ActivityLoginBinding
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch

/** 登录：账号密码 + 图形验证码；命中 mfaRequired 时展开邮箱两步验证 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var captcha: Captcha? = null
    private var mfaTicket: String? = null
    private var useRecovery = false
    private var sendCodeTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webCaptcha.setOnClickListener { loadCaptcha() }
        loadCaptcha()

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnSendCode.setOnClickListener { sendLoginCode() }
        binding.btnConfirm2fa.setOnClickListener { confirm2fa() }

        binding.tvUseRecovery.setOnClickListener {
            useRecovery = !useRecovery
            binding.et2faCode.hint = if (useRecovery) "恢复码" else "邮箱验证码"
            binding.tvUseRecovery.text = if (useRecovery) "返回使用邮箱验证码" else "无法接收邮件？使用恢复码"
            binding.btnSendCode.visible = !useRecovery
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.tvForgot.setOnClickListener {
            toastLong("请前往网页端 lingxi.xiguastudio.top 找回密码")
        }
    }

    override fun onDestroy() {
        sendCodeTimer?.cancel()
        super.onDestroy()
    }

    // ==================== 图形验证码（自绘 SVG，不依赖 WebView） ====================

    private fun renderCaptcha(svg: String) {
        binding.webCaptcha.setSvg(svg)
    }

    private fun loadCaptcha() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getCaptcha()
                if (resp.isSuccess && resp.data != null) {
                    captcha = resp.data
                    renderCaptcha(resp.data.svg)
                    binding.etCaptcha.setText("")
                } else {
                    toast(resp.message.ifBlank { "验证码加载失败，点图片重试" })
                }
            } catch (e: Exception) {
                toast("验证码加载失败，点图片重试")
            }
        }
    }

    // ==================== 第一步：账号密码登录 ====================

    private fun doLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty()) { toast("请输入用户名"); return }
        if (password.isEmpty()) { toast("请输入密码"); return }

        binding.btnLogin.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.login(LoginRequest(username, password))
                val result = resp.data
                when {
                    !resp.isSuccess || result == null -> {
                        toast(resp.message.ifBlank { "登录失败" })
                        loadCaptcha()
                    }
                    result.accessToken != null && result.user != null -> {
                        AuthManager.saveLogin(result.accessToken, result.user, result.refreshToken)
                        toast("欢迎回来，${result.user.nickname.ifBlank { result.user.username }}")
                        finish()
                    }
                    result.mfaRequired && result.mfaTicket != null -> {
                        mfaTicket = result.mfaTicket
                        binding.group2fa.visible = true
                        binding.tvEmailMasked.text = "验证码将发送至 ${result.emailMasked ?: "绑定邮箱"}"
                        binding.tvUseRecovery.visible = result.canUseRecovery
                        toast("该账号开启了两步验证，请完成邮箱验证")
                    }
                    else -> {
                        toast("登录失败：返回数据异常")
                        loadCaptcha()
                    }
                }
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
                loadCaptcha()
            } finally {
                binding.btnLogin.isEnabled = true
            }
        }
    }

    // ==================== 第二步：邮箱验证码 ====================

    private fun sendLoginCode() {
        val ticket = mfaTicket ?: return
        val cap = captcha
        val code = binding.etCaptcha.text.toString().trim()
        if (cap == null || code.isEmpty()) { toast("请先填写图形验证码"); return }

        binding.btnSendCode.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.sendEmailCode(
                    SendCodeRequest(
                        purpose = "login",
                        ticket = ticket,
                        captchaId = cap.id,
                        captchaCode = code
                    )
                )
                if (resp.isSuccess) {
                    toast("验证码已发送，请查收邮件")
                    startSendCodeCountdown(resp.data?.resendAfter ?: 60)
                } else {
                    toast(resp.message.ifBlank { "发送失败" })
                    binding.btnSendCode.isEnabled = true
                    loadCaptcha()
                }
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
                binding.btnSendCode.isEnabled = true
                loadCaptcha()
            }
        }
    }

    private fun confirm2fa() {
        val ticket = mfaTicket ?: return
        val code = binding.et2faCode.text.toString().trim()
        if (code.isEmpty()) { toast(if (useRecovery) "请输入恢复码" else "请输入邮箱验证码"); return }

        binding.btnConfirm2fa.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.verify2fa(
                    Verify2faRequest(
                        ticket = ticket,
                        code = code,
                        useRecovery = useRecovery,
                        rememberDevice = binding.cbRemember.isChecked
                    )
                )
                val result = resp.data
                if (resp.isSuccess && result?.accessToken != null && result.user != null) {
                    AuthManager.saveLogin(result.accessToken, result.user, result.refreshToken)
                    toast("登录成功")
                    finish()
                } else {
                    toast(resp.message.ifBlank { "验证失败" })
                }
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
            } finally {
                binding.btnConfirm2fa.isEnabled = true
            }
        }
    }

    private fun startSendCodeCountdown(seconds: Int) {
        sendCodeTimer?.cancel()
        sendCodeTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.btnSendCode.text = "${millisUntilFinished / 1000}s 后重发"
                binding.btnSendCode.isEnabled = false
            }
            override fun onFinish() {
                binding.btnSendCode.text = "发送验证码"
                binding.btnSendCode.isEnabled = true
            }
        }.start()
    }
}
