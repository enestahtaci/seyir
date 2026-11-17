package io.github.enestahtaci.akis

import io.github.enestahtaci.akis.net.HttpDownloader
import io.github.enestahtaci.akis.yt.YouTube
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Talks to the real YouTube: run on demand (`-Pakis.liveTests=true`) to check that NewPipe Extractor still works
 * after YouTube changes something. Off by default so a normal build never depends on the network.
 */
class YouTubeLiveTest {
    @Test
    fun searchAndResolve() {
        assumeTrue(System.getProperty("akis.liveTests") == "true")
        NewPipe.init(HttpDownloader, Localization("tr", "TR"), ContentCountry("TR"))

        val results = YouTube.search("nasa")
        println("search: ${results.size} videos; first: ${results.firstOrNull()?.title}")
        assertTrue(results.isNotEmpty())

        val video = results.first { !it.isLive }
        val info = YouTube.stream(video.url)
        println("video-only: " + info.videoOnlyStreams.joinToString { "${it.height}p${it.fps} ${it.codec} ${it.deliveryMethod}" })
        println("audio: " + info.audioStreams.joinToString { "${it.averageBitrate}k ${it.codec} ${it.audioTrackType}" })
        assertTrue(info.videoOnlyStreams.any { it.height >= 720 && it.itagItem != null })
        assertTrue(info.audioStreams.isNotEmpty())
    }
}
