package com.gpo.pilipala.core.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

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
        val diff = System.currentTimeMillis() / 1000 - unixSeconds
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60}分钟前"
            diff < 2 * 86400 -> {
                val zone = zone()
                val day = localDate(unixSeconds, zone)
                if (diff < 86400 && day == LocalDate.now(zone)) "${diff / 3600}小时前" else "昨天"
            }
            else -> {
                val zone = zone()
                val t = Instant.ofEpochSecond(unixSeconds).atZone(zone)
                (if (t.year == LocalDate.now(zone).year) MonthDayFormat else YearMonthDayFormat).format(t)
            }
        }
    }

    fun date(unixSeconds: Long, pattern: String = "yyyy-MM-dd"): String {
        if (unixSeconds <= 0) return ""
        val t = Instant.ofEpochSecond(unixSeconds).atZone(zone())
        val formatter = formatters.getOrPut(pattern) {
            runCatching { DateTimeFormatter.ofPattern(pattern, Locale.CHINA) }.getOrNull() ?: return legacyDate(unixSeconds, pattern)
        }
        return formatter.format(t)
    }

    /** A pattern java.time does not take (none in use today): the old formatter. */
    private fun legacyDate(unixSeconds: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.CHINA).format(Date(unixSeconds * 1000))

    /** Strip `<em class="keyword">` highlight tags from search results. */
    fun stripTags(s: String): String {
        if (s.indexOf('<') < 0 && s.indexOf('&') < 0) return s
        return s.replace(TagRegex, "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    }

    // Formatting runs in list item composition and in page mapping: compile
    // the regex and build the (thread-safe, immutable) formatters once.
    private val TagRegex = Regex("<[^>]+>")
    private val MonthDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
    private val YearMonthDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

    // The cached ZoneId follows a system time zone change (read per call; only the id is compared).
    @Volatile private var cachedZone: Pair<String, ZoneId>? = null

    private fun zone(): ZoneId {
        val id = TimeZone.getDefault().id
        cachedZone?.let { (cachedId, zone) -> if (cachedId == id) return zone }
        return ZoneId.of(id).also { cachedZone = id to it }
    }

    private fun localDate(unixSeconds: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(unixSeconds).atZone(zone).toLocalDate()
}
