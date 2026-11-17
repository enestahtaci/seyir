package io.github.enestahtaci.seyir.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import io.github.enestahtaci.seyir.net.HttpDownloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * How YouTube's video servers (googlevideo.com/videoplayback) expect to be asked, following NewPipe's
 * YoutubeHttpDataSource (GPL-3.0): POST with the body "x\0", a running request number (&rn=), the web client's
 * Origin/Referer, and for OTF streams the byte range as a URL parameter (&range=) instead of a Range header.
 * Without these YouTube throttles or refuses (HTTP 403) after the first seconds.
 *
 * Done as a request rewrite on Media3's own DefaultHttpDataSource rather than a copy of a whole data source.
 */
@OptIn(UnstableApi::class)
class YoutubeRequests(private val rangeAsParameter: Boolean, private val requestNumber: Boolean) : ResolvingDataSource.Resolver {
    private val counter = AtomicLong()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val isPlayback = uri.path?.startsWith("/videoplayback") == true
        var url = uri.toString()
        var spec = dataSpec

        if (isPlayback && requestNumber && !url.contains("&rn=")) url += "&rn=" + counter.getAndIncrement()
        if (isPlayback && rangeAsParameter && (dataSpec.position != 0L || dataSpec.length != -1L)) {
            val end = if (dataSpec.length == -1L) "" else (dataSpec.position + dataSpec.length - 1).toString()
            url += "&range=${dataSpec.position}-$end"
            // The server now answers with just that range: read the whole answer, with no Range header on top.
            spec = spec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong())
                .setUriPositionOffset(dataSpec.uriPositionOffset + dataSpec.position).build()
        }

        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers["User-Agent"] =
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else HttpDownloader.USER_AGENT
        headers["TE"] = "trailers"
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers["Origin"] = YOUTUBE
            headers["Referer"] = YOUTUBE
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }

        return spec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpRequestHeaders(headers)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .build()
    }

    companion object {
        private const val YOUTUBE = "https://www.youtube.com"
        private val POST_BODY = byteArrayOf(0x78, 0)

        fun factory(rangeAsParameter: Boolean, requestNumber: Boolean): DataSource.Factory {
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false) // https only
                .setKeepPostFor302Redirects(true)
            return ResolvingDataSource.Factory(http, YoutubeRequests(rangeAsParameter, requestNumber))
        }
    }
}
