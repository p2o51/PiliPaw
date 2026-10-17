package com.gpo.pilipala.feature.player.cdn

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Thrown to end a read so ExoPlayer retries on the line the router just switched to. */
class CdnSwitchException : IOException("CDN line switched")

/**
 * Wraps the media DataSource: every open goes through [CdnRouter.route], video
 * reads are measured for the router, and a router switch ends the current read
 * (ExoPlayer retries at once from the same position, now on the new line).
 */
class CdnDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CdnDataSource(upstream.createDataSource())
    }

    private var original: String = ""
    private var host: String = ""
    private var isVideo = false
    private var generation = 0
    private var openedAt = 0L
    private var position = 0L
    private var bytes = 0L
    private var lastReportAt = 0L
    // Goodput counts only time spent waiting inside read(): ExoPlayer stops
    // reading when its buffer is full, and that idle time is not the line's fault.
    private var activeNanos = 0L
    private var windowBytes = 0L
    private val samples = ArrayDeque<LongArray>() // [bytes, activeNanos] per report

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec.uri.toString()
        val routed = CdnRouter.route(original)
        host = CdnHosts.hostOf(routed)
        isVideo = CdnRouter.isVideoUrl(original)
        generation = CdnRouter.abortGeneration()
        position = dataSpec.position
        bytes = 0
        activeNanos = 0
        windowBytes = 0
        samples.clear()
        openedAt = System.currentTimeMillis()
        lastReportAt = openedAt
        val spec = if (routed == original) dataSpec else dataSpec.buildUpon().setUri(Uri.parse(routed)).build()
        return try {
            upstream.open(spec)
        } catch (e: IOException) {
            CdnRouter.onError(original, host)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isVideo && CdnRouter.abortGeneration() != generation) {
            val next = CdnHosts.hostOf(CdnRouter.route(original))
            generation = CdnRouter.abortGeneration()
            if (next != host) throw CdnSwitchException()
        }
        val t0 = System.nanoTime()
        val n = try {
            upstream.read(buffer, offset, length)
        } catch (e: IOException) {
            CdnRouter.onError(original, host)
            throw e
        }
        if (n > 0) {
            bytes += n
            if (isVideo) {
                activeNanos += System.nanoTime() - t0
                windowBytes += n
                val now = System.currentTimeMillis()
                if (now - lastReportAt >= 1_000) {
                    lastReportAt = now
                    samples.addLast(longArrayOf(windowBytes, activeNanos))
                    windowBytes = 0
                    activeNanos = 0
                    // Keep the most recent ~8 s of transfer or 4 MB.
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
                    if (t > 0) CdnRouter.onVideoProgress(original, host, position + bytes, b, t / 1_000_000)
                }
            }
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
