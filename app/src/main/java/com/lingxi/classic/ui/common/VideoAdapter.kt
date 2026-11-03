package com.lingxi.classic.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemVideoBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.visible

/** 全站通用的视频卡片适配器（列表/网格通用，布局由 RecyclerView 的 LayoutManager 决定） */
class VideoAdapter(
    private val onClick: (Video) -> Unit,
    private val onLongClick: ((Video) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = mutableListOf<Video>()

    fun submit(list: List<Video>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Video>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun getItems(): List<Video> = items

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        val b = holder.binding
        b.tvTitle.text = v.title
        ImageLoader.load(b.ivCover, v.getFullCoverUrl(ApiClient.BASE_URL))

        b.tvDuration.visible = !v.isArticle
        b.tvDuration.text = if (v.isAudio) "音频" else FormatUtils.formatDuration(v.duration)

        if (v.isArticle) {
            b.tvKindBadge.visible = true
            b.tvKindBadge.text = "专栏"
        } else if (v.isAudio) {
            b.tvKindBadge.visible = true
            b.tvKindBadge.text = "音频"
        } else {
            b.tvKindBadge.visible = false
        }

        b.tvMeta.text = buildString {
            append(v.up.displayName())
            append(" · ")
            append(FormatUtils.formatCount(v.viewCount))
            append(" 播放")
            if (v.danmakuCount > 0) append(" · ${FormatUtils.formatCount(v.danmakuCount)} 弹幕")
        }

        holder.itemView.setOnClickListener { onClick(v) }
        onLongClick?.let { cb ->
            holder.itemView.setOnLongClickListener { cb(v); true }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        ImageLoader.clear(holder.binding.ivCover)
    }
}
