package com.lingxi.classic.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.model.Video
import com.lingxi.classic.data.model.displayName
import com.lingxi.classic.databinding.ItemVideoBinding
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.visible

/** 全站通用的视频卡片适配器（列表/网格通用，布局由 RecyclerView 的 LayoutManager 决定）。可挂载一个全宽头部（如首页轮播） */
class VideoAdapter(
    private val onClick: (Video) -> Unit,
    private val onLongClick: ((Video) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Video>()
    private var headerFactory: ((ViewGroup) -> View)? = null

    private val headerOffset get() = if (headerFactory != null) 1 else 0

    /** 设置头部视图工厂（null 移除）。头部占据 position 0，items 索引语义不变 */
    fun setHeader(factory: ((ViewGroup) -> View)?) {
        headerFactory = factory
        notifyDataSetChanged()
    }

    fun isHeaderPosition(position: Int): Boolean = headerFactory != null && position == 0

    fun submit(list: List<Video>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Video>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start + headerOffset, list.size)
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position + headerOffset)
        }
    }

    fun getItems(): List<Video> = items

    /** 真实视频条数（不含头部）；空态/错误态判断必须用它，不能用 itemCount（永远含 Banner 头部） */
    val dataCount: Int get() = items.size

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int =
        if (isHeaderPosition(position)) TYPE_HEADER else TYPE_VIDEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderVH(headerFactory!!(parent))
        } else {
            VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun getItemCount(): Int = items.size + headerOffset

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is VH) return
        val v = items[position - headerOffset]
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VH) ImageLoader.clear(holder.binding.ivCover)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_VIDEO = 1
    }
}
