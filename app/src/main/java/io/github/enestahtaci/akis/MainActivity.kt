package io.github.enestahtaci.akis

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.enestahtaci.akis.data.Db
import io.github.enestahtaci.akis.data.Feed
import io.github.enestahtaci.akis.data.Settings
import io.github.enestahtaci.akis.ui.CardAdapter
import io.github.enestahtaci.akis.ui.Header
import io.github.enestahtaci.akis.yt.Video
import io.github.enestahtaci.akis.yt.YouTube
import java.util.concurrent.Executors

/**
 * The main screen, laid out like YouTube on TV: menu on the left, the page on the right. Moving through the menu
 * switches pages at once (local data only, so it is instant); RIGHT/OK enters the page, BACK returns to the menu.
 */
class MainActivity : Activity() {
    private enum class Page { HOME, SUBSCRIPTIONS, SHORTS, SEARCH, HISTORY, SETTINGS }

    private lateinit var nav: ViewGroup
    private lateinit var settingsView: View
    private lateinit var settingsList: LinearLayout
    private lateinit var settings: Settings
    private lateinit var navItems: Map<Page, TextView>
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var db: Db
    private val adapter = CardAdapter(
        onVideo = { video, list -> PlayerActivity.open(this, video, if (video.isShort) list.filter { it.isShort } else listOf(video)) },
        onChannel = { ChannelActivity.open(this, it) },
        onVideoLong = { video -> video.channelUrl?.let { ChannelActivity.open(this, it) } },
    )
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val refresher = Executors.newSingleThreadExecutor()
    @Volatile private var refreshing = false

    private var page = Page.HOME
    private var loadId = 0
    private var searchResults: List<Any> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)
        nav = findViewById(R.id.nav)
        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        grid = findViewById(R.id.grid)
        settingsView = findViewById(R.id.settings)
        settingsList = findViewById(R.id.settingsList)
        settings = Settings.get(this)

        val layout = GridLayoutManager(this, COLUMNS)
        layout.spanSizeLookup = adapter.spanLookup(COLUMNS)
        grid.layoutManager = layout
        grid.adapter = adapter
        grid.setItemViewCacheSize(8)

        navItems = mapOf(
            Page.HOME to findViewById(R.id.navHome),
            Page.SUBSCRIPTIONS to findViewById(R.id.navSubscriptions),
            Page.SHORTS to findViewById(R.id.navShorts),
            Page.SEARCH to findViewById(R.id.navSearch),
            Page.HISTORY to findViewById(R.id.navHistory),
            Page.SETTINGS to findViewById(R.id.navSettings),
        )
        for ((p, view) in navItems) {
            view.setOnFocusChangeListener { _, focused -> if (focused && p != page) show(p) }
            view.setOnClickListener { enterPage() }
        }

        query.setOnEditorActionListener { _, actionId, event ->
            // A hardware/remote Enter arrives twice (key down and key up): act on the down only, swallow the up.
            if (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) search(query.text.toString().trim())
                return@setOnEditorActionListener true
            }
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(query.text.toString().trim()); true
            } else false
        }

        registerBack()
        show(Page.HOME)
        navItems.getValue(Page.HOME).requestFocus()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the player or a channel: history, progress lines and subscriptions may have changed.
        if (page != Page.SEARCH) show(page)
    }

    private fun show(p: Page) {
        page = p
        for ((key, view) in navItems) view.isSelected = key == p
        query.visibility = if (p == Page.SEARCH) View.VISIBLE else View.GONE
        settingsView.visibility = if (p == Page.SETTINGS) View.VISIBLE else View.GONE
        grid.visibility = if (p == Page.SETTINGS) View.GONE else View.VISIBLE
        val id = ++loadId

        if (p == Page.SETTINGS) {
            setStatus(null)
            buildSettings()
            return
        }
        if (p == Page.SEARCH) {
            adapter.show(searchResults, emptyMap())
            setStatus(if (searchResults.isEmpty()) getString(R.string.search_start) else null)
            return
        }
        Db.io.execute {
            val positions = db.positions()
            val (items, empty) = when (p) {
                Page.HOME -> {
                    val resume = db.history(40).filter { v ->
                        val at = positions[v.url] ?: 0
                        !v.isShort && v.durationSec > 0 && at > 10_000 && at < (v.durationSec - 20) * 1000
                    }.take(COLUMNS * 2)
                    val feed = db.feed()
                    val list = ArrayList<Any>()
                    if (resume.isNotEmpty()) { list.add(Header(getString(R.string.continue_watching))); list.addAll(resume) }
                    if (feed.isNotEmpty()) { list.add(Header(getString(R.string.latest_from_subscriptions))); list.addAll(feed) }
                    list to if (db.subscriptions().isEmpty()) R.string.no_subscriptions else R.string.loading
                }
                Page.SUBSCRIPTIONS -> db.subscriptions() to R.string.no_subscriptions
                Page.SHORTS -> db.feed(limit = 80, shorts = true) to R.string.no_shorts
                Page.HISTORY -> db.history() to R.string.no_history
                Page.SEARCH, Page.SETTINGS -> emptyList<Any>() to R.string.search_start
            }
            main.post {
                if (id != loadId) return@post
                adapter.show(items, positions)
                setStatus(if (items.isEmpty()) getString(empty) else null)
            }
        }
        if ((p == Page.HOME || p == Page.SHORTS) && Feed.isStale()) refreshFeed()
    }

    /** Fetches every subscription's latest videos in the background, then redraws the page if it shows the feed. */
    private fun refreshFeed() {
        if (refreshing) return
        refreshing = true
        refresher.execute {
            val started = System.nanoTime()
            Feed.refresh(db)
            Log.i("akis.perf", "feed refresh ${(System.nanoTime() - started) / 1_000_000} ms")
            refreshing = false
            main.post { if (!isDestroyed && (page == Page.HOME || page == Page.SHORTS)) show(page) }
        }
    }

    private fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // --- settings page ---

    /** Redraws every row's value (rows are built once; changing a value never removes the focused row). */
    private val settingsRefreshers = ArrayList<() -> Unit>()

    /**
     * Builds the settings rows once; afterwards OK only updates texts in place. (Rebuilding would remove the focused
     * row, and Android would hand the focus to the first menu item, switching pages.)
     */
    private fun buildSettings() {
        if (settingsList.childCount > 0) { settingsRefreshers.forEach { it() }; return }

        header(R.string.settings_playback)
        row(getString(R.string.settings_quality), { "${settings.maxHeight}p" }) {
            val all = Settings.QUALITIES
            settings.maxHeight = all[(all.indexOf(settings.maxHeight) + 1).mod(all.size)]
        }

        header(R.string.settings_sponsorblock)
        row(getString(R.string.settings_sponsorblock), { getString(if (settings.sponsorBlock) R.string.on else R.string.off) }) {
            settings.sponsorBlock = !settings.sponsorBlock
        }
        note(R.string.settings_sponsorblock_about)
        header(R.string.settings_skip_categories)
        for ((key, label) in Settings.CATEGORIES) {
            val view = row(label, { getString(if (settings.skips(key)) R.string.skip else R.string.dont_skip) }) {
                settings.setSkips(key, !settings.skips(key))
            }
            // With SponsorBlock off the categories stay visible but greyed out and skipped by the remote.
            settingsRefreshers += { view.isFocusable = settings.sponsorBlock; view.alpha = if (settings.sponsorBlock) 1f else 0.4f }
        }

        header(R.string.settings_data)
        var confirming = false
        val clear = row(getString(R.string.settings_clear_history), { "" }) {}
        clear.setOnClickListener {
            if (!confirming) {
                confirming = true
                clear.text = getString(R.string.settings_clear_confirm)
            } else {
                confirming = false
                Db.io.execute { db.clearHistory() }
                clear.text = getString(R.string.settings_history_cleared)
            }
        }
        clear.setOnFocusChangeListener { _, focused ->
            if (!focused && confirming) { confirming = false; clear.text = getString(R.string.settings_clear_history) }
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        note(0, getString(R.string.settings_version, version))
        settingsRefreshers.forEach { it() }
    }

    private fun header(text: Int) {
        val view = layoutInflater.inflate(R.layout.item_header, settingsList, false) as TextView
        view.setText(text)
        settingsList.addView(view)
    }

    private fun note(text: Int, literal: String? = null) {
        val view = TextView(this)
        view.text = literal ?: getString(text)
        view.setTextColor(getColor(R.color.muted))
        view.textSize = 16f
        view.setPadding(24, 4, 24, 12)
        settingsList.addView(view)
    }

    /** A focusable row "label:  value"; OK runs [change], then every row redraws its value in place. */
    private fun row(label: String, value: () -> String, change: () -> Unit): TextView {
        val view = TextView(this, null, 0, R.style.Akis_Setting)
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (6 * resources.displayMetrics.density).toInt() }
        settingsRefreshers += { value().let { v -> view.text = if (v.isEmpty()) label else "$label:  $v" } }
        view.setOnClickListener { change(); settingsRefreshers.forEach { it() } }
        settingsList.addView(view)
        return view
    }

    private fun enterPage() {
        if (page == Page.SEARCH) { query.requestFocus(); return }
        if (page == Page.SETTINGS) {
            (0 until settingsList.childCount).map { settingsList.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
            return
        }
        val first = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.isFocusable }
        first?.requestFocus()
    }

    private fun search(text: String) {
        if (text.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        // A YouTube link or an 11-character video ID opens the video directly.
        YouTube.videoUrlOf(text)?.let { url ->
            PlayerActivity.open(this, Video(url, "", "", null, 0, -1, null, isLive = false, isShort = url.contains("/shorts/")))
            return
        }
        val id = ++loadId
        setStatus(getString(R.string.searching))
        val started = System.nanoTime()
        network.execute {
            val result = runCatching { YouTube.search(text) }
            main.post {
                if (id != loadId || isDestroyed) return@post
                result.onSuccess { items ->
                    Log.i("akis.perf", "search ${items.size} results in ${(System.nanoTime() - started) / 1_000_000} ms")
                    searchResults = items
                    adapter.show(items, emptyMap())
                    setStatus(if (items.isEmpty()) getString(R.string.no_results) else null)
                    if (items.isNotEmpty()) grid.post { enterPage(); grid.getChildAt(0)?.requestFocus() }
                }.onFailure {
                    Log.w("akis", "search failed: ${it.javaClass.simpleName}")
                    setStatus(getString(R.string.network_error))
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val inNav = focused?.parent === nav
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (inNav) { enterPage(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!inNav && focused != null && focused !is EditText) {
                    // Leaving the page to the left always lands on the current menu item (not the nearest one, which
                    // would switch pages).
                    val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, focused, View.FOCUS_LEFT)
                    if (next == null || next.parent === nav) { navItems.getValue(page).requestFocus(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** BACK inside a page returns to the menu; BACK on the menu leaves the app. */
    private fun back() {
        if (currentFocus?.parent !== nav) navItems.getValue(page).requestFocus() else finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    @Deprecated("Android 12 and older; newer versions use the callback from registerBack()")
    @android.annotation.SuppressLint("GestureBackNavigation") // only reached below API 33 (same as enestv)
    override fun onBackPressed() = back()

    override fun onDestroy() {
        network.shutdownNow()
        refresher.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 4
    }
}
