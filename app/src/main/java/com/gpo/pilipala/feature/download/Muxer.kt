package com.gpo.pilipala.feature.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/**
 * DASH video + audio (fragmented MP4 `.m4s`) into one plain MP4 with the platform's
 * MediaExtractor / MediaMuxer: samples are copied as they are (no re-encode), interleaved
 * by timestamp. Writes to a temp file and renames, so a half-written MP4 never appears.
 */
internal object Muxer {
    private const val MIN_BUFFER = 4 * 1024 * 1024

    private class Source(val extractor: MediaExtractor, val track: Int) {
        var done = false
    }

    suspend fun mux(video: File, audio: File?, out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.delete()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val m = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val sources = mutableListOf<Source>()
            var bufferSize = MIN_BUFFER
            for ((file, prefix) in listOfNotNull(video to "video/", audio?.let { it to "audio/" })) {
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(file.path)
                val index = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
                } ?: error("没有找到${if (prefix == "video/") "视频" else "音频"}轨道")
                ex.selectTrack(index)
                val format = ex.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                sources += Source(ex, m.addTrack(format))
            }
            m.start()
            started = true
            var buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var n = 0
            while (true) {
                if (++n % 256 == 0) currentCoroutineContext().ensureActive()
                val next = sources.filter { !it.done }.minByOrNull { it.extractor.sampleTime } ?: break
                val ex = next.extractor
                if (ex.sampleTime < 0) {
                    next.done = true
                    continue
                }
                // API 26/27 have no getSampleSize; use the track's advertised input size there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val size = ex.sampleSize
                    require(size <= Int.MAX_VALUE - 1024L) { "媒体采样过大" }
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt() + 1024)
                }
                buffer.clear()
                val read = ex.readSampleData(buffer, 0)
                if (read < 0) {
                    next.done = true
                    continue
                }
                val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, read, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(next.track, buffer, info)
                if (!ex.advance()) next.done = true
            }
            m.stop()
            started = false
            m.release()
            muxer = null
            if (!tmp.renameTo(out)) error("写入失败")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            extractors.forEach { runCatching { it.release() } }
            muxer?.let { mx ->
                if (started) runCatching { mx.stop() }
                runCatching { mx.release() }
            }
            tmp.delete()
        }
    }
}
