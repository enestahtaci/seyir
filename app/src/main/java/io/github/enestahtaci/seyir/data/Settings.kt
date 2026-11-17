package io.github.enestahtaci.seyir.data

import android.content.Context
import android.content.SharedPreferences

/** User choices, on this TV only. Small and read often, so SharedPreferences (kept in memory after the first read). */
class Settings private constructor(private val prefs: SharedPreferences) {

    /** Highest video quality to pick, as the short side in pixels (1080 = 1080p). */
    var maxHeight: Int
        get() = prefs.getInt(KEY_QUALITY, 1080)
        set(value) = prefs.edit().putInt(KEY_QUALITY, value).apply()

    var sponsorBlock: Boolean
        get() = prefs.getBoolean(KEY_SPONSORBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPONSORBLOCK, value).apply()

    /** When the app last asked GitHub for a new version (epoch ms); checked at most every few hours. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("update.checked", 0)
        set(value) = prefs.edit().putLong("update.checked", value).apply()

    fun skips(category: String): Boolean = prefs.getBoolean("sb.$category", category in DEFAULT_SKIPS)

    fun setSkips(category: String, skip: Boolean) = prefs.edit().putBoolean("sb.$category", skip).apply()

    /** The categories to skip, or none when SponsorBlock is off. */
    fun skippedCategories(): List<String> = if (!sponsorBlock) emptyList() else CATEGORIES.map { it.first }.filter(::skips)

    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_SPONSORBLOCK = "sponsorblock"

        val QUALITIES = listOf(2160, 1440, 1080, 720, 480)

        /** SponsorBlock categories with their Turkish names, in the order the settings page lists them. */
        val CATEGORIES = listOf(
            "sponsor" to "Sponsor",
            "selfpromo" to "Kendi tanıtımı ve bağış çağrısı",
            "interaction" to "Abone ol / beğen hatırlatması",
            "intro" to "Giriş animasyonu",
            "outro" to "Kapanış ve bitiş kartları",
            "preview" to "Önizleme / özet",
            "filler" to "Konu dışı kısımlar",
            "music_offtopic" to "Müzik videolarında müzik dışı kısım",
        )
        private val DEFAULT_SKIPS = setOf("sponsor", "selfpromo", "interaction")

        fun label(category: String) = CATEGORIES.firstOrNull { it.first == category }?.second ?: category

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext.getSharedPreferences("seyir", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
