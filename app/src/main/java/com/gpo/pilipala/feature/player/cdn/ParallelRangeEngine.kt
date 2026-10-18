package com.gpo.pilipala.feature.player.cdn

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fetches one byte range of a media file as many parallel HTTP Range requests and
 * hands the bytes to a single reader in order (the idea of Bilibili Thread Ripper's
 * downloader, shaped for ExoPlayer's one-long-read progressive loading).
 *
 * - The range [start]..[end] is cut into [chunkSize] pieces. At most `threads()`
 *   pieces download at once, and at most `threads() + 2` pieces exist at all
 *   (downloading or downloaded-but-unread), so memory stays bounded and a reader
 *   that stops asking (ExoPlayer's buffer is full) stops the downloads too.
 * - The reader gets bytes of the head piece as they arrive, not when it is whole.
 * - A failed or stalled piece resumes from what it already has, on the next host
 *   in [hosts] (all URLs are the same file), up to [MaxAttempts] times.
 * - Every response must be a 206 whose Content-Range matches the asked range.
 *
 * Pure JVM + OkHttp; Android-free so it can be exercised outside the app.
 */
internal class ParallelRangeEngine(
    private val client: OkHttpClient,
    private val executor: ExecutorService,
    private val headers: Map<String, String>,
    /** Same file on several hosts; [0] is the primary. */
    private val hosts: List<String>,
    /** The first [spread] hosts share fresh pieces round-robin; the rest are fallbacks only. */
    private val spread: Int,
    private val start: Long,
    /** Inclusive. */
    private val end: Long,
    private val chunkSize: Int,
    private val threads: () -> Int,
    private val events: Events,
    /** An already-open 206 response for the first piece (`start`..[firstEnd]), or null. */
    first: Response?,
    firstCall: Call?,
    firstEnd: Long,
    firstHost: Int = 0,
) {
    interface Events {
        /** A piece finished on [url]: [bytes] in [ms] on one connection. */
        fun onPiece(url: String, bytes: Long, ms: Long) {}
        /** A piece attempt failed on [url] and will move on (or give up). */
        fun onRetry(url: String, error: IOException) {}
        /** Download throughput while at least one request was busy. */
        fun onTransfer(bytes: Long, busyNanos: Long) {}
    }

    companion object {
        const val MaxAttempts = 4
        /** No new bytes for this long on the piece the reader waits for: restart it elsewhere. */
        const val StallMs = 2_500L
        /** The reader waited this long on a slow head piece while later pieces are done: move it. */
        const val HedgeMs = 1_500L
        private val contentRangeRe = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

        /** start, end, total (-1 = unknown) of a Content-Range header. */
        fun parseContentRange(value: String?): LongArray? {
            val m = contentRangeRe.find(value?.trim() ?: return null) ?: return null
            val s = m.groupValues[1].toLongOrNull() ?: return null
            val e = m.groupValues[2].toLongOrNull() ?: return null
            val t = if (m.groupValues[3] == "*") -1L else m.groupValues[3].toLongOrNull() ?: return null
            if (e < s || (t >= 0 && t <= e)) return null
            return longArrayOf(s, e, t)
        }

        fun request(url: String, headers: Map<String, String>, from: Long, to: Long): Request =
            Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                // No transparent gzip: byte offsets must be the file's.
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$from-$to")
                .build()
    }

    private inner class Piece(val index: Int, val start: Long, val end: Long) {
        val length = (end - start + 1).toInt()
        val data = ByteArray(length)
        @Volatile var filled = 0
        var done = false
        var running = false
        var failed: IOException? = null
        var call: Call? = null
        var attempts = 0
        var hostIndex = -1
        var lastProgressAt = System.nanoTime()
        var hedged = false
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pieces = ArrayDeque<Piece>()
    private var nextStart = start
    private var nextIndex = 0
    private var readOffset = 0
    private var closed = false
    private var running = 0
    private val badHosts = HashSet<Int>()

    // Busy-time throughput accounting (under lock).
    private var busySince = 0L
    private var busyNanos = 0L
    private var bytesSinceReport = 0L
    private var lastReportAt = System.nanoTime()

    init {
        require(end >= start && chunkSize > 0 && hosts.isNotEmpty())
        lock.withLock {
            if (first != null) {
                val p = Piece(nextIndex++, start, firstEnd)
                nextStart = firstEnd + 1
                pieces.addLast(p)
                p.running = true
                p.hostIndex = firstHost
                p.call = firstCall
                markBusy(+1)
                executor.execute { runPiece(p, first) }
            }
            schedule()
        }
    }

    /** Reads like an InputStream; -1 at the end of the range. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        lock.withLock {
            var waitedSince = 0L
            while (true) {
                if (closed) throw InterruptedIOException("closed")
                val head = pieces.firstOrNull() ?: return -1
                val available = head.filled - readOffset
                if (available > 0) {
                    val n = minOf(length, available)
                    System.arraycopy(head.data, readOffset, buffer, offset, n)
                    readOffset += n
                    if (readOffset == head.length) {
                        pieces.removeFirst()
                        readOffset = 0
                        schedule()
                    }
                    return n
                }
                head.failed?.let { throw it }
                val now = System.nanoTime()
                if (waitedSince == 0L) waitedSince = now
                watch(head, now, now - waitedSince)
                try {
                    changed.await(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // ExoPlayer's Loader interrupts a canceled load.
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted")
                }
            }
        }
    }

    fun close() {
        val calls = lock.withLock {
            if (closed) return
            closed = true
            changed.signalAll()
            pieces.mapNotNull { it.call }.also { pieces.clear() }
        }
        calls.forEach { runCatching { it.cancel() } }
    }

    // ── Scheduling (under lock) ─────────────────────────────────────────────

    private fun schedule() {
        if (closed) return
        val limit = threads().coerceIn(1, 32)
        while (pieces.size < limit + 2 && nextStart <= end) {
            val pieceEnd = minOf(end, nextStart + chunkSize - 1)
            pieces.addLast(Piece(nextIndex++, nextStart, pieceEnd))
            nextStart = pieceEnd + 1
        }
        for (p in pieces) {
            if (running >= limit) break
            if (p.running || p.done || p.failed != null) continue
            start(p)
        }
    }

    private fun start(p: Piece) {
        p.running = true
        p.hostIndex = pickHost(p)
        p.lastProgressAt = System.nanoTime()
        markBusy(+1)
        executor.execute { runPiece(p, null) }
    }

    private fun pickHost(p: Piece): Int {
        val n = hosts.size
        if (p.attempts == 0) {
            val share = spread.coerceIn(1, n)
            val pick = p.index % share
            if (pick !in badHosts) return pick
        }
        // A retry moves on from the host that just failed; hosts that failed this stream go last.
        val from = if (p.hostIndex < 0) 0 else p.hostIndex + 1
        val order = (0 until n).map { (from + it) % n }
        return order.firstOrNull { it !in badHosts } ?: order.first()
    }

    private fun markBusy(delta: Int) {
        val now = System.nanoTime()
        if (running > 0) busyNanos += now - busySince
        busySince = now
        running += delta
    }

    /** The reader is waiting on [head]: restart it elsewhere if it stalls or crawls. */
    private fun watch(head: Piece, now: Long, waitedNanos: Long) {
        val call = head.call ?: return
        if (!head.running) return
        val idle = now - head.lastProgressAt
        val stalled = idle > StallMs * 1_000_000
        val laterDone = pieces.drop(1).any { it.done }
        val crawling = !head.hedged && hosts.size > 1 && laterDone && waitedNanos > HedgeMs * 1_000_000
        if (stalled || crawling) {
            if (crawling) head.hedged = true
            head.lastProgressAt = now
            call.cancel() // the worker resumes the tail on the next host
        }
    }

    // ── Workers ─────────────────────────────────────────────────────────────

    private fun runPiece(p: Piece, opened: Response?) {
        var response = opened
        while (true) {
            val hostIndex = lock.withLock { if (closed) return finish(p); p.hostIndex }
            val url = hosts[hostIndex]
            val t0 = System.nanoTime()
            val from0 = p.filled
            try {
                val resp = response ?: run {
                    val call = client.newCall(request(url, headers, p.start + p.filled, p.end))
                    lock.withLock {
                        if (closed) return finish(p)
                        p.call = call
                    }
                    call.execute()
                }
                response = null
                resp.use { r -> receive(p, r) }
                val bytes = (p.length - from0).toLong()
                val ms = (System.nanoTime() - t0) / 1_000_000
                lock.withLock {
                    p.done = true
                    p.running = false
                    p.call = null
                    markBusy(-1)
                    schedule()
                    changed.signalAll()
                }
                events.onPiece(url, bytes, ms)
                return
            } catch (e: IOException) {
                response?.close()
                response = null
                val giveUp = lock.withLock {
                    if (closed) return finish(p)
                    p.call = null
                    p.attempts++
                    // A host that sent nothing at all is unlikely to do better for this stream.
                    if (p.filled == from0 && !p.hedged) badHosts += hostIndex
                    p.attempts >= MaxAttempts
                }
                events.onRetry(url, e)
                if (giveUp) {
                    lock.withLock {
                        p.failed = e
                        p.running = false
                        markBusy(-1)
                        changed.signalAll()
                    }
                    return
                }
                val backoff = 150L * p.attempts
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return finish(p)
                }
                lock.withLock { p.hostIndex = pickHost(p) }
            }
        }
    }

    private fun finish(p: Piece) {
        lock.withLock {
            if (p.running) {
                p.running = false
                markBusy(-1)
            }
            p.call = null
        }
    }

    private fun receive(p: Piece, r: Response) {
        val askedFrom = p.start + p.filled
        if (r.code != 206) throw IOException("HTTP ${r.code}")
        val cr = parseContentRange(r.header("Content-Range")) ?: throw IOException("no Content-Range")
        if (cr[0] != askedFrom || cr[1] != p.end) throw IOException("Content-Range ${cr[0]}-${cr[1]} != $askedFrom-${p.end}")
        val source = r.body.byteStream()
        while (p.filled < p.length) {
            val n = source.read(p.data, p.filled, p.length - p.filled)
            if (n < 0) throw IOException("short body ${p.filled}/${p.length}")
            lock.withLock {
                if (closed) throw InterruptedIOException("closed")
                p.filled += n
                p.lastProgressAt = System.nanoTime()
                account(n.toLong())
                changed.signalAll()
            }
        }
    }

    /** Under lock: add [n] downloaded bytes, report about once a second. */
    private fun account(n: Long) {
        bytesSinceReport += n
        val now = System.nanoTime()
        if (now - lastReportAt < 1_000_000_000L) return
        if (running > 0) {
            busyNanos += now - busySince
            busySince = now
        }
        val b = bytesSinceReport
        val t = busyNanos
        bytesSinceReport = 0
        busyNanos = 0
        lastReportAt = now
        if (b > 0 && t > 0) runCatching { events.onTransfer(b, t) }
    }
}
