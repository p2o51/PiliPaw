package com.gpo.pilipala.feature.player.cdn

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.gpo.pilipala.core.net.BiliClient
import java.io.IOException

/**
 * 多线程加载. Sits under [CdnDataSource] (so it receives the already-routed URL):
 * a DASH segment file (video or audio m4s) is fetched as parallel Range requests by
 * [ParallelRangeEngine]; anything else, or when [ParallelLoad.wanted] says no, goes
 * to [upstream] untouched.
 *
 * Playback must never be worse than without it: a first request that fails, a
 * server that ignores Range (200) or an unknown total length all fall back to the
 * plain upstream for that open.
 *
 * Auto mode can engage (or the setting can change) while a plain read is running:
 * the read then ends with [CdnSwitchException] and ExoPlayer reopens at once at the
 * same position, now in the other mode.
 */
class ParallelRangeDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(upstream.createDataSource())
    }

    private companion object {
        const val RecheckMs = 2_000L
        /** Hosts that answered a Range request with 200 this process: never split again. */
        val noRangeHosts: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    }

    /** Download goodput of a parallel stream: (bytes, busy ms), windowed like [CdnDataSource]. */
    @Volatile var goodputSink: ((Long, Long) -> Unit)? = null

    /** The current open uses parallel ranges. */
    @Volatile var isParallel = false
        private set

    private val listeners = mutableListOf<TransferListener>()
    private var engine: ParallelRangeEngine? = null
    private var spec: DataSpec? = null
    private var url = ""
    private var headers: Map<String, List<String>> = emptyMap()
    /** Plain because parallel wasn't wanted (may switch up later), not because it failed. */
    private var plainByChoice = false
    private var lastCheckAt = 0L
    private val samples = ArrayDeque<LongArray>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        url = dataSpec.uri.toString()
        isParallel = false
        plainByChoice = false
        lastCheckAt = System.currentTimeMillis()
        synchronized(samples) { samples.clear() }
        val splittable = splittable(dataSpec)
        if (splittable && ParallelLoad.wanted()) {
            openParallel(dataSpec)?.let { return it }
            return openPlain(dataSpec)
        }
        plainByChoice = splittable
        return openPlain(dataSpec)
    }

    private fun splittable(dataSpec: DataSpec): Boolean =
        dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
            ParallelLoad.eligible(url) && CdnHosts.hostOf(url) !in noRangeHosts &&
            (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length >= 2L * ParallelLoad.ChunkBytes)

    private fun openPlain(dataSpec: DataSpec): Long {
        val t0 = System.nanoTime()
        val n = upstream.open(dataSpec)
        ParallelLoad.recordTtfb((System.nanoTime() - t0) / 1_000_000)
        return n
    }

    /** Opens in parallel mode; null = fall back to plain. */
    private fun openParallel(dataSpec: DataSpec): Long? {
        val client = ParallelLoad.client
        val reqHeaders = BiliClient.mediaHeaders() + dataSpec.httpRequestHeaders
        val pos = dataSpec.position
        val lengthKnown = dataSpec.length != C.LENGTH_UNSET.toLong()
        val chunk = ParallelLoad.ChunkBytes
        val firstAsk = if (lengthKnown) minOf(pos + dataSpec.length - 1, pos + chunk - 1) else pos + chunk - 1
        val (hosts, spread) = CdnRouter.parallelHosts(url)
        val audio = CdnRouter.isAudioUrl(url)

        val call = client.newCall(ParallelRangeEngine.request(url, reqHeaders, pos, firstAsk))
        val t0 = System.nanoTime()
        val resp = try {
            call.execute()
        } catch (e: IOException) {
            return null
        }
        ParallelLoad.recordTtfb((System.nanoTime() - t0) / 1_000_000)
        fun fallback(): Long? {
            resp.close()
            return null
        }
        if (resp.code == 200) {
            noRangeHosts += CdnHosts.hostOf(url)
            return fallback()
        }
        if (resp.code != 206) return fallback()
        val cr = ParallelRangeEngine.parseContentRange(resp.header("Content-Range")) ?: return fallback()
        if (cr[0] != pos) return fallback()
        val total = cr[2]
        val end = when {
            lengthKnown -> pos + dataSpec.length - 1
            total >= 0 -> total - 1
            else -> return fallback()
        }
        if (total >= 0 && end > total - 1) return fallback()
        if (cr[1] != minOf(firstAsk, end)) return fallback()

        headers = resp.headers.toMultimap()
        engine = ParallelRangeEngine(
            client = client,
            executor = ParallelLoad.executor,
            headers = reqHeaders,
            hosts = hosts,
            spread = spread,
            start = pos,
            end = end,
            chunkSize = chunk,
            threads = { ParallelLoad.threadsFor(audio) },
            events = object : ParallelRangeEngine.Events {
                override fun onPiece(url: String, bytes: Long, ms: Long) = ParallelLoad.onPiece(CdnHosts.hostOf(url), bytes, ms)
                override fun onRetry(url: String, error: IOException) {
                    android.util.Log.i("PiliRange", "piece retry on ${CdnHosts.hostOf(url)}: ${error.message}")
                    ParallelLoad.onRetry()
                }
                override fun onTransfer(bytes: Long, busyNanos: Long) = report(bytes, busyNanos)
            },
            first = resp,
            firstCall = call,
            firstEnd = cr[1],
        )
        isParallel = true
        transferInitializing(dataSpec)
        listeners.forEach { it.onTransferStart(this, dataSpec, true) }
        ParallelLoad.onOpened(hosts.size)
        return end - pos + 1
    }

    private fun transferInitializing(dataSpec: DataSpec) {
        listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt >= RecheckMs) {
            lastCheckAt = now
            // Setting turned off mid-stream, or auto engaged while reading plain: reopen in the other mode.
            if (isParallel && CdnSettings.parallel.value == "off") throw CdnSwitchException()
            if (!isParallel && plainByChoice && ParallelLoad.wanted()) throw CdnSwitchException()
        }
        val e = engine ?: return upstream.read(buffer, offset, length)
        val n = e.read(buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        val s = spec
        if (s != null) listeners.forEach { it.onBytesTransferred(this, s, true, n) }
        return n
    }

    /** Keeps the most recent ~8 s / 4 MB of busy download, like [CdnDataSource]. */
    private fun report(bytes: Long, busyNanos: Long) {
        val sink = goodputSink ?: return
        val (b, t) = synchronized(samples) {
            samples.addLast(longArrayOf(bytes, busyNanos))
            var b = 0L
            var t = 0L
            val keep = ArrayDeque<LongArray>()
            for (sample in samples.reversed()) {
                if (t >= 8_000_000_000L || b >= 4L * 1024 * 1024) break
                keep.addFirst(sample)
                b += sample[0]
                t += sample[1]
            }
            samples.clear()
            samples.addAll(keep)
            b to t
        }
        if (t >= 300_000_000L && b >= 512L * 1024) sink(b, t / 1_000_000)
    }

    override fun getUri(): Uri? = if (isParallel) spec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (isParallel) headers else upstream.responseHeaders

    override fun close() {
        val e = engine
        engine = null
        if (e != null) {
            e.close()
            isParallel = false
            val s = spec
            if (s != null) listeners.forEach { it.onTransferEnd(this, s, true) }
        } else {
            upstream.close()
        }
    }
}
