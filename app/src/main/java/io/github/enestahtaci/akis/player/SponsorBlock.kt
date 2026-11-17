package io.github.enestahtaci.akis.player

import io.github.enestahtaci.akis.net.HttpDownloader
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Community-marked parts of a video to skip (sponsor reads, intros…), from SponsorBlock (sponsor.ajay.app).
 *
 * Privacy: the video ID itself is never sent. We send the first 4 characters of its SHA-256 hash; the server answers
 * with every video sharing that prefix and we pick ours locally, so the server cannot tell which video is watched.
 */
object SponsorBlock {
    class Segment(val startMs: Long, val endMs: Long, val category: String)

    private const val API = "https://sponsor.ajay.app/api/skipSegments/"

    /** Blocking (network). Returns an empty list when there is nothing to skip or the service is unreachable. */
    fun segments(videoId: String, categories: List<String>): List<Segment> {
        if (categories.isEmpty() || videoId.isEmpty()) return emptyList()
        val prefix = sha256(videoId).take(4)
        val query = "categories=" + URLEncoder.encode(JSONArray(categories).toString(), "UTF-8") +
            "&actionTypes=" + URLEncoder.encode("[\"skip\"]", "UTF-8")
        val c = URL("$API$prefix?$query").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", HttpDownloader.USER_AGENT)
            if (c.responseCode != 200) return emptyList() // 404 = no segments for this prefix
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(body, videoId)
        } finally {
            c.disconnect()
        }
    }

    internal fun parse(body: String, videoId: String): List<Segment> {
        val videos = JSONArray(body)
        for (i in 0 until videos.length()) {
            val video = videos.getJSONObject(i)
            if (video.optString("videoID") != videoId) continue
            val list = video.getJSONArray("segments")
            return (0 until list.length()).map { list.getJSONObject(it) }
                .filter { it.optString("actionType", "skip") == "skip" }
                .map {
                    val range = it.getJSONArray("segment")
                    Segment((range.getDouble(0) * 1000).toLong(), (range.getDouble(1) * 1000).toLong(), it.getString("category"))
                }
                .filter { it.endMs - it.startMs >= 1_000 } // ignore marks shorter than a second
                .sortedBy { it.startMs }
        }
        return emptyList()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
