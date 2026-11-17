package io.github.enestahtaci.seyir.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import io.github.enestahtaci.seyir.net.HttpDownloader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Small thumbnail loader (no image library): 3 background threads, decoded at card size in RGB_565 (half the memory of
 * ARGB), kept in a 16 MB memory cache. The view's tag holds the URL it wants, so a recycled card never shows the wrong
 * picture when an older download finishes late.
 */
object Thumbnails {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TARGET_WIDTH = 400

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url == null) { view.setImageDrawable(null); return }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        pool.execute {
            if (view.tag != url) return@execute // scrolled away before we started
            val bitmap = runCatching { decode(download(url)) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun download(url: String): ByteArray {
        if (!url.startsWith("https://")) return ByteArray(0)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", HttpDownloader.USER_AGENT)
            if (c.responseCode !in 200..299) return ByteArray(0)
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return ByteArray(0)
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}
