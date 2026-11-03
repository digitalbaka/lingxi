package com.lingxi.classic.ui.common

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.CoinRequest
import com.lingxi.classic.data.model.FavoriteRequest
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.databinding.ViewInteractionBarBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.unwrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 互动按钮行（点赞/投币/收藏/分享）：封装按钮状态渲染与弹窗，播放页与文章页共用。
 * 布局用 &lt;include layout="@layout/view_interaction_bar"/&gt; 嵌入后把 root 传进来。
 */
class InteractionBar(
    private val context: Context,
    barRoot: View
) {
    private val binding = ViewInteractionBarBinding.bind(barRoot)
    private val scope: CoroutineScope
        get() = (context as LifecycleOwner).lifecycleScope

    private var video: Video? = null
    private var liked = false
    private var likeCount = 0
    private var coined = 0
    private var coinCount = 0
    private var faved = false
    private var favCount = 0

    fun bind(v: Video) {
        video = v
        v.viewer?.let {
            liked = it.liked
            coined = it.coined
            faved = it.faved
        }
        likeCount = v.likeCount
        coinCount = v.coinCount
        favCount = v.favCount
        render()
        binding.btnLike.setOnClickListener { doLike() }
        binding.btnCoin.setOnClickListener { doCoin() }
        binding.btnFav.setOnClickListener { doFavorite() }
        binding.btnShare.setOnClickListener { doShare(v) }
    }

    private fun render() {
        binding.btnLike.text = "❤ ${FormatUtils.formatCount(likeCount)}"
        binding.btnLike.setTextColor(color(if (liked) R.color.lx_green else R.color.lx_text_sub))
        binding.btnCoin.text = "◉ ${FormatUtils.formatCount(coinCount)}"
        binding.btnCoin.setTextColor(color(if (coined > 0) R.color.lx_green else R.color.lx_text_sub))
        binding.btnFav.text = "★ ${FormatUtils.formatCount(favCount)}"
        binding.btnFav.setTextColor(color(if (faved) R.color.lx_green else R.color.lx_text_sub))
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun requireLogin(): Boolean {
        if (!AuthManager.isLoggedIn) {
            context.toast("请先登录")
            return false
        }
        return true
    }

    private fun doLike() {
        val v = video ?: return
        if (!requireLogin()) return
        scope.launch {
            try {
                val r = ApiClient.apiService.likeVideo(v.id).unwrap(context) ?: return@launch
                liked = r.liked
                likeCount = r.likeCount
                render()
                context.toast(if (r.liked) "已点赞" else "已取消点赞")
            } catch (e: Exception) {
                context.toast("操作失败：${e.message}")
            }
        }
    }

    private fun doCoin() {
        val v = video ?: return
        if (!requireLogin()) return
        if (coined >= 2) {
            context.toast("这个视频已经投过币啦")
            return
        }
        AlertDialog.Builder(context)
            .setTitle("投币")
            .setItems(arrayOf("投 1 枚", "投 2 枚")) { _, which ->
                scope.launch {
                    try {
                        val r = ApiClient.apiService.coinVideo(v.id, CoinRequest(which + 1))
                            .unwrap(context) ?: return@launch
                        coined = r.coined
                        coinCount = r.coinCount
                        render()
                        context.toast("投币成功，剩余小鱼干 ${r.coins}")
                    } catch (e: Exception) {
                        context.toast("投币失败：${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doFavorite() {
        val v = video ?: return
        if (!requireLogin()) return
        if (faved) {
            context.toast("已收藏，可在「我的收藏」中查看")
            return
        }
        scope.launch {
            try {
                val folders = ApiClient.apiService.getMyFolders().unwrap(context)?.items.orEmpty()
                if (folders.isEmpty()) {
                    context.toast("暂无收藏夹")
                    return@launch
                }
                val names = folders.map { "${it.name}（${it.count}）" }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle("选择收藏夹")
                    .setItems(names) { _, which ->
                        val folder = folders[which]
                        scope.launch fav@{
                            try {
                                val r = ApiClient.apiService.favoriteVideo(v.id, FavoriteRequest(folder.id))
                                    .unwrap(context) ?: return@fav
                                faved = r.faved
                                favCount = r.favCount
                                render()
                                context.toast("已收藏到「${folder.name}」")
                            } catch (e: Exception) {
                                context.toast("收藏失败：${e.message}")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                context.toast("加载收藏夹失败：${e.message}")
            }
        }
    }

    private fun doShare(v: Video) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${v.title} https://lingxi.xiguastudio.top/video/${v.id}")
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    }
}
