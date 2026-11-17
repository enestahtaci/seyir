package io.github.enestahtaci.seyir.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Turkish display text for numbers, durations and dates, the way YouTube shows them ("1,2 Mn görüntüleme", "12:04"). */
object Format {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val oneDecimal = DecimalFormat("0.#", DecimalFormatSymbols(tr))

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(tr, "%d:%02d:%02d", h, m, s) else String.format(tr, "%d:%02d", m, s)
    }

    private fun count(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> "${oneDecimal.format(n / 1_000.0)} B"
        n < 1_000_000_000 -> "${oneDecimal.format(n / 1_000_000.0)} Mn"
        else -> "${oneDecimal.format(n / 1_000_000_000.0)} Mr"
    }

    fun views(n: Long): String = if (n < 0) "" else "${count(n)} görüntüleme"

    fun subscribers(n: Long): String = if (n < 0) "" else "${count(n)} abone"

    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return ""
        val minutes = (now - epochMs) / 60_000
        return when {
            minutes < 1 -> "şimdi"
            minutes < 60 -> "$minutes dakika önce"
            minutes < 60 * 24 -> "${minutes / 60} saat önce"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} gün önce"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24 * 7)} hafta önce"
            minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)} ay önce"
            else -> "${minutes / (60 * 24 * 365)} yıl önce"
        }
    }
}
