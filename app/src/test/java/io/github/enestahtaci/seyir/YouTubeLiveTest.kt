package io.github.enestahtaci.seyir

import io.github.enestahtaci.seyir.net.HttpDownloader
import io.github.enestahtaci.seyir.yt.Channel
import io.github.enestahtaci.seyir.yt.Video
import io.github.enestahtaci.seyir.yt.YouTube
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Talks to the real YouTube: run on demand (`-Pseyir.liveTests=true`) to check that NewPipe Extractor still works
 * after YouTube changes something. Off by default so a normal build never depends on the network.
 */
class YouTubeLiveTest {
    @Test
    fun searchAndResolve() {
        assumeTrue(System.getProperty("seyir.liveTests") == "true")
        NewPipe.init(HttpDownloader, Localization("tr", "TR"), ContentCountry("TR"))

        val results = YouTube.search("nasa")
        val videos = results.filterIsInstance<Video>()
        val channels = results.filterIsInstance<Channel>()
        println("search: ${videos.size} videos, ${channels.size} channels; first video: ${videos.firstOrNull()?.title}")
        assertTrue(videos.isNotEmpty())

        val video = videos.first { !it.isLive }
        val info = YouTube.stream(video.url)
        println("video-only: " + info.videoOnlyStreams.joinToString { "${it.height}p${it.fps} ${it.codec} ${it.deliveryMethod}" })
        println("audio: " + info.audioStreams.joinToString { "${it.averageBitrate}k ${it.codec} ${it.audioTrackType}" })
        assertTrue(info.videoOnlyStreams.any { it.height >= 720 && it.itagItem != null })
        assertTrue(info.audioStreams.isNotEmpty())
    }
}
