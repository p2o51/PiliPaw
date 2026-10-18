package com.gpo.pilipala.feature.player.cdn

import com.gpo.pilipala.core.net.BiliClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多线程加载: when and how DASH segments are fetched as parallel Range requests
 * ([ParallelRangeEngine] via [ParallelRangeDataSource]).
 *
 * Why it helps overseas: one TCP connection's throughput is capped by
 * window / RTT, and AU → HK/CN routes have 120–300 ms RTT plus loss. N
 * connections each get their own window. The client is pinned to HTTP/1.1 —
 * the UPOS hosts speak h2, and h2 would multiplex every range onto ONE TCP
 * connection, which is exactly the limit this works around.
 *
 * Auto mode turns on per video when the measured line goodput is below 1.5×
 * the stream's bitrate, or the measured request latency is high; once on it
 * stays on for that video (parallel goodput would otherwise turn it back off).
 */
object ParallelLoad {
    /** Bytes per piece. */
    const val ChunkBytes = 1024 * 1024
    /** Latency (min of recent time-to-first-byte) from which auto turns on. */
    const val HighRttMs = 160L
    val threadChoices = listOf(4, 6, 8, 12, 16)

    data class Status(
        /** A parallel stream was opened during this video. */
        val active: Boolean = false,
        val threads: Int = 0,
        /** Why it is on: "已开启" / "延迟高" / "速度不足". */
        val reason: String = "",
        val hosts: Int = 0,
        val retries: Int = 0,
        val rttMs: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Router session this video's auto decision belongs to; -1 = auto not engaged. */
    @Volatile private var engagedSession = -1L
    @Volatile private var engagedReason = ""
    private val ttfb = ArrayDeque<Long>()
    private val sampleLock = Any()
    private val pending = HashMap<String, LongArray>() // host -> [bytes, ms]
    private var lastFlushAt = 0L

    val client: OkHttpClient by lazy {
        BiliClient.http.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 2, TimeUnit.MINUTES))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val executor: ExecutorService by lazy {
        val n = AtomicInteger()
        Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "pili-range-${n.incrementAndGet()}").apply { isDaemon = true } })
    }

    fun threads(): Int = CdnSettings.parallelThreads.value.coerceIn(1, 32)

    /** Audio is a few hundred kbps: a couple of connections are plenty. */
    fun threadsFor(audio: Boolean): Int = if (audio) (threads() / 4).coerceIn(2, 4) else threads()

    /** Is this (routed) URL a DASH segment file we may split? */
    fun eligible(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (u.scheme != "https" && u.scheme != "http") return false
        val path = u.rawPath ?: return false
        return path.endsWith(".m4s", ignoreCase = true)
    }

    /** Should a stream opened now use parallel ranges? Also engages auto mode. */
    fun wanted(): Boolean = when (CdnSettings.parallel.value) {
        "on" -> {
            engagedReason = "已开启"
            true
        }
        "off" -> false
        else -> autoWanted()
    }

    private fun autoWanted(): Boolean {
        val session = CdnRouter.sessionId()
        if (session < 0) return false
        if (engagedSession == session) return true
        val st = CdnRouter.status.value
        val reason = when {
            st.goodputBps > 0 && st.requiredBps > 0 && st.goodputBps < st.requiredBps * 3 / 2 -> "速度不足"
            rttMs() >= HighRttMs -> "延迟高"
            else -> return false
        }
        engagedSession = session
        engagedReason = reason
        return true
    }

    /** Time to first byte of a media request, ms. */
    fun recordTtfb(ms: Long) {
        if (ms <= 0) return
        synchronized(ttfb) {
            ttfb.addLast(ms)
            while (ttfb.size > 8) ttfb.removeFirst()
        }
    }

    /** Min of recent first-byte times (robust to handshakes and server hiccups); 0 = unknown. */
    fun rttMs(): Long = synchronized(ttfb) { ttfb.minOrNull() ?: 0L }

    fun onOpened(hosts: Int) {
        _status.value = Status(
            active = true,
            threads = threads(),
            reason = engagedReason.ifEmpty { "已开启" },
            hosts = hosts,
            retries = _status.value.retries,
            rttMs = rttMs(),
        )
    }

    fun onRetry() {
        _status.value = _status.value.copy(retries = _status.value.retries + 1)
    }

    /** A piece's single-connection speed: fed into the router's per-host history, batched. */
    fun onPiece(host: String, bytes: Long, ms: Long) {
        if (bytes < 256 * 1024 || ms <= 0 || host.isEmpty()) return
        val flush = synchronized(sampleLock) {
            val acc = pending.getOrPut(host) { LongArray(2) }
            acc[0] += bytes
            acc[1] += ms
            val now = System.currentTimeMillis()
            if (now - lastFlushAt < 15_000) return
            lastFlushAt = now
            pending.toMap().also { pending.clear() }
        }
        flush.forEach { (h, a) -> if (a[1] > 0) CdnRouter.recordSample(h, a[0] * 8_000 / a[1]) }
    }

    /** New video (or none): auto decides again, status clears. */
    fun reset() {
        engagedSession = -1L
        engagedReason = ""
        synchronized(sampleLock) { pending.clear() }
        _status.value = Status()
    }
}
