package io.github.enestahtaci.seyir.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import io.github.enestahtaci.seyir.net.HttpDownloader
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the project's GitHub Releases (github.com/[REPO]): check the latest release, download its APK and hand
 * it to Android's installer.
 *
 * Safety: only HTTPS, only an APK from this repository's own release downloads, and before installing we check that the
 * APK carries the same signing certificate as the installed app (Android checks this too and refuses otherwise). A
 * look-alike APK from anywhere else is never installed.
 */
object Updater {
    const val REPO = "enestahtaci/seyir"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    /** Blocking. The newest published release, or null when there is none yet. Throws on network errors. */
    fun latest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Seyir-updater")
            if (c.responseCode == 404) return null // no release published yet
            if (c.responseCode != 200) throw IOException("GitHub ${c.responseCode}")
            val json = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.getString("browser_download_url")
                if (asset.getString("name").endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) {
                    return Release(json.getString("tag_name").removePrefix("v"), json.optString("body"), url, asset.getLong("size"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** True when [remote] ("0.2.0") is a higher version than [local]. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Blocking. Downloads the release APK into the app's cache, reporting progress 0..100. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        require(release.apkUrl.startsWith(DOWNLOAD_PREFIX)) { "unexpected download location" }
        require(release.size in 1..MAX_APK_BYTES) { "unexpected size" }
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "seyir.apk")
        // GitHub redirects to its file host (also HTTPS); HttpURLConnection follows same-protocol redirects only.
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", HttpDownloader.USER_AGENT)
            if (c.responseCode != 200) throw IOException("download ${c.responseCode}")
            var done = 0L
            var lastPercent = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("too large")
                        val percent = (done * 100 / release.size).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                }
            }
            if (done != release.size) throw IOException("incomplete download")
            return file
        } finally {
            c.disconnect()
        }
    }

    /** True when [apk] is Seyir signed with the same certificate as this installed copy. */
    fun isSameApp(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners ?: return false
        val theirs = archive.signingInfo?.apkContentsSigners ?: return false
        return mine.size == theirs.size &&
            mine.all { m -> theirs.any { t -> t.toByteArray().contentEquals(m.toByteArray()) } }
    }

    /** Whether Android lets Seyir install APKs ("unknown sources" for this app). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Hands the APK to Android's installer; the TV then shows its own "Install?" confirmation. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("seyir.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}
