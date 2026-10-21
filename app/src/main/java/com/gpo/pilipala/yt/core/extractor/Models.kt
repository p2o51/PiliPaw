package com.gpo.pilipala.yt.core.extractor

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.yt.core.util.Fmt
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * What the UI sees of a video: plain, immutable, extractor-free. Numbers below 0 are unknown
 * (the extractor's -1) and draw as nothing, never as "0".
 */
@Immutable
data class VideoItem(
    val url: String,
    val title: String,
    val thumbnail: String,
    val channelName: String,
    val channelUrl: String,
    val channelAvatar: String,
    val durationSec: Long,
    val views: Long,
    /** Relative upload time ("3天前"), dated by us from [uploadedAt]; empty when unknown. */
    val uploaded: String,
    /** Epoch millis when the extractor knows the date (parsed or from RSS), else 0. Sorts the 订阅 feed. */
    val uploadedAt: Long,
    val isLive: Boolean,
    val isShort: Boolean,
    val channelVerified: Boolean,
)

@Immutable
@Serializable
data class ChannelItem(
    val url: String,
    val name: String,
    val avatar: String,
    val subscribers: Long,
    val videos: Long,
    val verified: Boolean,
)

/** A search or list row: a video or a channel. Playlists come later. */
@Immutable
sealed interface ListItem {
    val key: String

    data class Video(val video: VideoItem) : ListItem { override val key get() = "v:" + video.url }
    data class Channel(val channel: ChannelItem) : ListItem { override val key get() = "c:" + channel.url }
}

fun StreamInfoItem.toVideo(): VideoItem {
    val at = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
    return VideoItem(
        url = url,
        title = name.orEmpty(),
        thumbnail = thumbnailUrl.orEmpty(),
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl.orEmpty(),
        channelAvatar = uploaderAvatarUrl.orEmpty(),
        durationSec = duration,
        views = viewCount,
        uploaded = Fmt.ago(at),
        uploadedAt = at,
        isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = isShortFormContent,
        channelVerified = isUploaderVerified,
    )
}

fun ChannelInfoItem.toChannel(): ChannelItem = ChannelItem(
    url = url,
    name = name.orEmpty(),
    avatar = thumbnailUrl.orEmpty(),
    subscribers = subscriberCount,
    videos = streamCount,
    verified = isVerified,
)

fun InfoItem.toListItem(): ListItem? = when (this) {
    is StreamInfoItem -> ListItem.Video(toVideo())
    is ChannelInfoItem -> ListItem.Channel(toChannel())
    else -> null
}
