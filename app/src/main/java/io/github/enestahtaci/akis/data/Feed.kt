package io.github.enestahtaci.akis.data

import io.github.enestahtaci.akis.yt.Channel
import io.github.enestahtaci.akis.yt.YouTube
import java.util.concurrent.Executors

/**
 * The subscription feed: each subscribed channel's RSS feed, 4 at a time, saved to the database. The home screen shows
 * the saved feed at once and refreshes it in the background when it is older than [MAX_AGE_MS].
 */
object Feed {
    private const val MAX_AGE_MS = 30 * 60_000L
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var refreshedAt = 0L

    fun isStale() = System.currentTimeMillis() - refreshedAt > MAX_AGE_MS

    /** Blocking: call on a background thread. Channels that fail (network, removed channel) keep their old entries. */
    fun refresh(db: Db) {
        val jobs = db.subscriptions().map { channel -> pool.submit<Boolean> { refreshChannel(db, channel) } }
        jobs.forEach { runCatching { it.get() } }
        refreshedAt = System.currentTimeMillis()
    }

    /** Blocking. Used right after subscribing, so the new channel shows up on the home screen at once. */
    fun refreshChannel(db: Db, channel: Channel): Boolean {
        val videos = runCatching { YouTube.latest(channel) }.getOrNull() ?: return false
        db.replaceFeed(channel.url, videos.filter { !it.isShort }, shorts = false)
        db.replaceFeed(channel.url, videos.filter { it.isShort }, shorts = true)
        return true
    }
}
