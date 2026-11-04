package com.gpo.pilipala.feature.player

import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.core.util.ScopeErrors
import com.gpo.pilipala.feature.player.cdn.CdnHosts
import com.gpo.pilipala.feature.player.cdn.CdnRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Starts a video's playurl the moment it is opened (Navigator.push), before its page,
 * view model and player exist, and opens the connection to the CDN line it will read
 * from as soon as the URLs are known. The player then takes the answer instead of asking
 * again ([take]), and its first media request reuses the warm connection (one shared
 * OkHttp pool), saving an API round trip and a TLS handshake.
 *
 * One entry per video, used once, dropped after [TTL_MS]: a later fetch (quality change,
 * retry) always asks Bilibili fresh.
 */
internal object PlayUrlPrefetch {
    private const val TTL_MS = 60_000L

    private class Entry(val key: String, val at: Long, val info: Deferred<PlayInfo>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeErrors)
    @Volatile private var entry: Entry? = null

    private fun keyOf(t: PlayTarget, qn: Int): String {
        val bv = t.bvid.ifEmpty { if (t.aid > 0) IdUtils.av2bv(t.aid) else "" }
        return "$bv|${t.cid}|${t.epId}|${t.isPgc}|$qn"
    }

    /** A plain video about to open; ignored without a cid (its page resolves the cid first). */
    fun start(bvid: String, aid: Long, cid: Long) {
        if (cid <= 0 || (bvid.isEmpty() && aid <= 0)) return
        val t = PlayTarget(bvid = bvid, aid = aid, cid = cid)
        val qn = AppSettings.defaultVideoQuality.value
        val key = keyOf(t, qn)
        entry?.let { if (it.key == key && System.currentTimeMillis() - it.at < TTL_MS) return }
        val info = scope.async { PlayUrlApi.fetch(t, qn) }
        entry = Entry(key, System.currentTimeMillis(), info)
        scope.launch { runCatching { preconnect(info.await(), qn) } }
    }

    /** The prefetched answer for [t] at [qn] (awaited if still in flight), or a fresh fetch. */
    suspend fun take(t: PlayTarget, qn: Int): PlayInfo {
        val e = entry
        if (e != null && e.key == keyOf(t, qn) && System.currentTimeMillis() - e.at < TTL_MS) {
            entry = null
            runCatching { e.info.await() }.onSuccess { return it }
        }
        return PlayUrlApi.fetch(t, qn)
    }

    /**
     * A one-byte read of the streams the player will pick, on the hosts it will route to:
     * DNS, TCP and TLS are done before the player asks. Bilibili's UPOS lines speak HTTP/2,
     * so one connection carries video and audio; Akamai is HTTP/1.1, so each host gets one.
     */
    private fun preconnect(pi: PlayInfo, wish: Int) {
        val urls = if (pi.isDash) {
            val qn = StreamPicker.videoQn(pi, wish)
            val codec = Qualities.codecIdForSetting(AppSettings.preferredCodec.value)
            listOfNotNull(
                StreamPicker.video(pi, qn, codec),
                StreamPicker.audio(pi, AppSettings.defaultAudioQuality.value),
            ).mapNotNull { CdnRouter.orderUrls(it.urls).firstOrNull() }
        } else {
            listOfNotNull(pi.durl.firstOrNull()?.urls?.let { CdnRouter.orderUrls(it).firstOrNull() })
        }
        val seen = HashSet<String>()
        for (url in urls.map(CdnRouter::route)) {
            if (!seen.add(CdnHosts.hostOf(url))) continue
            val req = Request.Builder().url(url)
                .apply { BiliClient.mediaHeaders().forEach { (k, v) -> header(k, v) } }
                .header("Range", "bytes=0-0")
                .build()
            runCatching { BiliClient.http.newCall(req).execute().use { it.body.bytes() } }
        }
    }
}
