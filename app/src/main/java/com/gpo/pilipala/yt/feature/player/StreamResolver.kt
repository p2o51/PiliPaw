package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/**
 * StreamInfo → one Media3 [MediaSource], the way PipePipeClient's PlaybackResolver does it for
 * YouTube: a live stream plays its HLS (else DASH) manifest; a video plays its best video-only
 * stream merged with its best audio stream, each wrapped in a generated single-stream DASH
 * manifest so ExoPlayer seeks by index instead of re-downloading. Muxed progressive is the
 * fallback. SABR (signed-in mweb) is not here yet: see docs/PLAN.md, 播放内核.
 */
object StreamResolver {

    class Unsupported(message: String) : Exception(message)

    data class Resolved(val source: MediaSource, val video: VideoStream?, val audio: AudioStream?)

    fun resolve(info: StreamInfo, upstream: DataSource.Factory, preferredHeight: Int): Resolved {
        val factory = YoutubeDataSource.forVideo(upstream)
        val item = MediaItem.Builder()
            .setUri(info.url)
            .setMediaId(info.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.name)
                    .setArtist(info.uploaderName)
                    .setArtworkUri(info.thumbnailUrl?.let(Uri::parse))
                    .build(),
            )
            .build()

        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            val hls = info.hlsUrl.orEmpty()
            if (hls.isNotEmpty()) {
                return Resolved(HlsMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(hls).build()), null, null)
            }
            val dash = info.dashMpdUrl.orEmpty()
            if (dash.isNotEmpty()) {
                return Resolved(DashMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(dash).build()), null, null)
            }
            throw Unsupported("直播流不可用")
        }

        val video = pickVideo(info.videoOnlyStreams.orEmpty(), preferredHeight)
        val audio = pickAudio(info.audioStreams.orEmpty())
        if (video != null && audio != null) {
            val v = single(video, info, factory, item)
            val a = single(audio, info, factory, item)
            return Resolved(MergingMediaSource(true, v, a), video, audio)
        }
        // Old-style muxed streams (360p): still better than nothing.
        val muxed = info.videoStreams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { it.height }
        if (muxed != null) {
            return Resolved(ProgressiveMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(muxed.content).build()), muxed, null)
        }
        if (audio != null) return Resolved(single(audio, info, factory, item), null, audio)
        throw Unsupported("没有可播放的流")
    }

    /** Highest stream at or under [preferredHeight] (0 = no cap); H.264 first at equal height (every decoder has it). */
    private fun pickVideo(streams: List<VideoStream>, preferredHeight: Int): VideoStream? {
        val playable = streams.filter { it.itagItem != null && it.deliveryMethod.isPlayable() }
        if (playable.isEmpty()) return null
        val capped = if (preferredHeight > 0) playable.filter { it.height <= preferredHeight }.ifEmpty { listOf(playable.minBy { it.height }) } else playable
        return capped.sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenByDescending { it.fps }
                .thenByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 },
        ).first()
    }

    /** The original-language track when there are several, M4A first, then bitrate. */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val playable = streams.filter { it.itagItem != null && it.deliveryMethod.isPlayable() }
        if (playable.isEmpty()) return null
        val tracks = playable.groupBy { it.audioTrackId }
        val original = tracks.entries.firstOrNull { (_, s) -> s.any { it.audioTrackName?.contains("original", ignoreCase = true) == true } }?.value
        val pool = original ?: playable
        return pool.sortedWith(
            compareByDescending<AudioStream> { if (it.format == MediaFormat.M4A) 1 else 0 }
                .thenByDescending { it.averageBitrate },
        ).first()
    }

    private fun DeliveryMethod.isPlayable() = this == DeliveryMethod.PROGRESSIVE_HTTP || this == DeliveryMethod.DASH

    private fun single(stream: Stream, info: StreamInfo, factory: DataSource.Factory, item: MediaItem): MediaSource {
        val itag = stream.itagItem
        val manifest = runCatching {
            when (stream.deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP -> YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag ?: return@runCatching null, info.duration)
                DeliveryMethod.DASH -> YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag ?: return@runCatching null, info.duration)
                else -> null
            }
        }.getOrNull()
        if (manifest != null) {
            val parsed = DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(manifest.toByteArray()))
            return DashMediaSource.Factory(factory).createMediaSource(parsed, item.buildUpon().setUri(stream.content).build())
        }
        return ProgressiveMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(stream.content).build())
    }
}
