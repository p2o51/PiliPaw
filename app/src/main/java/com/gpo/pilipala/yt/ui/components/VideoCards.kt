package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.PiliSymbols

/** Covers in cards: 12dp continuous corners, bare (no border). */
val CardCoverShape = ContinuousRoundedCornerShape(12.dp)

/**
 * The big single-column card (YouTube's shape, PiliPaw 0.4.22's): the thumbnail edge to edge
 * of its column, then avatar + title + one quiet metadata line. Grids use it at every width;
 * the column count comes from [videoGridMinWidth].
 */
@Composable
fun VideoCard(video: VideoItem, modifier: Modifier = Modifier, showChannel: Boolean = true) {
    val nav = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    if (menu) VideoMenuSheet(video, onDismiss = { menu = false })
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardCoverShape)
            .combinedClickable(onLongClick = { menu = true }) { Player.play(video.url, video) }
            .padding(bottom = 12.dp),
    ) {
        Thumbnail(video, Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showChannel && video.channelUrl.isNotEmpty()) {
                Avatar(
                    video.channelAvatar,
                    size = 36.dp,
                    onClick = { nav.push(Route.Channel(video.channelUrl, video.channelName, video.channelAvatar)) },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(video, showChannel)
            }
        }
    }
}

/** A compact row: thumbnail left, text right. Search results, related videos. */
@Composable
fun VideoRow(
    video: VideoItem,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 168.dp,
    /** Watched so far (0..1) as a line along the thumbnail's bottom; null draws none. */
    progress: Float? = null,
    /** Null: the video menu ([VideoMenuSheet]). */
    onLongClick: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    if (menu) VideoMenuSheet(video, onDismiss = { menu = false })
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick ?: { menu = true }) { Player.play(video.url, video) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(video, Modifier.width(thumbWidth), progress)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                video.channelName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(video, showChannel = false)
        }
    }
}

@Composable
fun ChannelRow(channel: ChannelItem, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable { nav.push(Route.Channel(channel.url, channel.name, channel.avatar)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(channel.avatar, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.subscribers >= 0) ValueLabel(Fmt.count(channel.subscribers), "订阅者")
        }
    }
}

/** 16:9 thumbnail; duration (or 直播) in a small block on its corner. */
@Composable
fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier, progress: Float? = null) {
    Box(modifier.aspectRatio(16f / 9f)) {
        NetImage(video.thumbnail, Modifier.matchParentSize(), shape = CardCoverShape, thumbWidth = null)
        if (progress != null && progress > 0.01f) {
            val played = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CardCoverShape)
                    .drawWithContent {
                        val h = 3.dp.toPx()
                        drawRect(Color.White.copy(alpha = 0.3f), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
                        drawRect(played, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width * progress.coerceIn(0f, 1f), h))
                    },
            )
        }
        val badge = if (video.isLive) "直播" else Fmt.duration(video.durationSec)
        if (badge.isNotEmpty()) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(ContinuousRoundedCornerShape(6.dp))
                    .background(if (video.isLive) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Channel, views and date as separate texts with space between, never joined with "·". */
@Composable
private fun MetaLine(video: VideoItem, showChannel: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showChannel && video.channelName.isNotEmpty()) {
            Text(
                video.channelName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (video.views >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(PiliSymbols.Views, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                FameCount(Fmt.count(video.views), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, revealable = false)
            }
        }
        if (video.uploaded.isNotEmpty()) {
            Text(video.uploaded, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A bold value and a quiet label ("12万" "订阅者"). [fame]: a popularity number (名声模式 hides it). */
@Composable
fun ValueLabel(value: String, label: String, modifier: Modifier = Modifier, fame: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        FameCount(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, revealable = false, hide = fame)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Grid cells for [VideoCard]: one column on a phone, two on a tablet in portrait, three or more in landscape. */
val videoGridMinWidth: Dp = 320.dp
