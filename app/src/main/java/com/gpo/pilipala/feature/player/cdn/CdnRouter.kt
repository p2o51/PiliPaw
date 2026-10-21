package com.gpo.pilipala.feature.player.cdn

import com.gpo.pilipala.core.util.ScopeErrors
import android.content.Context
import android.content.SharedPreferences
import com.gpo.pilipala.core.net.BiliClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Per-video line routing — the bilibili-accelerator v0.5 engine adapted to
 * ExoPlayer's progressive DASH loading (one long range read per stream):
 *
 * 1. Start on the host Bilibili issued. Popular videos never race.
 * 2. Measure what the player actually receives on the video stream.
 * 3. When the line can't keep up (goodput < 1.2× the video bitrate after 8 s
 *    or 4 MB, with < 30 s buffered) or errors twice in 30 s, race two
 *    challengers on the next 768 KB; switch only if the winner needs at most
 *    two-thirds of the time the current line would. Then stay.
 * 4. A switch takes effect by ending the current read with an IOException;
 *    ExoPlayer retries at once and [route] sends the retry to the winner.
 *
 * At most four switches per video, cooldown 10 → 20 → 40 s. Routing state is
 * per session and never saved; only per-host history (3-day half-life) is.
 */
object CdnRouter {
    data class Status(
        /** Host the video stream is actually read from. */
        val host: String = "",
        val switched: Boolean = false,
        val switches: Int = 0,
        /** Measured video goodput, bits/s; 0 = not measured yet. */
        val goodputBps: Long = 0,
        val requiredBps: Long = 0,
        val racing: Boolean = false,
        /** One-line result of the last race ("已切换" / "当前线路最快"…). */
        val note: String = "",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeErrors)
    private lateinit var prefs: SharedPreferences
    private val raceClient: OkHttpClient by lazy {
        BiliClient.http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    // ── Session ─────────────────────────────────────────────────────────────

    private class Session(
        val id: Long,
        /** Every issued URL of the video streams, all representations. */
        val videoUrls: Set<String>,
        val audioUrls: Set<String>,
        val issuedHosts: Set<String>,
        @Volatile var requiredBps: Long,
        val bufferedAheadMs: () -> Long,
    ) {
        @Volatile var activeHost: String? = null
        @Volatile var switches = 0
        @Volatile var lastSwitchAt = 0L
        @Volatile var nextRaceAt = 0L
        @Volatile var racing = false
        @Volatile var abortGeneration = 0
        val errors = ConcurrentHashMap<String, MutableList<Long>>()
        val lostAt = ConcurrentHashMap<String, Long>()
    }

    @Volatile private var session: Session? = null
    private var sessionSeq = 0L

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("pili_cdn_history", Context.MODE_PRIVATE)
    }

    /**
     * Start routing a new video. [videoUrls]/[audioUrls] are all issued URLs
     * (base + backups) of the picked representations; [requiredBps] is the
     * picked video bandwidth.
     */
    fun beginSession(
        videoUrls: List<String>,
        audioUrls: List<String>,
        requiredBps: Long,
        bufferedAheadMs: () -> Long,
    ) {
        val all = videoUrls + audioUrls
        session = Session(
            id = ++sessionSeq,
            videoUrls = videoUrls.toSet(),
            audioUrls = audioUrls.toSet(),
            issuedHosts = all.map(CdnHosts::hostOf).filter { it.isNotEmpty() }.toSet(),
            requiredBps = requiredBps,
            bufferedAheadMs = bufferedAheadMs,
        )
        val first = videoUrls.firstOrNull()?.let { route(it) }?.let(CdnHosts::hostOf).orEmpty()
        _status.value = Status(host = first, requiredBps = requiredBps)
        ParallelLoad.reset()
    }

    /** Quality change: judge against the highest bitrate requested this video. */
    fun updateRequired(bps: Long) {
        val s = session ?: return
        if (bps > s.requiredBps) s.requiredBps = bps
        _status.value = _status.value.copy(requiredBps = s.requiredBps)
    }

    fun endSession() {
        session = null
        _status.value = Status()
        ParallelLoad.reset()
    }

    /** Current video's routing session id; -1 = none. */
    fun sessionId(): Long = session?.id ?: -1L

    private val audioFileRe = Regex("-302\\d\\d\\.m4s$")

    private fun pathOf(url: String): String? = runCatching { java.net.URI(url).rawPath }.getOrNull()

    /** An audio stream file (by the session's issued URLs, or Bilibili's 302xx audio ids). */
    fun isAudioUrl(url: String): Boolean {
        val path = pathOf(url) ?: return false
        val s = session
        if (s != null && s.audioUrls.any { pathOf(it) == path }) return true
        return audioFileRe.containsMatchIn(path)
    }

    /**
     * Hosts a parallel stream of [url] (already routed) may use: [url] first, then
     * the same file on other hosts — issued URLs of the same path (UPOS or Akamai)
     * and, for UPOS, pool mirrors — best history first. The second value is how
     * many of them share fresh pieces: the primary, plus (auto mode only) up to two
     * hosts whose history is at least 80% of the primary's. The rest are fallbacks
     * for failed or stalled pieces.
     */
    fun parallelHosts(url: String, max: Int = 4): Pair<List<String>, Int> {
        val primary = CdnHosts.hostOf(url)
        val path = pathOf(url) ?: return listOf(url) to 1
        val s = session
        val now = System.currentTimeMillis()
        val failing = s?.errors?.filterValues { synchronized(it) { it.count { t -> now - t < 30_000 } } >= 2 }?.keys.orEmpty()
        val alternates = LinkedHashMap<String, String>()
        s?.let { it.videoUrls + it.audioUrls }.orEmpty().forEach { u ->
            val h = CdnHosts.hostOf(u)
            val kind = CdnHosts.classify(u)
            if (h != primary && h !in failing && pathOf(u) == path && (kind == CdnHosts.Kind.Upos || kind == CdnHosts.Kind.Akamai)) {
                alternates.putIfAbsent(h, u)
            }
        }
        if (CdnHosts.classify(url) == CdnHosts.Kind.Upos) {
            CdnHosts.POOL.forEach { h ->
                if (h.host != primary && h.host !in failing) CdnHosts.swap(url, h.host)?.let { alternates.putIfAbsent(h.host, it) }
            }
        }
        val ranked = alternates.entries.sortedByDescending { history(it.key) ?: -1.0 }
        val primaryHistory = history(primary)
        val sharing = if (CdnSettings.mode.value == "auto" && primaryHistory != null && primaryHistory > 0) {
            ranked.take(2).takeWhile { (history(it.key) ?: 0.0) >= primaryHistory * 0.8 }.size
        } else {
            0
        }
        val list = listOf(url) + ranked.take(max - 1).map { it.value }
        return list to (1 + sharing).coerceAtMost(list.size)
    }

    /** A single-connection speed measured outside a race (parallel pieces). */
    fun recordSample(host: String, bps: Long) {
        if (bps > 0) remember(host, bps)
    }

    /**
     * Base + backup URLs reordered so a proper CDN URL is tried before P2P /
     * PCDN / MCDN ones (Bilibili often issues a good backup). Use for the
     * player's URL list before picking one.
     */
    fun orderUrls(urls: List<String>): List<String> {
        if (CdnSettings.mode.value == "native" && CdnSettings.mcdn.value == "keep" && !CdnSettings.avoidPcdn.value) return urls
        fun rank(u: String) = when (CdnHosts.classify(u)) {
            CdnHosts.Kind.Upos -> 0
            CdnHosts.Kind.Akamai -> 1
            CdnHosts.Kind.Other -> 2
            CdnHosts.Kind.Mcdn -> 3
            CdnHosts.Kind.Pcdn -> 4
        }
        return urls.sortedBy(::rank)
    }

    // ── Routing (called on loader threads) ──────────────────────────────────

    /** The URL a request should actually go to. */
    fun route(url: String): String {
        val mode = CdnSettings.mode.value
        val kind = CdnHosts.classify(url)
        // Fixed rules first.
        if (kind == CdnHosts.Kind.Mcdn) {
            return when (CdnSettings.mcdn.value) {
                "proxy" -> CdnHosts.proxyMcdn(url)
                else -> url
            }
        }
        if (kind == CdnHosts.Kind.Pcdn && CdnSettings.avoidPcdn.value) {
            val target = if (mode == "fixed") CdnSettings.fixedHost.value else (session?.activeHost ?: CdnHosts.DEFAULT_TARGET)
            return CdnHosts.swap(url, target) ?: url
        }
        return when (mode) {
            "fixed" -> CdnHosts.swap(url, CdnSettings.fixedHost.value) ?: url
            "auto" -> {
                val s = session ?: return url
                val target = s.activeHost ?: return url
                when {
                    CdnHosts.hostOf(url) == target -> url
                    // Akamai only accepts its own signed URL: use the issued one for these bytes.
                    target.endsWith(".akamaized.net") -> issuedOn(s, url, target) ?: url
                    kind == CdnHosts.Kind.Akamai -> siblingSwap(url, target) ?: url
                    else -> CdnHosts.swap(url, target) ?: url
                }
            }
            else -> url
        }
    }

    /** Akamai URL → the same representation's UPOS URL on [target]. */
    private fun siblingSwap(url: String, target: String): String? {
        val s = session ?: return null
        val group = if (url in s.videoUrls) s.videoUrls else if (url in s.audioUrls) s.audioUrls else return null
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull() ?: return null
        val sibling = group.firstOrNull {
            CdnHosts.classify(it) == CdnHosts.Kind.Upos && runCatching { android.net.Uri.parse(it).path }.getOrNull() == path
        } ?: return null
        return CdnHosts.swap(sibling, target)
    }

    /** The issued URL on [host] for the same representation (same path) as [url], if Bilibili issued one. */
    private fun issuedOn(s: Session, url: String, host: String): String? {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull() ?: return null
        return (s.videoUrls + s.audioUrls).firstOrNull {
            CdnHosts.hostOf(it) == host && runCatching { android.net.Uri.parse(it).path }.getOrNull() == path
        }
    }

    fun isVideoUrl(original: String): Boolean = session?.videoUrls?.contains(original) == true

    /** Generation the reading source compares against to know it must end its read. */
    fun abortGeneration(): Int = session?.abortGeneration ?: 0

    // ── Measurement → decisions ───────────────────────────────────────────

    /**
     * Called by [CdnDataSource] while reading a VIDEO stream: [bytes] over
     * [elapsedMs] of transfer on [host], from byte [position] of [original].
     */
    fun onVideoProgress(original: String, host: String, position: Long, bytes: Long, elapsedMs: Long) {
        val s = session ?: return
        if (elapsedMs <= 0) return
        val bps = bytes * 8_000 / elapsedMs
        _status.value = _status.value.copy(host = host, goodputBps = bps, switched = s.activeHost != null, switches = s.switches)
        if (CdnSettings.mode.value != "auto" || !CdnSettings.autoSwitch.value) return
        val judged = elapsedMs >= 8_000 || bytes >= 4L * 1024 * 1024
        if (!judged || s.requiredBps <= 0) return
        val short = bps < s.requiredBps * 6 / 5
        if (short && s.bufferedAheadMs() < 30_000) maybeRace(s, original, host, position, bps, "线路速度不足")
    }

    fun onError(original: String, host: String) {
        val s = session ?: return
        val now = System.currentTimeMillis()
        val list = s.errors.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            list.add(now)
            list.removeAll { now - it > 30_000 }
        }
        remember(host, 0, failed = true)
        // A line we switched to that fails: go back to what Bilibili issued and stop
        // switching for this video. Playback must never be worse than without us.
        if (s.activeHost != null && host == s.activeHost) {
            synchronized(s) {
                s.activeHost = null
                s.switches = 4
                s.abortGeneration++
            }
            android.util.Log.i("PiliCdn", "switched line $host failed, back to native")
            _status.value = _status.value.copy(switched = false, note = "已切回原生线路")
            return
        }
        if (CdnSettings.mode.value == "auto" && CdnSettings.autoSwitch.value && synchronized(list) { list.size } >= 2) {
            maybeRace(s, original, host, 0, 0, "线路连续出错")
        }
    }

    /** 测试其他线路: one race now, saves nothing. */
    fun testNow(original: String?) {
        val s = session ?: return
        val url = original ?: s.videoUrls.firstOrNull() ?: return
        val host = _status.value.host.ifEmpty { CdnHosts.hostOf(route(url)) }
        maybeRace(s, url, host, 0, _status.value.goodputBps, "手动测试", manual = true)
    }

    private fun maybeRace(s: Session, original: String, current: String, position: Long, currentBps: Long, why: String, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(s) {
            if (s.racing) return
            if (!manual && (now < s.nextRaceAt || s.switches >= 4)) return
            s.racing = true
        }
        _status.value = _status.value.copy(racing = true, note = why)
        scope.launch {
            val sourceUrl = route(original)
            val challengers = pickChallengers(s, current)
            val range = position.coerceAtLeast(0) until position + RACE_BYTES
            val contenders = if (manual || currentBps <= 0) challengers + current else challengers
            val results = contenders.map { host ->
                async { host to timeRange(swapFor(sourceUrl, original, host, s) ?: return@async host to null, range) }
            }.awaitAll()
            results.forEach { (host, ms) -> if (ms != null) remember(host, RACE_BYTES * 8_000 / max(ms, 1)) }
            val best = results.filter { it.second != null && it.first != current }.minByOrNull { it.second!! }
            val currentMs = results.firstOrNull { it.first == current }?.second
                ?: if (currentBps > 0) RACE_BYTES * 8_000 / currentBps else Long.MAX_VALUE
            val switchNow = best != null && best.second!! * 3 <= currentMs * 2
            synchronized(s) {
                s.racing = false
                val wait = 10_000L shl min(s.switches, 3)
                s.nextRaceAt = System.currentTimeMillis() + if (switchNow) wait else min(wait * 2, 120_000)
                if (switchNow) {
                    s.lostAt[current] = System.currentTimeMillis()
                    s.activeHost = best!!.first
                    s.switches++
                    s.lastSwitchAt = System.currentTimeMillis()
                    s.abortGeneration++
                }
            }
            if (session !== s) return@launch
            android.util.Log.i("PiliCdn", "race why=$why current=$current results=$results switch=$switchNow")
            val note = when {
                switchNow -> {
                    val (region, vendor) = CdnHosts.describe(best!!.first)
                    "已切换到$region$vendor"
                }
                best == null -> "其他线路不可用"
                else -> "已比较 ${results.size} 条线路，当前线路最快"
            }
            _status.value = _status.value.copy(
                racing = false,
                note = note,
                switched = s.activeHost != null,
                switches = s.switches,
                host = s.activeHost ?: _status.value.host,
            )
        }
    }

    private fun swapFor(sourceUrl: String, original: String, host: String, s: Session): String? {
        if (CdnHosts.hostOf(sourceUrl) == host) return sourceUrl
        if (CdnHosts.classify(sourceUrl) == CdnHosts.Kind.Akamai) return siblingSwap(original, host)
        // Issued Akamai host: only reachable through its issued URL.
        if (host.endsWith(".akamaized.net")) {
            return (s.videoUrls + s.audioUrls).firstOrNull { CdnHosts.hostOf(it) == host }
                ?.takeIf { runCatching { android.net.Uri.parse(it).path }.getOrNull() == runCatching { android.net.Uri.parse(original).path }.getOrNull() }
        }
        return CdnHosts.swap(sourceUrl, host)
    }

    /** Two challengers: the other issued host first, then the pool by history; ties go to mainland. */
    private fun pickChallengers(s: Session, current: String): List<String> {
        val now = System.currentTimeMillis()
        val resting = s.lostAt.filterValues { now - it < 60_000 }.keys
        val failing = s.errors.filterValues { synchronized(it) { it.count { t -> now - t < 30_000 } } >= 2 }.keys
        val candidates = (s.issuedHosts.filter { CdnHosts.classify("https://$it/") != CdnHosts.Kind.Pcdn } + CdnHosts.POOL.map { it.host })
            .distinct()
            .filter { it != current && it !in resting && it !in failing }
        val issuedOther = s.issuedHosts.firstOrNull { it in candidates }
        val measured = candidates.mapNotNull { h -> history(h)?.let { h to it } }
        val median = measured.map { it.second }.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size - 1) / 2] }
        val ranked = candidates.sortedWith(
            compareByDescending<String> { history(it) ?: median }
                .thenBy { CdnHosts.find(it)?.overseas ?: true },
        )
        val picks = mutableListOf<String>()
        if (issuedOther != null) picks += issuedOther
        // One challenger in five is random so history keeps learning.
        val rnd = if ((now / 1000) % 5 == 0L && ranked.size > 2) ranked.drop(1).random() else null
        for (h in listOfNotNull(rnd) + ranked) {
            if (picks.size >= 2) break
            if (h !in picks) picks += h
        }
        return picks
    }

    /** Milliseconds to fetch [range] of [url]; null on failure. */
    private suspend fun timeRange(url: String, range: LongRange): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) { withTimeoutOrNull(6_000) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=${range.first}-${range.last}")
                .apply { BiliClient.mediaHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            val start = System.nanoTime()
            raceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val src = resp.body.source()
                var total = 0L
                val buf = okio.Buffer()
                while (total < RACE_BYTES) {
                    val n = src.read(buf, 64 * 1024)
                    if (n < 0) break
                    total += n
                    buf.clear()
                }
                if (total < 64 * 1024) return@runCatching null
                (System.nanoTime() - start) / 1_000_000
            }
        }.getOrNull()
    } }

    // ── Manual speed test (settings page) ───────────────────────────────────

    data class HostSpeed(val host: String, val mbps: Double?)

    /**
     * Measures [issued] URLs as issued plus every pool host (a UPOS issued URL
     * with its host swapped): 2 MB from a random-ish offset, sequential so they
     * don't share bandwidth. Results go into history only; nothing is selected.
     */
    suspend fun speedTest(issued: List<String>, onEach: (HostSpeed) -> Unit) {
        val upos = issued.firstOrNull { CdnHosts.classify(it) == CdnHosts.Kind.Upos } ?: return
        val targets = LinkedHashMap<String, String?>()
        issued.forEach { u -> targets.putIfAbsent(CdnHosts.hostOf(u), u) }
        CdnHosts.POOL.forEach { h -> targets.putIfAbsent(h.host, CdnHosts.swap(upos, h.host)) }
        val start = 1_048_576L * (1 + (System.currentTimeMillis() / 1000 % 4))
        for ((h, url) in targets) {
            val ms = url?.let { timeBytes(it, start, 2L * 1024 * 1024) }
            val mbps = ms?.let { 2.0 * 1024 * 1024 * 8 / 1000 / it }
            if (mbps != null) remember(h, (mbps * 1_000_000).toLong())
            onEach(HostSpeed(h, mbps))
        }
    }

    private suspend fun timeBytes(url: String, from: Long, length: Long): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) { withTimeoutOrNull(12_000) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=$from-${from + length - 1}")
                .apply { BiliClient.mediaHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            val t0 = System.nanoTime()
            BiliClient.http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val n = resp.body.bytes().size
                if (n < length / 2) return@runCatching null
                (System.nanoTime() - t0) / 1_000_000
            }
        }.getOrNull()
    } }

    // ── History (per host, 3-day half-life) ─────────────────────────────────

    private fun history(host: String): Double? {
        if (!::prefs.isInitialized) return null
        val raw = prefs.getString(host, null) ?: return null
        val parts = raw.split('|')
        val bps = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val failedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0
        val age = (System.currentTimeMillis() - at).coerceAtLeast(0)
        val penalty = if (System.currentTimeMillis() - failedAt < 3_600_000) 0.25 else 1.0
        return bps * exp(-age / (3.0 * 86_400_000 / Math.log(2.0))) * penalty
    }

    private fun remember(host: String, bps: Long, failed: Boolean = false) {
        if (!::prefs.isInitialized || host.isEmpty()) return
        val old = prefs.getString(host, null)?.split('|')
        val oldBps = old?.getOrNull(0)?.toDoubleOrNull()
        val now = System.currentTimeMillis()
        val newBps = when {
            failed -> oldBps ?: 0.0
            oldBps == null -> bps.toDouble()
            else -> oldBps * 0.6 + bps * 0.4
        }
        val failedAt = if (failed) now else old?.getOrNull(2)?.toLongOrNull() ?: 0
        prefs.edit().putString(host, "$newBps|$now|$failedAt").apply()
    }

    fun historyMbps(host: String): Double? = history(host)?.let { it / 1_000_000 }

    private const val RACE_BYTES = 768L * 1024
}
