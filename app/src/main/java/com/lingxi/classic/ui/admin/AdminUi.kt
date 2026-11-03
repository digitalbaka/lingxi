package com.lingxi.classic.ui.admin

import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.R
import com.lingxi.classic.data.model.ApiResponse
import com.lingxi.classic.util.dp
import com.lingxi.classic.util.toast
import kotlinx.coroutines.launch

/** 管理后台通用 UI 工具：操作封装、对话框、状态标签样式 */

/** 统一操作封装：try/catch + 成功 toast + 刷新 */
fun Fragment.doAction(refresh: () -> Unit, block: suspend () -> ApiResponse<*>) {
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val resp = block()
            if (resp.isSuccess) {
                context?.toast(resp.message.ifBlank { "操作成功" })
                refresh()
            } else {
                context?.toast(resp.message.ifBlank { "操作失败 (${resp.code})" })
            }
        } catch (e: Exception) {
            context?.toast("操作失败：${e.message}")
        }
    }
}

/** 确认对话框 */
fun Fragment.confirm(title: String, msg: String, okText: String = "确定", onOk: () -> Unit) {
    val ctx = context ?: return
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(okText) { _, _ -> onOk() }
        .setNegativeButton("取消", null)
        .show()
}

/** 单输入框对话框 */
fun Fragment.inputDialog(
    title: String,
    hint: String,
    allowEmpty: Boolean = false,
    isNumber: Boolean = false,
    onOk: (String) -> Unit
) {
    val ctx = context ?: return
    val et = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        val p = 14.dp(ctx)
        setPadding(p, p / 2, p, p / 2)
    }
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    val m = 20.dp(ctx)
    lp.setMargins(m, 0, m, 0)
    val container = LinearLayout(ctx).apply { addView(et, lp) }
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(container)
        .setPositiveButton("确定") { _, _ ->
            val text = et.text.toString().trim()
            if (text.isEmpty() && !allowEmpty) {
                ctx.toast("请输入$hint")
                return@setPositiveButton
            }
            onOk(text)
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 双输入框对话框 */
fun Fragment.twoInputDialog(
    title: String,
    hint1: String,
    hint2: String,
    isNumber: Boolean = false,
    onOk: (String, String) -> Unit
) {
    val ctx = context ?: return
    fun makeEt(hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        val p = 14.dp(ctx)
        setPadding(p, p / 2, p, p / 2)
    }
    val et1 = makeEt(hint1)
    val et2 = makeEt(hint2)
    val m = 20.dp(ctx)
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, 0, m, 0)
        addView(et1)
        addView(et2)
    }
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(container)
        .setPositiveButton("确定") { _, _ ->
            val t1 = et1.text.toString().trim()
            val t2 = et2.text.toString().trim()
            if (t1.isEmpty()) { ctx.toast("请输入$hint1"); return@setPositiveButton }
            if (t2.isEmpty()) { ctx.toast("请输入$hint2"); return@setPositiveButton }
            onOk(t1, t2)
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 通用状态标签：0 待处理橙 / 1 通过绿 / 2 驳回红 / 其他灰 */
fun TextView.styleStatus(status: Int) {
    val ctx = context
    when (status) {
        0 -> {
            setBackgroundResource(R.drawable.admin_tag_orange)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_orange))
        }
        1 -> {
            setBackgroundResource(R.drawable.bg_tag)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_green_dark))
        }
        2 -> {
            setBackgroundResource(R.drawable.admin_tag_red)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_red))
        }
        else -> {
            setBackgroundResource(R.drawable.admin_tag_gray)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_text_sub))
        }
    }
    typeface = Typeface.DEFAULT_BOLD
}

/** 举报状态标签：pending 待处理 / resolved 已处理 / rejected 已驳回 */
fun TextView.styleReportStatus(status: String) {
    val ctx = context
    when (status) {
        "pending" -> {
            text = "待处理"
            setBackgroundResource(R.drawable.admin_tag_orange)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_orange))
        }
        "resolved" -> {
            text = "已处理"
            setBackgroundResource(R.drawable.bg_tag)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_green_dark))
        }
        "rejected" -> {
            text = "已驳回"
            setBackgroundResource(R.drawable.admin_tag_red)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_red))
        }
        else -> {
            text = status.ifBlank { "未知" }
            setBackgroundResource(R.drawable.admin_tag_gray)
            setTextColor(ContextCompat.getColor(ctx, R.color.lx_text_sub))
        }
    }
    typeface = Typeface.DEFAULT_BOLD
}

/** 举报对象类型映射 */
fun targetTypeLabel(type: String): String = when (type) {
    "video" -> "稿件"
    "article" -> "专栏"
    "comment" -> "评论"
    "danmaku" -> "弹幕"
    "user" -> "用户"
    else -> type.ifBlank { "内容" }
}
