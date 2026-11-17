package io.github.enestahtaci.akis.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Turns a YouTube video into one Media3 source: a single video quality (the best this TV decodes in hardware, up to
 * [maxHeight]) merged with the best original-language audio. Live streams use YouTube's HLS playlist.
 *
 * Choosing one quality up front (instead of letting the player switch) keeps memory and CPU low on a weak TV.
 */
@OptIn(UnstableApi::class)
object StreamSource {
    class Choice(val source: MediaSource, val label: String)

    // NewPipe uses these three request styles (PlayerDataSource): progressive DASH, OTF DASH, HLS.
    private val progressive by lazy { YoutubeRequests.factory(rangeAsParameter = false, requestNumber = true) }
    private val otf by lazy { YoutubeRequests.factory(rangeAsParameter = true, requestNumber = true) }
    private val hls by lazy { YoutubeRequests.factory(rangeAsParameter = false, requestNumber = false) }

    fun build(info: StreamInfo, maxHeight: Int): Choice {
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            require(info.hlsUrl.isNotEmpty()) { "live stream without HLS" }
            val item = MediaItem.Builder().setUri(info.hlsUrl).setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build()
            ).build()
            return Choice(HlsMediaSource.Factory(hls).setAllowChunklessPreparation(true).createMediaSource(item), "Canlı")
        }

        val video = pickVideo(info.videoOnlyStreams, maxHeight)
        val audio = pickAudio(info.audioStreams)
        if (video != null && audio != null) {
            return Choice(
                MergingMediaSource(true, youtubeSource(video, info), youtubeSource(audio, info)),
                "${video.shortSide()}p${if (video.fps > 30) video.fps.toString() else ""} · ${codecName(video.codec)}",
            )
        }
        // Fallback: an old combined (video+audio) stream, usually 360p.
        val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height } ?: error("no playable stream")
        val item = MediaItem.fromUri(muxed.content)
        return Choice(ProgressiveMediaSource.Factory(progressive).createMediaSource(item), "${muxed.height}p")
    }

    private fun youtubeSource(stream: Stream, info: StreamInfo): MediaSource {
        val itag = requireNotNull(stream.itagItem) { "stream without itag" }
        val (manifest, factory) = when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP ->
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, info.duration) to progressive
            DeliveryMethod.DASH ->
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, info.duration) to otf
            else -> error("unsupported delivery ${stream.deliveryMethod}")
        }
        val uri = Uri.parse(stream.content)
        val parsed = DashManifestParser().parse(uri, manifest.byteInputStream())
        return DashMediaSource.Factory(factory).createMediaSource(parsed, MediaItem.Builder().setUri(uri).build())
    }

    // --- choosing streams ---

    private enum class Codec(val mime: String, val preference: Int) {
        AV1("video/av01", 3), VP9("video/x-vnd.on2.vp9", 2), AVC("video/avc", 1)
    }

    private fun codecOf(codec: String?): Codec? {
        val c = codec?.lowercase() ?: return null
        return when {
            c.startsWith("av01") -> Codec.AV1
            c.startsWith("vp9") || c.startsWith("vp09") -> Codec.VP9
            c.startsWith("avc") -> Codec.AVC
            else -> null
        }
    }

    private fun codecName(codec: String?) = when (codecOf(codec)) {
        Codec.AV1 -> "AV1"; Codec.VP9 -> "VP9"; Codec.AVC -> "H.264"; null -> codec.orEmpty()
    }

    /** Hardware video decoders on this device (software decoding 1080p would stutter on a TV chip). */
    private val hardwareDecoders: List<Pair<Codec, MediaCodecInfo.VideoCapabilities>> by lazy {
        val result = ArrayList<Pair<Codec, MediaCodecInfo.VideoCapabilities>>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            if (!hardware) continue
            for (codec in Codec.entries) {
                if (info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) }) {
                    info.getCapabilitiesForType(codec.mime).videoCapabilities?.let { result += codec to it }
                }
            }
        }
        result
    }

    private fun decodable(stream: VideoStream): Codec? {
        val codec = codecOf(stream.codec) ?: return null
        val width = stream.width.takeIf { it > 0 } ?: (stream.height * 16 / 9)
        val fps = stream.fps.takeIf { it > 0 } ?: 30
        // Decoders often list sizes for landscape only; a vertical Short decodes as its rotated size.
        val ok = hardwareDecoders.any { (c, caps) ->
            c == codec && runCatching {
                caps.areSizeAndRateSupported(width, stream.height, fps.toDouble()) ||
                    caps.areSizeAndRateSupported(stream.height, width, fps.toDouble())
            }.getOrDefault(false)
        }
        return if (ok) codec else null
    }

    /** "1080p" means the short side: a vertical Short at 1080x1920 is 1080p too. */
    private fun VideoStream.shortSide() = if (width > 0) minOf(width, height) else height

    private fun pickVideo(streams: List<VideoStream>, maxHeight: Int): VideoStream? = streams
        .filter { it.isUrl && it.itagItem != null && it.shortSide() in 1..maxHeight && it.fps <= 60 }
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH }
        .mapNotNull { s -> decodable(s)?.let { s to it } }
        .maxWithOrNull(compareBy<Pair<VideoStream, Codec>>({ it.first.shortSide() }, { it.first.fps }, { it.second.preference }))
        ?.first

    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter {
            it.isUrl && it.itagItem != null &&
                (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH)
        }
        // Original language first (not a dubbed or described track), then the highest bitrate.
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        return (original.ifEmpty { usable }).maxByOrNull { it.averageBitrate }
    }
}
