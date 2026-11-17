package com.lingxi.classic.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.reflect.TypeToken
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.ApiResponse
import com.lingxi.classic.data.model.Captcha
import com.lingxi.classic.data.model.LoginRequest
import com.lingxi.classic.data.model.LoginResult
import com.lingxi.classic.data.model.SendCodeRequest
import com.lingxi.classic.data.model.Verify2faRequest
import com.lingxi.classic.data.model.expiresAtMillis
import com.lingxi.classic.databinding.ActivityLoginBinding
import com.lingxi.classic.util.apiMessage
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * 登录：账号密码 →（按 mfaMethod 分流）认证器 TOTP / 邮箱验证码 / 恢复码。
 *
 * 站点规则：密码正确 ≠ 拿到登录态。命中 mfaRequired 时只有一张 5 分钟有效的 mfaTicket，
 * 必须用 /auth/verify-2fa 换 token，成功后才能把 accessToken 存下来。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var captcha: Captcha? = null
    private var mfaTicket: String? = null

    /** 'totp' | 'email'；登录第一步响应里的 data.mfaMethod */
    private var mfaMethod = "totp"
    private var emailMasked: String? = null
    private var canUseRecovery = false

    /** 当前是否把输入框当作恢复码（A1B2-C3D4）使用 */
    private var useRecovery = false
    private var sendCodeTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 图形验证码属于邮箱二次验证步骤（发送邮箱验证码时服务端才要求），此处只挂刷新，不预加载
        binding.webCaptcha.emptyHint = "点击刷新验证码"
        binding.webCaptcha.setOnClickListener { loadCaptcha() }

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnSendCode.setOnClickListener { sendLoginCode() }
        binding.btnConfirm2fa.setOnClickListener { confirm2fa() }

        binding.tvUseRecovery.setOnClickListener {
            useRecovery = !useRecovery
            binding.et2faCode.setText("")
            apply2faUi()
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

    /** 仅在二次验证区展开时刷新验证码（登录第一步失败不白白消耗） */
    private fun refreshCaptchaOnlyIfEmailStep() {
        if (binding.group2fa.visible && mfaMethod == "email" && !useRecovery) loadCaptcha()
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
                // 带上上次记住的设备令牌：仅对未绑定认证器的账号有效，能免掉邮箱二次验证
                val resp = ApiClient.apiService.login(
                    LoginRequest(username, password, AuthManager.deviceToken ?: "")
                )
                val result = resp.data
                when {
                    !resp.isSuccess || result == null -> {
                        toast(resp.message.ifBlank { "登录失败" })
                        refreshCaptchaOnlyIfEmailStep()
                    }
                    // 只有拿到 accessToken 才是真的登录成功；HTTP 200 + code 0 也可能只是「需要二次验证」
                    result.accessToken != null && result.user != null -> {
                        val user = result.user
                        AuthManager.saveLogin(
                            result.accessToken, user, result.refreshToken,
                            result.expiresAtMillis(), result.deviceToken
                        )
                        toast("欢迎回来，${user.nickname.ifBlank { user.username }}")
                        finish()
                    }
                    result.mfaRequired && result.mfaTicket != null -> {
                        mfaTicket = result.mfaTicket
                        emailMasked = result.emailMasked
                        canUseRecovery = result.canUseRecovery
                        // 以服务端 mfaMethod 为准（兼容缺失/大小写异常），邮箱步骤通过后可能还要再走 TOTP
                        mfaMethod = resolveMfaMethod(result)
                        useRecovery = false
                        binding.et2faCode.setText("")
                        apply2faUi()
                        if (mfaMethod == "email") {
                            loadCaptcha() // 进入邮箱验证码步骤才拉取图形验证码
                            toast("该账号开启了两步验证，请完成邮箱验证")
                        } else {
                            toast("请输入认证器 App 中显示的 6 位验证码")
                        }
                    }
                    else -> {
                        // code=0 但既没有 token 也没有 mfaTicket：属于异常响应，原样透出服务端文案
                        toast(resp.message.ifBlank { "登录失败：返回数据异常" })
                        refreshCaptchaOnlyIfEmailStep()
                    }
                }
            } catch (e: Exception) {
                toast(e.apiMessage("登录失败，请稍后重试"))
                refreshCaptchaOnlyIfEmailStep()
            } finally {
                binding.btnLogin.isEnabled = true
            }
        }
    }

    /** 按当前 mfaMethod / 恢复码状态刷新二次验证区（TOTP 不需要图形码与发信按钮） */
    private fun apply2faUi() {
        val totp = mfaMethod == "totp"
        binding.group2fa.visible = true
        binding.groupCaptcha.visible = !totp && !useRecovery
        binding.tvCaptchaHint.visible = !totp && !useRecovery
        binding.btnSendCode.visible = !totp && !useRecovery
        // TOTP 用户服务端不认 deviceToken（每次登录都必须输码），别给用户错误预期
        binding.cbRemember.visible = !totp
        binding.tvUseRecovery.visible = canUseRecovery

        binding.tv2faTitle.text = if (totp) "认证器验证" else "邮箱验证"
        binding.et2faCode.hint = when {
            useRecovery -> "恢复码（如 A1B2-C3D4）"
            totp -> "6 位验证码"
            else -> "邮箱验证码"
        }
        // 恢复码是字母数字混合 → 文本键盘；6 位码 / 邮箱验证码 → 数字键盘
        binding.et2faCode.inputType =
            if (useRecovery) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER

        binding.tvUseRecovery.text = when {
            useRecovery && totp -> "返回使用认证器验证"
            useRecovery -> "返回使用邮箱验证码"
            totp -> "无法使用认证器？使用恢复码"
            else -> "无法接收邮件？使用恢复码"
        }
        binding.tvEmailMasked.text = when {
            useRecovery -> "请输入绑定认证器时保存的一次性恢复码"
            totp -> "请输入认证器 App 中显示的 6 位验证码"
            else -> "验证码将发送至 ${emailMasked ?: "绑定邮箱"}"
        }
    }

    /** ticket 只有 5 分钟，过期只能回到第一步重输密码（没有续期接口） */
    private fun resetMfaStep() {
        mfaTicket = null
        useRecovery = false
        sendCodeTimer?.cancel()
        binding.group2fa.visible = false
        binding.et2faCode.setText("")
        binding.etCaptcha.setText("")
    }

    // ==================== 第二步（邮箱）：发送验证码 ====================

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
                } else if (!switchToTotpIfRequired(resp.message, null)) {
                    toast(resp.message.ifBlank { "发送失败" })
                    binding.btnSendCode.isEnabled = true
                    loadCaptcha()
                }
            } catch (e: Exception) {
                // 有些服务端会在「发信」阶段就回「请改用 TOTP」，这里同样要能切步骤
                val msg = e.apiMessage("发送失败，请稍后重试")
                if (!switchToTotpIfRequired(msg, null)) {
                    toast(msg)
                    binding.btnSendCode.isEnabled = true
                    loadCaptcha()
                }
            }
        }
    }

    // ==================== 第二步：TOTP / 邮箱验证码 / 恢复码 ====================

    private fun confirm2fa() {
        val ticket = mfaTicket ?: return
        val raw = binding.et2faCode.text.toString().trim()
        val code = if (useRecovery) normalizeRecovery(raw) else raw

        // 本地先校验，避免白白消耗服务端限频额度（setup/enable/disable 都是 10 分钟窗口计数）
        if (useRecovery) {
            if (code.length != 8 || !code.all { it.isLetterOrDigit() }) {
                toast("恢复码格式为 8 位字母数字，如 A1B2-C3D4")
                return
            }
        } else if (mfaMethod == "totp") {
            if (code.length != 6 || !code.all { it.isDigit() }) {
                toast("请输入 6 位数字验证码")
                return
            }
        } else if (code.isEmpty()) {
            toast("请输入邮箱验证码")
            return
        }

        binding.btnConfirm2fa.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.verify2fa(
                    Verify2faRequest(
                        ticket = ticket,
                        code = code,
                        useRecovery = useRecovery,
                        // TOTP 用户服务端不看 deviceToken，统一按默认 true 走
                        rememberDevice = mfaMethod == "totp" || binding.cbRemember.isChecked
                    )
                )
                handleVerifyResponse(resp)
            } catch (e: HttpException) {
                // 4xx 的文案在 errorBody 里；而且「还要继续验证」也可能以 4xx 返回，
                // 必须先按结构解析，不能一律当成失败文案弹掉
                val parsed = parseVerifyError(e)
                val msg = parsed?.message?.takeIf { it.isNotBlank() }
                if (!switchToTotpIfRequired(msg.orEmpty(), parsed?.data)) {
                    handle2faFailure(msg ?: "验证失败，请稍后重试")
                }
            } catch (e: Exception) {
                handle2faFailure(e.apiMessage("验证失败，请稍后重试"))
            } finally {
                binding.btnConfirm2fa.isEnabled = true
            }
        }
    }

    /**
     * verify-2fa 响应统一分流。**只认 code == 0**：HTTP 200 也可能是「还要下一步」。
     *
     * ① 有 accessToken + user → 存 token 进主页；
     * ② 没 token 但带 mfaTicket / mfaRequired（或文案要求改用认证器）→ 继续下一步验证，
     *    典型现象就是邮箱验证码提交后服务端要求「请输入 TOTP」；
     * ③ 其它 → 按失败提示。
     */
    private fun handleVerifyResponse(resp: ApiResponse<LoginResult>) {
        val result = resp.data
        if (resp.isSuccess && result != null) {
            val token = result.accessToken
            if (token != null && result.user != null) {
                sendCodeTimer?.cancel()
                AuthManager.saveLogin(
                    token, result.user, result.refreshToken,
                    result.expiresAtMillis(), result.deviceToken
                )
                toast("登录成功")
                finish()
                return
            }
            if (switchToTotpIfRequired(resp.message, result)) return
            if (hasNextStep(result)) {
                continueNextStep(result, resp.message)
                return
            }
            handle2faFailure(resp.message.ifBlank { "登录失败：返回数据异常" })
            return
        }
        if (result != null) {
            if (switchToTotpIfRequired(resp.message, result)) return
            if (hasNextStep(result)) {
                continueNextStep(result, resp.message)
                return
            }
        }
        handle2faFailure(resp.message.ifBlank { "验证失败" })
    }

    /** 未发 token 但带 mfaTicket / mfaRequired = 服务端还有下一步 */
    private fun hasNextStep(result: LoginResult): Boolean =
        result.accessToken == null && (result.mfaRequired || !result.mfaTicket.isNullOrBlank())

    /**
     * 解析服务端要求的第一步验证方式。
     * mfaMethod 明确给出时以服务端为准（顺带兼容大小写/空格）；
     * 缺失时才兜底：有 emailMasked 走邮箱码，否则按认证器处理。
     */
    private fun resolveMfaMethod(result: LoginResult): String =
        when (result.mfaMethod?.trim()?.lowercase()) {
            "totp" -> "totp"
            "email" -> "email"
            else -> if (!result.emailMasked.isNullOrBlank()) "email" else "totp"
        }

    /** 文案表示「请改用认证器验证」 */
    private fun String.looksLikeTotpRequired(): Boolean =
        contains("TOTP", ignoreCase = true) || contains("认证器")

    /**
     * 服务端要求改走 TOTP 时切到认证器步骤（有新 ticket 就用新的）。
     * 返回 true 表示已切换，调用方不要再弹失败提示。
     */
    private fun switchToTotpIfRequired(message: String, data: LoginResult?): Boolean {
        // 已经停在认证器步骤：这多半是验证码本身的错误，交给原有失败分流，别清空输入原地打转
        if (mfaMethod == "totp") return false
        val dataSaysTotp = data != null && hasNextStep(data) && resolveMfaMethod(data) == "totp"
        if (!dataSaysTotp && !message.looksLikeTotpRequired()) return false
        val next = if (dataSaysTotp && data != null) data else LoginResult(mfaMethod = "totp")
        continueNextStep(
            LoginResult(
                mfaMethod = "totp",
                mfaTicket = next.mfaTicket,
                canUseRecovery = next.canUseRecovery,
                emailMasked = next.emailMasked ?: emailMasked
            ),
            message
        )
        return true
    }

    /** 推进到服务端指定的下一步（目前只有 totp / email 两种） */
    private fun continueNextStep(result: LoginResult, message: String) {
        val next = resolveMfaMethod(result)
        result.mfaTicket?.takeIf { it.isNotBlank() }?.let { mfaTicket = it }
        mfaMethod = next
        useRecovery = false
        if (result.canUseRecovery) canUseRecovery = true
        result.emailMasked?.takeIf { it.isNotBlank() }?.let { emailMasked = it }

        sendCodeTimer?.cancel()
        binding.et2faCode.setText("")
        binding.etCaptcha.setText("")
        // 倒计时可能被打断在「xxs 后重发」的禁用态，切步骤时复位，避免后续邮箱步骤发不了码
        binding.btnSendCode.text = "发送验证码"
        binding.btnSendCode.isEnabled = true
        apply2faUi()
        if (next == "email") {
            loadCaptcha()
            toastLong(message.ifBlank { "请继续完成邮箱验证" })
        } else {
            binding.et2faCode.requestFocus()
            toastLong(message.ifBlank { "请输入认证器 App 中显示的 6 位验证码" })
        }
    }

    /** 解析 HttpException 的响应体（含 data），用于区分「继续验证」与「失败」 */
    private fun parseVerifyError(e: HttpException): ApiResponse<LoginResult>? =
        try {
            val raw = e.response()?.errorBody()?.string()
            if (raw.isNullOrBlank()) {
                null
            } else {
                val type = object : TypeToken<ApiResponse<LoginResult>>() {}.type
                ApiClient.gson.fromJson<ApiResponse<LoginResult>>(raw, type)
            }
        } catch (ex: Exception) {
            null
        }

    /**
     * 失败分流。原则：不自动重试（重放保护 + 限频），只给可执行的下一步。
     */
    private fun handle2faFailure(message: String) {
        val msg = message.ifBlank { "验证失败" }
        when {
            // 登录票据无效或已过期，请重新登录
            msg.contains("票据") -> {
                resetMfaStep()
                toastLong("二次验证已超时，请重新输入密码登录")
            }
            // 同一时间步的码只成功一次；再点也是同一个失败，提示等下一个
            msg.contains("刚刚已经用过") -> {
                toastLong("这个验证码刚刚已经用过了，请等下一个验证码（约 30 秒）再试")
            }
            msg.contains("恢复码不正确") -> {
                toast("恢复码不正确或已被使用；可返回密码登录重试")
            }
            // 该账号未绑定邮箱，请改用恢复码登录
            msg.contains("未绑定邮箱") -> {
                if (canUseRecovery && !useRecovery) {
                    useRecovery = true
                    binding.et2faCode.setText("")
                    apply2faUi()
                    toastLong("该账号未绑定邮箱，请改用恢复码登录")
                } else {
                    toast(msg)
                }
            }
            else -> toast(msg)
        }
    }

    /** 恢复码：横线可省略、大小写不敏感（服务端会归一化，这里统一成大写无横线） */
    private fun normalizeRecovery(input: String): String =
        input.uppercase().replace("-", "").replace(" ", "").trim()

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
