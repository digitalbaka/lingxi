package com.lingxi.classic.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemRankingBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.visible

/**
 * 排行榜列表适配器：名次 + 封面 + 标题/UP 主 + 右侧当前指标数值。
 * 指标随筛选变化（播放量/点赞数/小鱼干/弹幕数/收藏数），由 [setMetric] 指定。
 */
class RankingAdapter(
    private val onClick: (Video) -> Unit
) : RecyclerView.Adapter<RankingAdapter.VH>() {

    private val items = mutableListOf<Video>()
    private var metricKey: String = "views"
    private var metricLabel: String = "播放量"

    fun setMetric(key: String, label: String) {
        metricKey = key
        metricLabel = label
        notifyDataSetChanged()
    }

    fun submit(list: List<Video>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemRankingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRankingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        val b = holder.binding
        val rank = position + 1

        b.tvRank.text = rank.toString()
        b.tvRank.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (rank <= 3) R.color.lx_green_dark else R.color.lx_text_sub
            )
        )
        b.tvTitle.text = v.title
        ImageLoader.load(b.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))

        b.tvDuration.visible = !v.isArticle
        b.tvDuration.text = if (v.isAudio) "音频" else FormatUtils.formatDuration(v.duration)

        b.tvMeta.text = buildString {
            append(v.up.displayName())
            append(" · ")
            append(FormatUtils.formatCount(v.viewCount))
            append(" 播放")
            if (v.danmakuCount > 0) append(" · ${FormatUtils.formatCount(v.danmakuCount)} 弹幕")
        }

        b.tvMetric.text = FormatUtils.formatCount(metricValue(v))
        b.tvMetricLabel.text = metricLabel

        b.rowRoot.setOnClickListener { onClick(v) }
    }

    private fun metricValue(v: Video): Int = when (metricKey) {
        "likes" -> v.likeCount
        "coins" -> v.coinCount
        "danmaku" -> v.danmakuCount
        "favs" -> v.favCount
        else -> v.viewCount
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        ImageLoader.clear(holder.binding.ivCover)
    }
}
