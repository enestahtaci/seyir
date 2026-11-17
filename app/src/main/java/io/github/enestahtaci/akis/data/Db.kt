package io.github.enestahtaci.akis.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.enestahtaci.akis.yt.Channel
import io.github.enestahtaci.akis.yt.Video
import java.util.concurrent.Executors

/**
 * Everything the app remembers, on this TV only (no account, no cloud backup): subscriptions, watch history with the
 * resume position, and the cached subscription feed. Plain SQLite, no ORM.
 * All methods do disk I/O: never call them on the UI thread (screens use [io]; SQLiteOpenHelper is thread-safe).
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "akis.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE subscription (url TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT, added_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL, channel TEXT, channel_url TEXT, thumb TEXT, " +
                "duration INTEGER NOT NULL, position_ms INTEGER NOT NULL, is_short INTEGER NOT NULL, watched_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE feed (url TEXT PRIMARY KEY, channel_url TEXT NOT NULL, title TEXT NOT NULL, channel TEXT, thumb TEXT, " +
                "duration INTEGER NOT NULL, views INTEGER NOT NULL, published_at INTEGER NOT NULL, is_short INTEGER NOT NULL, " +
                "is_live INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX feed_by_date ON feed (published_at DESC)")
        db.execSQL("CREATE INDEX history_by_date ON history (watched_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // --- subscriptions ---

    fun subscriptions(): List<Channel> = readableDatabase.rawQuery(
        "SELECT url, name, avatar FROM subscription ORDER BY name COLLATE NOCASE", null
    ).use { c -> c.map { Channel(it.getString(0), it.getString(1), it.getStringOrNull(2), -1) } }

    fun isSubscribed(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM subscription WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }

    fun subscribe(channel: Channel) {
        writableDatabase.insertWithOnConflict("subscription", null, ContentValues().apply {
            put("url", channel.url); put("name", channel.name); put("avatar", channel.avatarUrl)
            put("added_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unsubscribe(url: String) {
        writableDatabase.delete("subscription", "url = ?", arrayOf(url))
        writableDatabase.delete("feed", "channel_url = ?", arrayOf(url))
    }

    // --- subscription feed ---

    fun feed(limit: Int = 120, shorts: Boolean = false): List<Video> = readableDatabase.rawQuery(
        "SELECT url, title, channel, channel_url, thumb, duration, views, published_at, is_short, is_live FROM feed " +
            "WHERE is_short = ? ORDER BY published_at DESC LIMIT ?",
        arrayOf(if (shorts) "1" else "0", limit.toString())
    ).use { c ->
        c.map {
            Video(
                url = it.getString(0), title = it.getString(1), channel = it.getStringOrNull(2).orEmpty(),
                channelUrl = it.getStringOrNull(3), thumbUrl = it.getStringOrNull(4), durationSec = it.getLong(5),
                views = it.getLong(6), publishedAt = it.getLong(7), isShort = it.getInt(8) == 1, isLive = it.getInt(9) == 1,
            )
        }
    }

    /** Replaces one channel's videos (or Shorts) in the feed with a fresh list. */
    fun replaceFeed(channelUrl: String, videos: List<Video>, shorts: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("feed", "channel_url = ? AND is_short = ?", arrayOf(channelUrl, if (shorts) "1" else "0"))
            for (v in videos) {
                db.insertWithOnConflict("feed", null, ContentValues().apply {
                    put("url", v.url); put("channel_url", channelUrl); put("title", v.title); put("channel", v.channel)
                    put("thumb", v.thumbUrl); put("duration", v.durationSec); put("views", v.views)
                    put("published_at", v.publishedAt); put("is_short", if (shorts) 1 else 0); put("is_live", if (v.isLive) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- history ---

    fun history(limit: Int = 200): List<Video> = readableDatabase.rawQuery(
        "SELECT url, title, channel, channel_url, thumb, duration, is_short FROM history ORDER BY watched_at DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        c.map {
            Video(
                url = it.getString(0), title = it.getString(1), channel = it.getStringOrNull(2).orEmpty(),
                channelUrl = it.getStringOrNull(3), thumbUrl = it.getStringOrNull(4), durationSec = it.getLong(5),
                views = -1, publishedAt = 0, isShort = it.getInt(6) == 1, isLive = false,
            )
        }
    }

    /** Watched position per video URL, for the progress bars on cards. */
    fun positions(): Map<String, Long> = readableDatabase.rawQuery("SELECT url, position_ms FROM history", null)
        .use { c -> c.map { it.getString(0) to it.getLong(1) }.toMap() }

    fun position(url: String): Long =
        readableDatabase.rawQuery("SELECT position_ms FROM history WHERE url = ?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun saveHistory(video: Video, positionMs: Long) {
        writableDatabase.insertWithOnConflict("history", null, ContentValues().apply {
            put("url", video.url); put("title", video.title); put("channel", video.channel); put("channel_url", video.channelUrl)
            put("thumb", video.thumbUrl); put("duration", video.durationSec); put("position_ms", positionMs)
            put("is_short", if (video.isShort) 1 else 0); put("watched_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearHistory() { writableDatabase.delete("history", null, null) }

    companion object {
        /** The one thread that touches the database: no locking questions, and the UI thread never waits on disk. */
        val io = Executors.newSingleThreadExecutor()

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }
    }
}

private inline fun <T> Cursor.map(block: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += block(this)
    return out
}

private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
