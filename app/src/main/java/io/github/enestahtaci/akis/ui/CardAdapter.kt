package io.github.enestahtaci.akis.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.enestahtaci.akis.R
import io.github.enestahtaci.akis.yt.Channel
import io.github.enestahtaci.akis.yt.Video

/** A section title spanning the whole grid row ("İzlemeye devam et", "Abonelikler"). */
data class Header(val text: String)

/**
 * The grid every screen uses: video cards, channel cards and section headers. Stable ids keep the remote's focus on the
 * same card when the list is refreshed.
 */
class CardAdapter(
    private val onVideo: (Video, List<Video>) -> Unit,
    private val onChannel: (Channel) -> Unit,
    /** Long press OK on a video: open its channel. */
    private val onVideoLong: (Video) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<Any> = emptyList()
        private set
    /** Watched milliseconds per video URL (history), for the red progress line. */
    private var positions: Map<String, Long> = emptyMap()

    init { setHasStableIds(true) }

    @SuppressLint("NotifyDataSetChanged") // a new list replaces the whole grid
    fun show(newItems: List<Any>, newPositions: Map<String, Long> = positions) {
        items = newItems
        positions = newPositions
        notifyDataSetChanged()
    }

    /** Header rows take the full width of a [columns]-wide grid. */
    fun spanLookup(columns: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = if (items.getOrNull(position) is Header) columns else 1
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = when (val item = items[position]) {
        is Video -> item.url.hashCode().toLong()
        is Channel -> item.url.hashCode().toLong() shl 1
        is Header -> item.text.hashCode().toLong() shl 2
        else -> position.toLong()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Video -> VIDEO
        is Channel -> CHANNEL
        else -> HEADER
    }

    class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val badge: TextView = view.findViewById(R.id.badge)
        val progress: View = view.findViewById(R.id.progress)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById<ImageView>(R.id.avatar).apply { clipToOutline = true }
        val name: TextView = view.findViewById(R.id.name)
        val detail: TextView = view.findViewById(R.id.detail)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text = view as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIDEO -> VideoHolder(inflater.inflate(R.layout.item_video, parent, false)).also { h ->
                h.itemView.setOnClickListener { at(h)?.let { v -> onVideo(v as Video, items.filterIsInstance<Video>()) } }
                h.itemView.setOnLongClickListener { at(h)?.let { v -> onVideoLong(v as Video) }; true }
                growOnFocus(h.itemView)
            }
            CHANNEL -> ChannelHolder(inflater.inflate(R.layout.item_channel, parent, false)).also { h ->
                h.itemView.setOnClickListener { at(h)?.let { c -> onChannel(c as Channel) } }
                growOnFocus(h.itemView)
            }
            else -> HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
        }
    }

    private fun at(holder: RecyclerView.ViewHolder) = holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { items[it] }

    /** Focused card grows a little, like YouTube on TV. A property animation: no layout pass. */
    private fun growOnFocus(view: View) = view.setOnFocusChangeListener { v, focused ->
        val scale = if (focused) 1.06f else 1f
        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        v.translationZ = if (focused) 8f else 0f
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VideoHolder -> bindVideo(holder, items[position] as Video)
            is ChannelHolder -> {
                val channel = items[position] as Channel
                holder.name.text = channel.name
                holder.detail.text = Format.subscribers(channel.subscribers)
                holder.detail.visibility = if (channel.subscribers < 0) View.GONE else View.VISIBLE
                Thumbnails.load(holder.avatar, channel.avatarUrl)
            }
            is HeaderHolder -> holder.text.text = (items[position] as Header).text
        }
    }

    private fun bindVideo(holder: VideoHolder, video: Video) {
        val context = holder.itemView.context
        holder.title.text = video.title
        holder.subtitle.text = listOf(video.channel, Format.views(video.views), Format.ago(video.publishedAt))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val badge = when {
            video.isLive -> context.getString(R.string.live)
            video.isShort -> context.getString(R.string.shorts)
            else -> Format.duration(video.durationSec)
        }
        holder.badge.text = badge
        holder.badge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        holder.badge.setBackgroundResource(R.drawable.badge_background)
        if (video.isLive) holder.badge.background.mutate().setTint(context.getColor(R.color.accent))

        val watched = positions[video.url] ?: 0
        if (watched > 0 && video.durationSec > 0) {
            holder.progress.pivotX = 0f
            holder.progress.scaleX = (watched / 1000f / video.durationSec).coerceIn(0.03f, 1f)
            holder.progress.visibility = View.VISIBLE
        } else {
            holder.progress.visibility = View.GONE
        }
        Thumbnails.load(holder.thumb, video.thumbUrl)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val image = when (holder) {
            is VideoHolder -> holder.thumb
            is ChannelHolder -> holder.avatar
            else -> return
        }
        image.tag = null // a late download must not land on a recycled card
        image.setImageDrawable(null)
    }

    companion object {
        private const val VIDEO = 0
        private const val CHANNEL = 1
        private const val HEADER = 2
    }
}
