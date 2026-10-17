package com.gpo.pilipala.core.util

/**
 * Normalises bilibili image URLs and asks the CDN for a resized webp.
 * `//i0.hdslb.com/…jpg` → `https://i0.hdslb.com/…jpg@480w_270h_1c.webp`
 */
object Images {
    fun normalize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u
    }

    fun thumb(url: String?, widthPx: Int, heightPx: Int? = null): String {
        val u = normalize(url)
        if (u.isEmpty() || !u.contains("hdslb.com") || u.contains('@') || u.endsWith(".gif")) return u
        val w = widthPx.coerceIn(48, 1920)
        return if (heightPx != null) "$u@${w}w_${heightPx.coerceIn(48, 1920)}h_1c.webp" else "$u@${w}w.webp"
    }
}
