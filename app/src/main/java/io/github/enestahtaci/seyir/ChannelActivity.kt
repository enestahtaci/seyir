package io.github.enestahtaci.seyir

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.enestahtaci.seyir.data.Db
import io.github.enestahtaci.seyir.data.Feed
import io.github.enestahtaci.seyir.ui.CardAdapter
import io.github.enestahtaci.seyir.ui.Format
import io.github.enestahtaci.seyir.ui.Thumbnails
import io.github.enestahtaci.seyir.yt.Channel
import io.github.enestahtaci.seyir.yt.ChannelPage
import io.github.enestahtaci.seyir.yt.Video
import io.github.enestahtaci.seyir.yt.YouTube
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import java.util.concurrent.Executors

/** A channel: header with the subscribe button, then its Videos / Shorts / Live tabs. */
class ChannelActivity : Activity() {
    private lateinit var db: Db
    private lateinit var subscribe: TextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var tabViews: Map<String, TextView>
    private val adapter = CardAdapter(
        onVideo = { video, list -> PlayerActivity.open(this, video, if (video.isShort) list else listOf(video)) },
        onChannel = {},
    )
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private var page: ChannelPage? = null
    private var subscribed = false
    private var tab = ChannelTabs.VIDEOS
    private var loadId = 0
    private val tabCache = HashMap<String, List<Video>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)
        db = Db.get(this)
        subscribe = findViewById(R.id.subscribe)
        status = findViewById(R.id.status)
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter
        findViewById<ImageView>(R.id.avatar).clipToOutline = true

        tabViews = mapOf(
            ChannelTabs.VIDEOS to findViewById(R.id.tabVideos),
            ChannelTabs.SHORTS to findViewById(R.id.tabShorts),
            ChannelTabs.LIVESTREAMS to findViewById(R.id.tabLive),
        )
        for ((key, view) in tabViews) {
            view.visibility = View.GONE
            view.setOnFocusChangeListener { _, focused -> if (focused && key != tab) showTab(key) }
            view.setOnClickListener { grid.getChildAt(0)?.requestFocus() }
        }
        subscribe.setOnClickListener { toggleSubscription() }

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        intent.getStringExtra(EXTRA_NAME)?.let { findViewById<TextView>(R.id.name).text = it }

        Db.io.execute {
            val isSubscribed = db.isSubscribed(url)
            main.post { subscribed = isSubscribed; drawSubscribe() }
        }
        network.execute {
            val result = runCatching { YouTube.channel(url) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { show(it) }.onFailure {
                    Log.w("seyir", "channel failed: ${it.javaClass.simpleName}")
                    status.setText(R.string.network_error)
                }
            }
        }
    }

    private fun show(loaded: ChannelPage) {
        page = loaded
        val channel = loaded.channel
        findViewById<TextView>(R.id.name).text = channel.name
        findViewById<TextView>(R.id.detail).text = Format.subscribers(channel.subscribers)
        Thumbnails.load(findViewById(R.id.avatar), channel.avatarUrl)
        // The subscription is stored under the URL YouTube reports (channel/UC…), whatever link opened the page.
        Db.io.execute {
            val isSubscribed = db.isSubscribed(channel.url)
            main.post { subscribed = isSubscribed; drawSubscribe() }
        }
        subscribe.visibility = View.VISIBLE
        for ((key, view) in tabViews) view.visibility = if (key in loaded.tabs) View.VISIBLE else View.GONE
        val first = listOf(ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.LIVESTREAMS).firstOrNull { it in loaded.tabs }
        if (first == null) { status.setText(R.string.empty_tab); return }
        // The button sits at the right edge; DOWN would find nothing below it. Send it to the tabs, and the tabs back up.
        val firstTab = tabViews.getValue(first)
        subscribe.nextFocusDownId = firstTab.id
        for (view in tabViews.values) view.nextFocusUpId = subscribe.id
        showTab(first)
        subscribe.requestFocus()
    }

    private fun showTab(key: String) {
        tab = key
        for ((k, view) in tabViews) view.isSelected = k == key
        val handler = page?.tabs?.get(key) ?: return
        val id = ++loadId
        tabCache[key]?.let { display(it); return }
        status.setText(R.string.loading)
        status.visibility = View.VISIBLE
        network.execute {
            val result = runCatching { YouTube.channelTab(handler) }
            main.post {
                if (id != loadId || isDestroyed) return@post
                result.onSuccess { videos ->
                    // Shorts from the Shorts tab are always Shorts, whatever the item says.
                    val list = if (key == ChannelTabs.SHORTS) videos.map { it.copy(isShort = true) } else videos
                    tabCache[key] = list
                    display(list)
                }.onFailure { status.setText(R.string.network_error) }
            }
        }
    }

    private fun display(videos: List<Video>) {
        Db.io.execute {
            val positions = db.positions()
            main.post {
                adapter.show(videos, positions)
                status.setText(R.string.empty_tab)
                status.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toggleSubscription() {
        val channel = page?.channel ?: return
        subscribed = !subscribed
        drawSubscribe()
        val nowSubscribed = subscribed
        Db.io.execute {
            if (nowSubscribed) db.subscribe(channel) else db.unsubscribe(channel.url)
        }
        // Fill the home feed with the new channel right away.
        if (nowSubscribed) network.execute { Feed.refreshChannel(db, channel) }
    }

    private fun drawSubscribe() {
        subscribe.isSelected = subscribed
        subscribe.setText(if (subscribed) R.string.unsubscribe else R.string.subscribe)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Going up from the videos lands on the open tab, not the nearest one (which would switch tabs).
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val focused = currentFocus
            if (focused != null && focused.parent === grid) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, focused, View.FOCUS_UP)
                if (next != null && next in tabViews.values) { tabViews.getValue(tab).requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        network.shutdown() // let a running subscribe-refresh finish
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun open(context: Context, url: String) {
            context.startActivity(Intent(context, ChannelActivity::class.java).putExtra(EXTRA_URL, url))
        }

        fun open(context: Context, channel: Channel) {
            context.startActivity(
                Intent(context, ChannelActivity::class.java).putExtra(EXTRA_URL, channel.url).putExtra(EXTRA_NAME, channel.name)
            )
        }
    }
}
