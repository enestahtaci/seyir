package io.github.enestahtaci.seyir.yt

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** A video as the lists show it. Plain data, so lists stay cheap. */
data class Video(
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    /** Seconds; 0 or less when unknown (live streams, Shorts, feed entries). */
    val durationSec: Long,
    /** -1 when unknown. */
    val views: Long,
    val thumbUrl: String?,
    val isLive: Boolean,
    val isShort: Boolean,
    /** Epoch milliseconds, 0 when unknown. */
    val publishedAt: Long = 0,
)

data class Channel(val url: String, val name: String, val avatarUrl: String?, val subscribers: Long)

/** A channel page: its header and the tabs we show (videos, Shorts, live), each loaded when opened. */
class ChannelPage(val channel: Channel, val tabs: Map<String, ListLinkHandler>)

/** Everything YouTube, through NewPipe Extractor. Every call does network I/O: never on the UI thread. */
object YouTube {
    private val service get() = ServiceList.YouTube

    /** Search results: videos and channels, in YouTube's order. */
    fun search(query: String): List<Any> {
        val handler = service.searchQHFactory.fromQuery(query, emptyList(), "")
        return SearchInfo.getInfo(service, handler).relatedItems.mapNotNull(::toCard)
    }

    fun stream(url: String): StreamInfo = StreamInfo.getInfo(service, url)

    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")

    /** The watch URL when [text] is a YouTube video link or a bare video ID; null for ordinary search words. */
    fun videoUrlOf(text: String): String? {
        val t = text.trim()
        if (videoId.matches(t) && t.any { it.isDigit() || it == '_' || it == '-' }) return "https://www.youtube.com/watch?v=$t"
        val factory = service.streamLHFactory
        return runCatching { if (factory.acceptUrl(t)) factory.fromUrl(t).url else null }.getOrNull()
    }

    fun channel(url: String): ChannelPage {
        val info = ChannelInfo.getInfo(service, url)
        val tabs = info.tabs.mapNotNull { tab ->
            val kind = tab.contentFilters.firstOrNull()
            if (kind == ChannelTabs.VIDEOS || kind == ChannelTabs.SHORTS || kind == ChannelTabs.LIVESTREAMS) kind to tab else null
        }.toMap()
        return ChannelPage(Channel(info.url, info.name, pickImage(info.avatars, 176), info.subscriberCount), tabs)
    }

    fun channelTab(tab: ListLinkHandler): List<Video> =
        ChannelTabInfo.getInfo(service, tab).relatedItems.filterIsInstance<StreamInfoItem>().map(::toVideo)

    /**
     * A channel's latest uploads from its RSS feed: one small request (~0.1 s) instead of loading the channel page.
     * The feed mixes Shorts in; their links point to /shorts/.
     */
    fun latest(channel: Channel): List<Video> =
        FeedInfo.getInfo(service, channel.url).relatedItems.filterIsInstance<StreamInfoItem>().map { item ->
            toVideo(item).let { v ->
                v.copy(
                    channel = v.channel.ifEmpty { channel.name },
                    channelUrl = v.channelUrl ?: channel.url,
                    isShort = v.isShort || item.url.contains("/shorts/"),
                )
            }
        }

    private fun toCard(item: InfoItem): Any? = when (item) {
        is StreamInfoItem -> toVideo(item)
        is ChannelInfoItem -> Channel(item.url, item.name, pickImage(item.thumbnails, 176), item.subscriberCount)
        else -> null
    }

    private fun toVideo(item: StreamInfoItem) = Video(
        url = item.url,
        title = item.name,
        channel = item.uploaderName.orEmpty(),
        channelUrl = item.uploaderUrl,
        durationSec = item.duration,
        views = item.viewCount,
        thumbUrl = pickImage(item.thumbnails, 320),
        isLive = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = item.isShortFormContent,
        publishedAt = item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0,
    )

    /** The smallest image at least [minWidth] px wide: less to download and decode than the biggest one. */
    fun pickImage(images: List<Image>, minWidth: Int): String? {
        if (images.isEmpty()) return null
        val known = images.filter { it.width > 0 }
        return (known.filter { it.width >= minWidth }.minByOrNull { it.width } ?: known.maxByOrNull { it.width } ?: images.last()).url
    }
}
