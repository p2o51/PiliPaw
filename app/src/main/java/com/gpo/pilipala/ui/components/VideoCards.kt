package com.gpo.pilipala.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.PiliSymbols

/** Common video summary used by every list (feeds, search, history, favs…). */
@Immutable
data class VideoItem(
    val bvid: String = "",
    val aid: Long = 0,
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    /** Seconds. */
    val duration: Long = 0,
    val ownerMid: Long = 0,
    val ownerName: String = "",
    val ownerFace: String = "",
    val views: Long = -1,
    val danmaku: Long = -1,
    val likes: Long = -1,
    /** Unix seconds. */
    val pubdate: Long = 0,
    /** Short tag on the cover corner ("已关注" / "番剧" / "直播"). */
    val badge: String = "",
    /** Recommendation reason / secondary label shown under the title. */
    val reason: String = "",
    /** Watch progress in seconds (history), -1 = finished. */
    val progress: Long = 0,
    /** Non-video targets (live / bangumi / article) — when set, cards open this instead of the video page. */
    val target: Route? = null,
)

private val CoverShape = ContinuousRoundedCornerShape(12.dp)

private fun VideoItem.route(): Route = target ?: Route.Video(bvid = bvid, aid = aid, cid = cid, cover = cover, title = title, progress = progress.coerceAtLeast(0))

/**
 * Grid card (home feeds). Cover 16:10, title two lines, owner as the quiet
 * second line. Stats sit on the cover's bottom scrim as icon + number pairs —
 * no "·" chains (Yoin copy rule).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCardV(
    item: VideoItem,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    Column(
        modifier = modifier
            .clip(YoinContainerShapes.Card)
            .combinedClickable(onLongClick = onLongClick, onClick = { onClick?.invoke() ?: nav.push(item.route()) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 360.dp)
            CoverScrim(item, Modifier.align(Alignment.BottomCenter))
            if (item.badge.isNotEmpty()) CoverBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val second = item.reason.ifEmpty { item.ownerName }
            if (second.isNotEmpty()) {
                Text(
                    second,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.reason.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Row card (search, history, favorites, related). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCardH(
    item: VideoItem,
    modifier: Modifier = Modifier,
    coverWidth: Dp = 152.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(onLongClick = onLongClick, onClick = { onClick?.invoke() ?: nav.push(item.route()) })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(coverWidth).aspectRatio(16f / 10f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = coverWidth)
            if (item.duration > 0) {
                DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            if (item.badge.isNotEmpty()) CoverBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (item.progress != 0L && item.duration > 0) {
                val frac = if (item.progress < 0) 1f else (item.progress.toFloat() / item.duration).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(Modifier.weight(1f).height(coverWidth * 10f / 16f), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (item.ownerName.isNotEmpty()) {
                    Text(
                        item.ownerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views))
                    if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku))
                    if (item.pubdate > 0) {
                        Text(
                            Fmt.relativeTime(item.pubdate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

/** Icon + number pair. */
@Composable
fun Stat(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.outline) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun CoverScrim(item: VideoItem, modifier: Modifier) {
    if (item.views < 0 && item.danmaku < 0 && item.duration <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CoverShape)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views), tint = Color.White)
        if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku), tint = Color.White)
        Spacer(Modifier.weight(1f))
        if (item.duration > 0) {
            Text(Fmt.duration(item.duration), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

@Composable
fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(ContinuousRoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

/** Small filled tag (color block, never an outline — Yoin). */
@Composable
fun CoverBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(ContinuousRoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}

/** Inline tag next to text (UP / 置顶 / 热评…). */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Text(
        text,
        modifier = modifier
            .clip(ContinuousRoundedCornerShape(4.dp))
            .background(container)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
    )
}
