package com.gpo.pilipala.feature.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gpo.pilipala.feature.player.PlayInfo
import com.gpo.pilipala.feature.player.QualityOption
import java.io.File

/**
 * A finished cache, as the player sees it: one progressive file (the muxed MP4), or the
 * video and audio streams side by side when muxing was not possible on this device.
 * The player swaps its playurl fetch for [playInfo] and its network sources for [source].
 */
internal class LocalMedia(
    val video: File,
    val audio: File?,
    val qn: Int,
    val label: String,
    val durationMs: Long,
) {
    val videoUri: String get() = Uri.fromFile(video).toString()

    /** A single-quality, non-DASH info: the quality sheet shows just the cached one. */
    fun playInfo(): PlayInfo = PlayInfo(
        quality = qn,
        qualities = listOf(QualityOption(qn, label, true)),
        videos = emptyList(),
        audios = emptyList(),
        durl = emptyList(),
        timelengthMs = durationMs,
        lastPlayTimeMs = 0,
    )

    /** [item] builds the first item (it carries the session metadata: title, cover). */
    fun source(context: Context, item: (String) -> MediaItem): MediaSource {
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        val v = factory.createMediaSource(item(videoUri))
        val a = audio ?: return v
        return MergingMediaSource(false, true, v, factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(a))))
    }
}
