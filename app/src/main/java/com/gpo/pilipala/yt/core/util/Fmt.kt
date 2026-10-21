package com.gpo.pilipala.yt.core.util

import java.util.Locale

/** Numbers and times as labels: bold values, quiet units, never joined with "·". */
object Fmt {
    /** 1234 → 1234, 12345 → 1.2万, 123456789 → 1.2亿. Negative (unknown) → "". */
    fun count(n: Long): String = when {
        n < 0 -> ""
        n < 10_000 -> n.toString()
        n < 100_000_000 -> trim(n / 10_000.0) + "万"
        else -> trim(n / 100_000_000.0) + "亿"
    }

    /** Seconds → 3:07 / 1:02:03. Unknown (≤ 0) → "". */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /**
     * Epoch millis → 刚刚 / 5分钟前 / 3小时前 / 2天前 / 4周前 / 3个月前 / 2年前. Unknown (≤ 0) → "".
     * The extractor asks YouTube in Zulu (so titles are never auto-translated), so its own
     * relative text is unusable; we date the parsed instant ourselves.
     */
    fun ago(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0) return ""
        val minutes = ((now - epochMillis) / 60_000).coerceAtLeast(0)
        val days = minutes / (60 * 24)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            minutes < 60 * 24 -> "${minutes / 60}小时前"
            days < 7 -> "${days}天前"
            days < 30 -> "${days / 7}周前"
            days < 365 -> "${days / 30}个月前"
            else -> "${days / 365}年前"
        }
    }

    private fun trim(v: Double): String {
        val r = String.format(Locale.ROOT, "%.1f", v)
        return if (r.endsWith(".0")) r.dropLast(2) else r
    }
}
