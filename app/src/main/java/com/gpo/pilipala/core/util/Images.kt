package com.gpo.pilipala.core.util

/**
 * Normalises bilibili image URLs and asks the CDN for a resized copy.
 * `//i0.hdslb.com/…jpg` → `https://i0.hdslb.com/…jpg@480w_270h_1c.avif`
 *
 * AVIF on Android 12+ (decoded by the platform): about half webp's bytes at the same size
 * (a 480×270 cover 25 KB → 13 KB). PNGs (emotes, stickers) stay webp so their
 * transparency is never at the mercy of the CDN's AVIF encoder; older Android gets webp.
 */
object Images {
    fun normalize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u
    }

    private val avif = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    fun thumb(url: String?, widthPx: Int, heightPx: Int? = null): String {
        val u = normalize(url)
        if (u.isEmpty() || !u.contains("hdslb.com") || u.contains('@') || u.endsWith(".gif")) return u
        val w = widthPx.coerceIn(48, 1920)
        val ext = if (avif && !u.substringBefore('?').endsWith(".png", ignoreCase = true)) "avif" else "webp"
        return if (heightPx != null) "$u@${w}w_${heightPx.coerceIn(48, 1920)}h_1c.$ext" else "$u@${w}w.$ext"
    }
}
