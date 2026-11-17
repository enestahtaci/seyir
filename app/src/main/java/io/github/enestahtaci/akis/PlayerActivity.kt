package io.github.enestahtaci.akis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.enestahtaci.akis.data.Db
import io.github.enestahtaci.akis.data.Settings
import io.github.enestahtaci.akis.player.SponsorBlock
import io.github.enestahtaci.akis.player.StreamSource
import io.github.enestahtaci.akis.ui.Format
import io.github.enestahtaci.akis.yt.Video
import io.github.enestahtaci.akis.yt.YouTube
import org.schabi.newpipe.extractor.ServiceList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Full-screen player for one video, or for a list of Shorts (UP/DOWN moves between them and each one loops, as on
 * YouTube). Remembers where you stopped and continues from there next time. Streams are resolved off the UI thread;
 * the next Short is resolved in advance so swiping feels instant.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private lateinit var db: Db
    private lateinit var settings: Settings
    private lateinit var exo: ExoPlayer

    // SponsorBlock: segments of the current video, and those already skipped (each is skipped once, so seeking back
    // into one plays it).
    private var segments: List<SponsorBlock.Segment> = emptyList()
    private val skipped = HashSet<SponsorBlock.Segment>()
    private val sponsorCheck = object : Runnable {
        override fun run() {
            if (segments.isEmpty()) return
            if (exo.isPlaying) {
                val at = exo.currentPosition
                val segment = segments.firstOrNull { it !in skipped && at >= it.startMs && at < it.endMs - 500 }
                if (segment != null) {
                    skipped += segment
                    exo.seekTo(segment.endMs)
                    showMessage(getString(R.string.skipped, Settings.label(segment.category)), sticky = false, autoHideMs = 2_000)
                }
            }
            main.postDelayed(this, SPONSOR_CHECK_MS)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val prepared = ConcurrentHashMap<String, Result<Resolved>>()
    @Volatile private var closed = false

    private var list: List<Video> = emptyList()
    private var index = 0
    private var current: Video? = null
    private var loadId = 0
    private val shortsMode get() = list.size > 1 || list.firstOrNull()?.isShort == true

    private class Resolved(val choice: StreamSource.Choice, val durationSec: Long, val resumeMs: Long, val video: Video)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        message = findViewById(R.id.message)
        db = Db.get(this)
        settings = Settings.get(this)

        list = queue
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isEmpty()) return finish()

        // Small buffer (like enestv): enough for smooth 1080p, without filling a 2.4 GB TV's memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(loadControl).build()
        playerView.player = exo
        // Shorts are watched clean, like on YouTube: no seek bar or buttons over the video.
        playerView.useController = !shortsMode
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && message.tag == null) message.visibility = View.GONE
                if (state == Player.STATE_ENDED && !shortsMode) { current?.let { saveHistory(it, 0, finished = true) }; finish() }
            }

            override fun onRenderedFirstFrame() {
                Log.i(PERF, "first frame ${(System.nanoTime() - firstFrameFrom) / 1_000_000} ms after source")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback error: ${error.errorCodeName}")
                showMessage(getString(R.string.play_error), sticky = true)
            }
        })
        play(index)
    }

    private var firstFrameFrom = 0L

    private fun resolve(video: Video): Result<Resolved> = prepared.remove(video.url) ?: runCatching {
        val started = System.nanoTime()
        val info = YouTube.stream(video.url)
        val choice = StreamSource.build(info, settings.maxHeight)
        Log.i(PERF, "resolved in ${(System.nanoTime() - started) / 1_000_000} ms: ${choice.label}")
        val resume = if (video.isShort || video.isLive) 0 else db.position(video.url)
        // Fill in what the list did not know (a video opened from a link has no title yet), for the history.
        val full = video.copy(
            title = video.title.ifEmpty { info.name },
            channel = video.channel.ifEmpty { info.uploaderName.orEmpty() },
            channelUrl = video.channelUrl ?: info.uploaderUrl,
            thumbUrl = video.thumbUrl ?: YouTube.pickImage(info.thumbnails, 320),
            durationSec = if (video.durationSec > 0) video.durationSec else info.duration,
        )
        Resolved(choice, info.duration, resume, full)
    }

    private fun play(at: Int) {
        current?.let { saveHistory(it, exo.currentPosition) }
        index = at
        val video = list[at]
        current = video
        val id = ++loadId
        exo.stop()
        showMessage(getString(R.string.loading_video), sticky = false)
        segments = emptyList()
        skipped.clear()
        main.removeCallbacks(sponsorCheck)
        val categories = settings.skippedCategories()
        if (!video.isLive && categories.isNotEmpty()) worker.execute {
            val found = runCatching {
                SponsorBlock.segments(ServiceList.YouTube.streamLHFactory.getId(video.url), categories)
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) Log.i(PERF, "sponsorblock: ${found.size} segments")
            main.post {
                if (closed || id != loadId || found.isEmpty()) return@post
                segments = found
                main.post(sponsorCheck)
            }
        }
        worker.execute {
            val result = resolve(video)
            main.post {
                if (closed || id != loadId) return@post
                result.onSuccess { start(it) }.onFailure {
                    // The exception text may hold a stream URL: log only its type.
                    Log.w(TAG, "resolve failed: ${it.javaClass.simpleName}")
                    showMessage(getString(R.string.play_error), sticky = true)
                }
            }
            // Resolve the next Short while this one plays.
            if (shortsMode && at + 1 < list.size) {
                val next = list[at + 1]
                if (!prepared.containsKey(next.url)) prepared[next.url] = resolve(next)
            }
        }
    }

    private fun start(resolved: Resolved) {
        current = resolved.video
        exo.repeatMode = if (shortsMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        firstFrameFrom = System.nanoTime()
        exo.setMediaSource(resolved.choice.source)
        val duration = resolved.durationSec * 1000
        if (resolved.resumeMs > 10_000 && (duration <= 0 || resolved.resumeMs < duration - 20_000)) {
            exo.seekTo(resolved.resumeMs)
            showMessage(getString(R.string.resumed_at, Format.duration(resolved.resumeMs / 1000)), sticky = false, autoHideMs = 3_000)
        } else {
            message.tag = null
        }
        exo.playWhenReady = true
        exo.prepare()
        playerView.requestFocus()
    }

    private fun showMessage(text: String, sticky: Boolean, autoHideMs: Long = 0) {
        message.text = text
        message.visibility = View.VISIBLE
        message.tag = if (sticky || autoHideMs > 0) text else null
        if (autoHideMs > 0) main.postDelayed({ if (message.tag == text) { message.tag = null; message.visibility = View.GONE } }, autoHideMs)
    }

    private fun saveHistory(video: Video, positionMs: Long, finished: Boolean = false) {
        if (video.isLive) return
        // A finished video is stored at 0, so it starts from the beginning next time and leaves "continue watching".
        val position = if (finished || video.isShort) 0 else positionMs.coerceAtLeast(0)
        Db.io.execute { db.saveHistory(video, position) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                // Play/pause and OK toggle playback directly when the controller is hidden, as on YouTube.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    exo.playWhenReady = !exo.playWhenReady
                    if (!shortsMode) playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!shortsMode) { seekBy(SEEK_MS); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!shortsMode) { seekBy(-SEEK_MS); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (shortsMode) { if (index + 1 < list.size) play(index + 1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (shortsMode) { if (index > 0) play(index - 1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekBy(ms: Long) {
        val target = (exo.currentPosition + ms).coerceAtLeast(0)
        exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration - 1_000) else target)
        playerView.showController()
    }

    override fun onStop() {
        super.onStop()
        if (::exo.isInitialized) {
            exo.pause()
            current?.let { saveHistory(it, exo.currentPosition) }
        }
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        if (::exo.isInitialized) exo.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "akis"
        private const val PERF = "akis.perf"
        private const val EXTRA_INDEX = "index"
        private const val SPONSOR_CHECK_MS = 300L
        private const val SEEK_MS = 10_000L

        /** What the next player plays. Kept in memory: a list of Shorts is too big for an Intent. */
        private var queue: List<Video> = emptyList()

        fun open(context: Context, video: Video, list: List<Video> = listOf(video)) {
            queue = list.ifEmpty { listOf(video) }
            val index = queue.indexOfFirst { it.url == video.url }.coerceAtLeast(0)
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_INDEX, index))
        }
    }
}
