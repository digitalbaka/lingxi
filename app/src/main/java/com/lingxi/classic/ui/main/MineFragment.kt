package com.lingxi.classic.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.CoinLog
import com.lingxi.classic.databinding.FragmentMineBinding
import com.lingxi.classic.databinding.ItemMineMenuBinding
import com.lingxi.classic.ui.auth.LoginActivity
import com.lingxi.classic.ui.message.MessageActivity
import com.lingxi.classic.ui.node.NodeSelectActivity
import com.lingxi.classic.ui.settings.SettingsActivity
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong
import com.lingxi.classic.util.unwrap
import com.lingxi.classic.util.visible
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 「我的」Tab：登录态入口 + 签到/小鱼干/大会员/消息 + 菜单列表 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private var checkedInToday = false

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    companion object {
        @JvmStatic
        fun newInstance(): MineFragment = MineFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnLogin.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        binding.actionCheckin.setOnClickListener { doCheckin() }
        binding.actionCoins.setOnClickListener { showCoinsDialog() }
        binding.actionVip.setOnClickListener { showVipDialog() }
        binding.actionMessage.setOnClickListener {
            if (!AuthManager.isLoggedIn) { requireContext().toast("请先登录"); return@setOnClickListener }
            startActivity(Intent(requireContext(), MessageActivity::class.java))
        }
        binding.ivAvatar.setOnClickListener {
            if (!AuthManager.isLoggedIn) {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
        }
        buildMenu()
    }

    override fun onResume() {
        super.onResume()
        refreshLoginState()
        refreshUnread()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 登录态与资料 ====================

    private fun refreshLoginState() {
        val loggedIn = AuthManager.isLoggedIn
        binding.btnLogin.visible = !loggedIn
        binding.gridActions.visible = loggedIn
        if (!loggedIn) {
            binding.tvNickname.text = "未登录"
            binding.tvLevel.visible = false
            binding.tvUid.visible = false
            binding.groupExp.visible = false
            binding.badgeUnread.visible = false
            binding.tvMsgBadge.visible = false
            binding.ivAvatar.setImageResource(R.drawable.bg_cover_placeholder)
            buildMenu()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val me = ApiClient.apiService.getMyProfile().unwrap(requireContext()) ?: return@launch
                val user = me.user ?: return@launch
                binding.tvNickname.text = user.nickname.ifBlank { user.username }
                binding.tvLevel.visible = true
                binding.tvLevel.text = "LV ${me.level?.level ?: user.level}"
                binding.tvUid.visible = true
                binding.tvUid.text = "UID: ${user.id}"
                binding.groupExp.visible = true
                val percent = (me.level?.percent ?: 0).coerceIn(0, 100)
                binding.progressExp.progress = percent
                binding.tvExp.text = if (me.level?.maxed == true) "已满级"
                    else "经验 ${me.level?.current ?: 0}/${me.level?.next ?: 0}（$percent%）"
                ImageLoader.load(binding.ivAvatar, user.getFullAvatarUrl(ApiClient.BASE_URL), circle = true)
                checkedInToday = me.checkedInToday
                updateCheckinUi()
            } catch (e: Exception) {
                requireContext().toast("加载资料失败：${e.message}")
            }
        }
        buildMenu()
    }

    private fun refreshUnread() {
        if (!AuthManager.isLoggedIn) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val summary = ApiClient.apiService.getNotificationSummary().unwrap() ?: return@launch
                val total = summary.unread
                binding.badgeUnread.visible = total > 0
                binding.tvMsgBadge.visible = total > 0
                binding.tvMsgBadge.text = if (total > 99) "99+" else total.toString()
            } catch (_: Exception) {}
        }
    }

    private fun updateCheckinUi() {
        binding.tvCheckinLabel.text = if (checkedInToday) "已签到" else "签到"
        binding.tvCheckinIcon.alpha = if (checkedInToday) 0.5f else 1.0f
    }

    // ==================== 功能区 ====================

    private fun doCheckin() {
        if (checkedInToday) { requireContext().toast("今天已经签到过了"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = ApiClient.apiService.checkin().unwrap(requireContext()) ?: return@launch
                checkedInToday = true
                updateCheckinUi()
                AlertDialog.Builder(requireContext())
                    .setTitle("签到成功")
                    .setMessage("获得 ${result.reward} 小鱼干，已连续签到 ${result.streak} 天\n当前小鱼干余额：${result.coins}")
                    .setPositiveButton("好", null)
                    .show()
            } catch (e: Exception) {
                requireContext().toast("签到失败：${e.message}")
            }
        }
    }

    private fun showCoinsDialog() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val overview = ApiClient.apiService.getMyCoins().unwrap(ctx) ?: return@launch
                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20.dp(ctx), 16.dp(ctx), 20.dp(ctx), 8.dp(ctx))
                }
                val title = TextView(ctx).apply {
                    text = "当前余额：${overview.coins} 小鱼干"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lx_text))
                }
                container.addView(title)
                if (overview.recent.isEmpty()) {
                    container.addView(TextView(ctx).apply {
                        text = "\n暂无流水记录"
                        setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lx_text_sub))
                    })
                } else {
                    overview.recent.forEach { log -> container.addView(buildCoinLogRow(ctx, log)) }
                }
                AlertDialog.Builder(ctx)
                    .setTitle("小鱼干")
                    .setView(ScrollView(ctx).apply { addView(container) })
                    .setPositiveButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                ctx.toast("加载失败：${e.message}")
            }
        }
    }

    private fun buildCoinLogRow(ctx: android.content.Context, log: CoinLog): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(ctx), 0, 8.dp(ctx))
        }
        val reason = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append(log.reason.ifBlank { "变动" })
                if (log.createdAt > 0) append("\n${FormatUtils.formatRelative(log.createdAt)}")
            }
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lx_text))
        }
        val delta = TextView(ctx).apply {
            val change = log.change
            text = (if (change >= 0) "+" else "") + change.toString()
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), if (change >= 0) R.color.lx_green_dark else R.color.lx_red))
        }
        row.addView(reason)
        row.addView(delta)
        return row
    }

    private fun showVipDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 16.dp(ctx), 20.dp(ctx), 8.dp(ctx))
        }
        val tvStatus = TextView(ctx).apply {
            text = "查询中…"
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lx_text))
        }
        val etCode = EditText(ctx).apply {
            hint = "输入兑换码"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val btnRedeem = TextView(ctx).apply {
            text = "兑 换"
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.white))
            setBackgroundResource(R.drawable.bg_button_green)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10.dp(ctx), 0, 10.dp(ctx))
        }
        container.addView(tvStatus)
        container.addView(etCode)
        container.addView(btnRedeem, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12.dp(ctx) })

        val dialog = AlertDialog.Builder(ctx).setTitle("大会员").setView(container)
            .setNegativeButton("关闭", null).create()

        btnRedeem.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) { ctx.toast("请输入兑换码"); return@setOnClickListener }
            btnRedeem.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = ApiClient.apiService.redeemVip(com.lingxi.classic.data.model.VipRedeemRequest(code))
                    if (resp.isSuccess) {
                        ctx.toast("兑换成功")
                        dialog.dismiss()
                        refreshLoginState()
                    } else {
                        ctx.toast(resp.message.ifBlank { "兑换失败" })
                    }
                } catch (e: Exception) {
                    ctx.toast("网络错误：${e.message}")
                } finally {
                    btnRedeem.isEnabled = true
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val vip = ApiClient.apiService.getVipMe().unwrap(ctx) ?: return@launch
                val isVip = AuthManager.user?.vip == true
                tvStatus.text = buildString {
                    append("当前状态：").append(if (isVip) "大会员" else "未开通")
                    if (vip.uploadMaxBytes > 0) {
                        append("\n上传上限：${FormatUtils.formatBytes(vip.uploadMaxBytes)}")
                    }
                    if (vip.history.isNotEmpty()) {
                        append("\n\n兑换记录：")
                        vip.history.takeLast(5).reversed().forEach { r ->
                            append("\n· ${r.code}（${r.plan.ifBlank { "${r.days} 天" }}）")
                        }
                    }
                }
            } catch (e: Exception) {
                tvStatus.text = "状态查询失败：${e.message}"
            }
        }
        dialog.show()
    }

    // ==================== 菜单 ====================

    private data class MenuItem(
        val icon: String,
        val title: String,
        val subtitle: () -> String = { "" },
        val needLogin: Boolean = true,
        val adminOnly: Boolean = false,
        val onClick: () -> Unit
    )

    private fun buildMenu() {
        val ctx = context ?: return
        val container = binding.menuContainer
        container.removeAllViews()

        val items = listOf(
            MenuItem("稿", "我的投稿") { openVideoList("my_videos") },
            MenuItem("史", "历史记录") { openVideoList("history") },
            MenuItem("藏", "我的收藏") { openVideoList("favorites") },
            MenuItem("料", "个人资料编辑") { showProfileEditDialog() },
            MenuItem("密", "修改密码") { showChangePasswordDialog() },
            MenuItem("点", "节点选择", needLogin = false,
                subtitle = { AuthManager.nodeName ?: "主站" }) {
                startActivity(Intent(ctx, NodeSelectActivity::class.java))
            },
            MenuItem("设", "设置", needLogin = false) {
                startActivity(Intent(ctx, SettingsActivity::class.java))
            },
            MenuItem("管", "管理后台", adminOnly = true) {
                startActivity(Intent().setClassName(ctx.packageName, "com.lingxi.classic.ui.admin.AdminActivity"))
            },
            MenuItem("退", "退出登录") { confirmLogout() }
        )

        val loggedIn = AuthManager.isLoggedIn
        val inflater = LayoutInflater.from(ctx)
        items.forEach { item ->
            if (item.adminOnly && !AuthManager.isAdmin) return@forEach
            if (item.needLogin && !loggedIn) return@forEach
            val row = ItemMineMenuBinding.inflate(inflater, container, false)
            row.tvIcon.text = item.icon
            row.tvTitle.text = item.title
            val sub = item.subtitle()
            row.tvSubtitle.text = sub
            row.tvSubtitle.visible = sub.isNotEmpty()
            row.root.setOnClickListener { item.onClick() }
            container.addView(row.root)
        }
    }

    private fun openVideoList(type: String) {
        val ctx = context ?: return
        startActivity(
            Intent().setClassName(ctx.packageName, "com.lingxi.classic.ui.list.VideoListActivity")
                .putExtra("type", type)
        )
    }

    // ==================== 资料编辑 ====================

    private fun showProfileEditDialog() {
        val ctx = requireContext()
        val user = AuthManager.user
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 4.dp(ctx))
        }
        val etNickname = EditText(ctx).apply { hint = "昵称"; setText(user?.nickname ?: "") }
        val etBio = EditText(ctx).apply { hint = "简介"; setLines(2) }
        val rgGender = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbSecret = RadioButton(ctx).apply { text = "保密"; tag = "" }
        val rbMale = RadioButton(ctx).apply { text = "男"; tag = "male" }
        val rbFemale = RadioButton(ctx).apply { text = "女"; tag = "female" }
        rgGender.addView(rbSecret)
        rgGender.addView(rbMale)
        rgGender.addView(rbFemale)
        rbSecret.isChecked = true
        val btnAvatar = TextView(ctx).apply {
            text = "更换头像（从相册选择）"
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lx_green_dark))
            setPadding(0, 12.dp(ctx), 0, 4.dp(ctx))
        }
        btnAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        container.addView(etNickname)
        container.addView(etBio)
        container.addView(rgGender)
        container.addView(btnAvatar)

        // 预填简介/性别
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val me = ApiClient.apiService.getMyProfile().unwrap()?.user ?: return@launch
                etBio.setText(me.bio)
                when (me.gender) {
                    "male" -> rbMale.isChecked = true
                    "female" -> rbFemale.isChecked = true
                    else -> rbSecret.isChecked = true
                }
            } catch (_: Exception) {}
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("编辑资料")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nickname = etNickname.text.toString().trim()
                if (nickname.isEmpty()) { ctx.toast("昵称不能为空"); return@setOnClickListener }
                val gender = when (rgGender.checkedRadioButtonId) {
                    rbMale.id -> "male"
                    rbFemale.id -> "female"
                    else -> ""
                }
                val body = mapOf(
                    "nickname" to nickname,
                    "bio" to etBio.text.toString().trim(),
                    "gender" to gender
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val resp = ApiClient.apiService.updateMyProfile(body)
                        if (resp.isSuccess) {
                            ctx.toast("已保存")
                            dialog.dismiss()
                            refreshLoginState()
                        } else {
                            ctx.toast(resp.message.ifBlank { "保存失败" })
                        }
                    } catch (e: Exception) {
                        ctx.toast("网络错误：${e.message}")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun uploadAvatar(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) { ctx.toast("读取图片失败"); return@launch }
                if (bytes.size > 5 * 1024 * 1024) { ctx.toast("头像不能超过 5MB"); return@launch }
                val name = queryDisplayName(uri) ?: "avatar.jpg"
                val body = bytes.toRequestBody("application/octet-stream".toMediaType())
                val part = MultipartBody.Part.createFormData("file", name, body)
                val resp = ApiClient.apiService.upload("avatars", part)
                val url = resp.data?.url
                if (!resp.isSuccess || url.isNullOrEmpty()) {
                    ctx.toast(resp.message.ifBlank { "上传失败" })
                    return@launch
                }
                val patch = ApiClient.apiService.updateMyProfile(mapOf("avatar" to url))
                if (patch.isSuccess) {
                    ctx.toast("头像已更新")
                    refreshLoginState()
                } else {
                    ctx.toast(patch.message.ifBlank { "保存头像失败" })
                }
            } catch (e: Exception) {
                ctx.toast("上传失败：${e.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val ctx = context ?: return null
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    // ==================== 修改密码 ====================

    private fun showChangePasswordDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 12.dp(ctx), 20.dp(ctx), 4.dp(ctx))
        }
        val etOld = EditText(ctx).apply {
            hint = "原密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etNew = EditText(ctx).apply {
            hint = "新密码（至少 6 位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etNew2 = EditText(ctx).apply {
            hint = "确认新密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etOld)
        container.addView(etNew)
        container.addView(etNew2)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("修改密码")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认修改", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val oldP = etOld.text.toString()
                val newP = etNew.text.toString()
                if (oldP.isEmpty()) { ctx.toast("请输入原密码"); return@setOnClickListener }
                if (newP.length < 6) { ctx.toast("新密码至少 6 位"); return@setOnClickListener }
                if (newP != etNew2.text.toString()) { ctx.toast("两次输入的新密码不一致"); return@setOnClickListener }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val resp = ApiClient.apiService.changePassword(
                            mapOf("oldPassword" to oldP, "newPassword" to newP)
                        )
                        if (resp.isSuccess) {
                            resp.data?.let { r ->
                                if (r.accessToken != null && r.user != null) {
                                    AuthManager.saveLogin(r.accessToken, r.user, r.refreshToken)
                                }
                            }
                            ctx.toastLong("密码已修改")
                            dialog.dismiss()
                        } else {
                            ctx.toast(resp.message.ifBlank { "修改失败" })
                        }
                    } catch (e: Exception) {
                        ctx.toast("网络错误：${e.message}")
                    }
                }
            }
        }
        dialog.show()
    }

    // ==================== 退出登录 ====================

    private fun confirmLogout() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("退出登录")
            .setMessage("确定要退出当前账号吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                AuthManager.logout()
                checkedInToday = false
                refreshLoginState()
                ctx.toast("已退出登录")
            }
            .show()
    }
}
