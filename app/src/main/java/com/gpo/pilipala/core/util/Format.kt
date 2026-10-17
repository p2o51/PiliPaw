package com.gpo.pilipala.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Display formatting shared by every screen. */
object Fmt {
    /** 12345 → 1.2万, 123456789 → 1.2亿. */
    fun count(n: Long): String = when {
        n >= 100_000_000L -> trim(n / 100_000_000.0) + "亿"
        n >= 10_000L -> trim(n / 10_000.0) + "万"
        else -> n.toString()
    }

    fun count(n: Int): String = count(n.toLong())

    /** Accepts already-formatted strings like "1.2万" from some endpoints. */
    fun count(s: String): String = s.toLongOrNull()?.let { count(it) } ?: s

    private fun trim(d: Double): String {
        val s = String.format(Locale.US, "%.1f", d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Seconds → `m:ss` / `h:mm:ss`. */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000)

    /** "03:21" / "1:02:03" strings → seconds. */
    fun parseDuration(s: String): Long = s.split(':').fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: 0) }

    /** Unix seconds → relative Chinese time (刚刚 / 5分钟前 / 昨天 / 03-12 / 2023-03-12). */
    fun relativeTime(unixSeconds: Long): String {
        if (unixSeconds <= 0) return ""
        val now = System.currentTimeMillis()
        val t = unixSeconds * 1000
        val diff = (now - t) / 1000
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val nowCal = Calendar.getInstance()
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60}分钟前"
            diff < 86400 && cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) -> "${diff / 3600}小时前"
            diff < 2 * 86400 -> "昨天"
            cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(t))
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(t))
        }
    }

    fun date(unixSeconds: Long, pattern: String = "yyyy-MM-dd"): String =
        if (unixSeconds <= 0) "" else SimpleDateFormat(pattern, Locale.CHINA).format(Date(unixSeconds * 1000))

    /** Strip `<em class="keyword">` highlight tags from search results. */
    fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
}
