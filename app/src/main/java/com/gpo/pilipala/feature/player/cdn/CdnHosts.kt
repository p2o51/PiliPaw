package com.gpo.pilipala.feature.player.cdn

import android.net.Uri
import java.net.URLEncoder

/**
 * Bilibili media host knowledge for overseas acceleration.
 *
 * Ported (logic, not code) from realzza/bilibili-accelerator (MIT):
 * - UPOS mirrors accept each other's signed path, so a UPOS URL can be moved to
 *   any pool mirror by swapping the host. Akamai URLs carry their own `hdnts`
 *   token and can't be host-swapped (a swap onto Akamai is refused with 403).
 * - MCDN (`*.mcdn.bilivideo.*`) is reachable through the official proxy
 *   `proxy-tf-all-ws.bilivideo.com/?url=`.
 * - PCDN / P2P endpoints (bare IPs, odd ports, `os=mcdn`, szbdyd/mountaintoys…)
 *   are the usual source of stalls overseas.
 */
object CdnHosts {
    data class Host(val host: String, val overseas: Boolean, val vendor: String) {
        val region: String get() = if (overseas) "海外" else "大陆"
    }

    /** Race / fixed-selection pool, the accelerator's set minus Akamai. */
    val POOL: List<Host> = listOf(
        Host("upos-sz-mirrorcosov.bilivideo.com", true, "腾讯云"),
        Host("upos-sz-mirroraliov.bilivideo.com", true, "阿里云"),
        Host("upos-sz-mirrorhwov.bilivideo.com", true, "华为云"),
        Host("upos-sz-mirrorali.bilivideo.com", false, "阿里云"),
        Host("upos-sz-mirrorhw.bilivideo.com", false, "华为云"),
        Host("upos-sz-mirrorcos.bilivideo.com", false, "腾讯云"),
        Host("upos-tf-all-hw.bilivideo.com", false, "华为云 TF"),
        Host("upos-tf-all-tx.bilivideo.com", false, "腾讯云 TF"),
    )

    const val PROXY_HOST = "proxy-tf-all-ws.bilivideo.com"
    /** Where PCDN URLs go when nothing better was issued. */
    const val DEFAULT_TARGET = "upos-sz-mirrorcosov.bilivideo.com"

    enum class Kind { Upos, Akamai, Mcdn, Pcdn, Other }

    private val ipRe = Regex("""^\d{1,3}(\.\d{1,3}){3}$|^\[[0-9a-f:]+]$""", RegexOption.IGNORE_CASE)
    private val xyMcdnRe = Regex("""^xy(?:\d+x){3}\d+xy\.mcdn\.bilivideo\.(?:cn|com|net)$""", RegexOption.IGNORE_CASE)
    private val mcdnRe = Regex("""\.mcdn\.bilivideo\.(?:cn|com|net)$""", RegexOption.IGNORE_CASE)
    private val p2pSuffixes = listOf(".szbdyd.com", ".mountaintoys.cn", ".nexusedgeio.com", ".edge.mountaintoys.cn")
    private val p2pHosts = setOf("upos-sz-mirror14b.bilivideo.com")

    fun classify(url: String): Kind {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Kind.Other
        val host = uri.host?.lowercase() ?: return Kind.Other
        val port = uri.port
        val oddPort = port != -1 && port != 80 && port != 443
        val queryMcdn = uri.getQueryParameter("os") == "mcdn"
        return when {
            mcdnRe.containsMatchIn(host) && !xyMcdnRe.matches(host) -> Kind.Mcdn
            ipRe.matches(host) || xyMcdnRe.matches(host) || oddPort || queryMcdn ||
                p2pSuffixes.any { host.endsWith(it) } || host in p2pHosts -> Kind.Pcdn
            host.endsWith(".akamaized.net") -> Kind.Akamai
            host.startsWith("upos-") || host.endsWith(".bilivideo.com") -> Kind.Upos
            else -> Kind.Other
        }
    }

    fun hostOf(url: String): String = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")

    /** Same bytes on another UPOS mirror; null when the URL can't be moved. */
    fun swap(url: String, targetHost: String): String? {
        val kind = classify(url)
        if (kind == Kind.Akamai || kind == Kind.Mcdn || kind == Kind.Other) return null
        val uri = Uri.parse(url)
        return uri.buildUpon().scheme("https").encodedAuthority(targetHost).build().toString()
    }

    fun proxyMcdn(url: String): String =
        "https://$PROXY_HOST/?url=" + URLEncoder.encode(url, "UTF-8")

    fun find(host: String): Host? = POOL.firstOrNull { it.host == host }

    /** Two-part label for UI: region + vendor (rendered as separate texts, never joined with "·"). */
    fun describe(host: String): Pair<String, String> {
        find(host)?.let { return it.region to it.vendor }
        val h = host.lowercase()
        return when {
            h.endsWith(".akamaized.net") -> "海外" to "Akamai"
            h.contains(".mcdn.") -> "P2P" to "MCDN"
            h == PROXY_HOST -> "代理" to "MCDN 代理"
            h.isEmpty() -> "" to ""
            else -> {
                val m = Regex("""^upos-[a-z]+-mirror([a-z0-9]+?)(ov)?\.bilivideo\.com$""").find(h)
                if (m != null) {
                    val vendor = when (m.groupValues[1]) {
                        "cos", "tx" -> "腾讯云"
                        "ali" -> "阿里云"
                        "hw" -> "华为云"
                        else -> m.groupValues[1]
                    }
                    (if (m.groupValues[2].isNotEmpty()) "海外" else "大陆") to vendor
                } else {
                    "其他" to h
                }
            }
        }
    }
}
