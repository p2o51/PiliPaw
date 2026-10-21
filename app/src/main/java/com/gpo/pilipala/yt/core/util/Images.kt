package com.gpo.pilipala.yt.core.util

/**
 * YouTube image URLs. Thumbnails (i.ytimg.com) come in fixed variants the extractor already
 * lists, so the model picks one ([com.gpo.pilipala.yt.core.extractor.bestImage]); avatars and
 * banners on the googleusercontent / ggpht CDNs take a size suffix (`=s88-c-k…`), which
 * [thumb] rewrites to the slot's width.
 */
object Images {
    fun normalize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u
    }

    fun thumb(url: String?, widthPx: Int, @Suppress("UNUSED_PARAMETER") heightPx: Int? = null): String {
        val u = normalize(url)
        if (u.isEmpty()) return u
        val sized = u.contains("ggpht.com") || u.contains("googleusercontent.com")
        if (!sized) return u
        val w = widthPx.coerceIn(48, 2560)
        return SizeSuffix.replace(u) { "=s$w${it.groupValues[1]}" }
    }

    private val SizeSuffix = Regex("""=s\d+(-[^/?]*)?$""")
}
