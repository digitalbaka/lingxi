package com.lingxi.classic.ui.auth

import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.Captcha
import com.lingxi.classic.data.model.RegisterRequest
import com.lingxi.classic.data.model.SendCodeRequest
import com.lingxi.classic.data.model.expiresAtMillis
import com.lingxi.classic.databinding.ActivityRegisterBinding
import com.lingxi.classic.util.centerContentOnTablet
import com.lingxi.classic.util.toast
import kotlinx.coroutines.launch

/** 注册：用户名/密码/邮箱 + 图形验证码 + 邮箱验证码 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    private var captcha: Captcha? = null
    private var sendCodeTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 平板：表单列限宽居中，避免宽屏下输入框被拉得过长
        binding.formRoot.centerContentOnTablet(480)

        binding.webCaptcha.setOnClickListener { loadCaptcha() }
        loadCaptcha()

        binding.btnSendCode.setOnClickListener { sendRegisterCode() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.tvToLogin.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        sendCodeTimer?.cancel()
        super.onDestroy()
    }

    /** 自绘 SVG 验证码，不依赖 WebView（兼容老设备） */
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

    private fun sendRegisterCode() {
        val email = binding.etEmail.text.toString().trim()
        val cap = captcha
        val code = binding.etCaptcha.text.toString().trim()
        if (email.isEmpty()) { toast("请先填写邮箱"); return }
        if (cap == null || code.isEmpty()) { toast("请先填写图形验证码"); return }

        binding.btnSendCode.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.sendEmailCode(
                    SendCodeRequest(
                        purpose = "register",
                        email = email,
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

    private fun doRegister() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val password2 = binding.etPassword2.text.toString()
        val email = binding.etEmail.text.toString().trim()
        val emailCode = binding.etEmailCode.text.toString().trim()
        val cap = captcha
        val captchaCode = binding.etCaptcha.text.toString().trim()

        if (username.isEmpty()) { toast("请输入用户名"); return }
        if (password.length < 6) { toast("密码至少 6 位"); return }
        if (password != password2) { toast("两次输入的密码不一致"); return }
        if (email.isEmpty()) { toast("请输入邮箱"); return }
        if (emailCode.isEmpty()) { toast("请输入邮箱验证码"); return }
        if (cap == null || captchaCode.isEmpty()) { toast("请输入图形验证码"); return }

        binding.btnRegister.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.register(
                    RegisterRequest(
                        username = username,
                        password = password,
                        email = email,
                        emailCode = emailCode,
                        captchaId = cap.id,
                        captchaCode = captchaCode
                    )
                )
                val result = resp.data
                if (resp.isSuccess) {
                    if (result?.accessToken != null && result.user != null) {
                        AuthManager.saveLogin(result.accessToken, result.user, result.refreshToken, result.expiresAtMillis())
                    }
                    toast("注册成功，欢迎来到凌汐")
                    finish()
                } else {
                    toast(resp.message.ifBlank { "注册失败" })
                    loadCaptcha()
                }
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
                loadCaptcha()
            } finally {
                binding.btnRegister.isEnabled = true
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
