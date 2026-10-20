package com.gpo.pilipala.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.videomenu.VideoCardMenu
import com.gpo.pilipala.feature.videomenu.isPlainVideo
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.seam.seamGraphic
import com.gpo.pilipala.ui.components.seam.seamText
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

/**
 * A card's long press: the list's own [onLongClick], else the shared video card menu
 * (VideoCardMenu.kt) for plain videos, hosted by the card itself.
 */
@Composable
private fun cardLongClick(item: VideoItem, onLongClick: (() -> Unit)?): (() -> Unit)? {
    if (onLongClick != null || !item.isPlainVideo) return onLongClick
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    if (menu) VideoCardMenu(item, onDismiss = { menu = false })
    return {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menu = true
    }
}

private fun VideoItem.route(): Route =target ?: Route.Video(bvid = bvid, aid = aid, cid = cid, cover = cover, title = title, progress = progress.coerceAtLeast(0))

/**
 * Grid card (home feeds). The page it opens grows out of it (container
 * transform, [pageSharedSource]). Cover 16:10, title two lines, owner as the quiet
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
    val longClick = cardLongClick(item, onLongClick)
    Column(
        modifier = modifier
            .pageSharedSource(item.route())
            .clip(YoinContainerShapes.Card)
            .combinedClickable(onLongClick = longClick, onClick = { onClick?.invoke() ?: nav.push(item.route()) }),
    ) {
        // Cover, scrim and badge are one print for the seams; the labels on it lift out whole.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).seamGraphic()) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 360.dp)
            CoverStats(item, Modifier.align(Alignment.BottomCenter))
            if (item.badge.isNotEmpty()) CoverBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        ReservedTitleColumn(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp).seamText()) { reserve ->
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = if (reserve) 2 else 1,
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

/**
 * 首页单列 (设置 › 外观 › 首页布局): the YouTube-style card, one per row on a phone.
 * A full-width 16:9 cover with only the duration on it, then the UP's avatar beside the
 * title, with the UP, 播放 and date on one line under it.
 */
@Composable
fun VideoCardL(
    item: VideoItem,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    val longClick = cardLongClick(item, onLongClick)
    Column(
        modifier = modifier
            .pageSharedSource(item.route())
            .clip(YoinContainerShapes.Card)
            .combinedClickable(onLongClick = longClick, onClick = { onClick?.invoke() ?: nav.push(item.route()) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).seamGraphic()) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 720.dp)
            if (item.duration > 0) {
                DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            if (item.badge.isNotEmpty()) CoverBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.ownerFace.isNotEmpty()) {
                Avatar(item.ownerFace, 36.dp) {
                    nav.push(Route.Member(item.ownerMid, item.ownerFace, item.ownerName))
                }
            }
            Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FameSelf(item.ownerMid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.ownerName.isNotEmpty()) {
                            Text(
                                item.ownerName,
                                style = MaterialTheme.typography.labelMedium.centered(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views))
                        if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku))
                        if (item.pubdate > 0) {
                            Text(
                                Fmt.relativeTime(item.pubdate),
                                style = MaterialTheme.typography.labelSmall.centered(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (item.reason.isNotEmpty()) {
                    Text(
                        item.reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    val longClick = cardLongClick(item, onLongClick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pageSharedSource(item.route())
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(onLongClick = longClick, onClick = { onClick?.invoke() ?: nav.push(item.route()) })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(coverWidth).aspectRatio(16f / 10f).seamGraphic()) {
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
        Column(Modifier.weight(1f).height(coverWidth * 10f / 16f).seamText(), verticalArrangement = Arrangement.SpaceBetween) {
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
                FameSelf(item.ownerMid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views))
                        if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku))
                        if (item.pubdate > 0) {
                            Text(
                                Fmt.relativeTime(item.pubdate),
                                style = MaterialTheme.typography.labelSmall.centered(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * Icon + number pair. Information text, so onSurfaceVariant by default —
 * `outline` is a border token and falls under 4.5:1 at 11sp.
 *
 * Under 名声模式 a popularity number ([fame]: 播放, 弹幕, 点赞… glyphs by default) draws
 * as [FameCount]'s line in [tint]; [revealable] lets a tap on it show the number
 * (only where the pair is not inside a bigger clickable — cards keep their tap).
 * [self]: the number measures the signed-in user ([LocalFameSelf] by default, see
 * [FameSelf]); 只隐藏自己的 hides only those.
 */
@Composable
fun Stat(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fame: Boolean = icon.isPopularityGlyph(),
    revealable: Boolean = false,
    self: Boolean = LocalFameSelf.current,
) {
    val style = MaterialTheme.typography.labelSmall.centered()
    Row(modifier.seamText(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(style.inlineIconSize()), tint = tint)
        FameCount(
            text,
            style = style,
            color = tint,
            lineColor = tint,
            revealable = revealable,
            hide = fame,
            self = self,
        )
    }
}

@Composable
private fun CoverStats(item: VideoItem, modifier: Modifier) {
    if (item.views < 0 && item.danmaku < 0 && item.duration <= 0) return
    FameSelf(item.ownerMid) {
        CoverScrim(modifier) {
            if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views), tint = Color.White)
            if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku), tint = Color.White)
            Spacer(Modifier.weight(1f))
            if (item.duration > 0) {
                Text(Fmt.duration(item.duration), style = MaterialTheme.typography.labelSmall.centered(), color = Color.White, modifier = Modifier.seamText())
            }
        }
    }
}

/** Fixed height of the bottom-of-cover scrim, whatever the row's own height. */
val CoverScrimHeight = 48.dp

private val CoverScrimStops = arrayOf(
    0f to Color.Transparent,
    0.4f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Black.copy(alpha = 0.7f),
)

/**
 * The one scrim for white labels on a cover's bottom edge (stats, duration,
 * live area, episode…): a [CoverScrimHeight] gradient that is already dense
 * behind the text, so the labels hold on white or bright covers. Drawn in the
 * cover's [shape] — no clip layer. Place it at the cover's BottomCenter.
 */
@Composable
fun CoverScrim(
    modifier: Modifier = Modifier,
    shape: Shape = CoverShape,
    content: @Composable RowScope.() -> Unit,
) {
    // The labels centre on each other (icon pairs, plain text, chips of other heights);
    // the line as a whole sits on the scrim's bottom edge.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CoverScrimHeight)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val brush = Brush.verticalGradient(*CoverScrimStops)
                onDrawBehind { drawOutline(outline, brush) }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .seamText()
            .clip(ContinuousRoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.centered(),
        color = Color.White,
    )
}

/**
 * Small filled tag on a cover (color block, never an outline — Yoin). Tertiary,
 * so the theme's accent seed shows up across feeds.
 */
@Composable
fun CoverBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .seamText()
            .clip(ContinuousRoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall.centered(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onTertiary,
    )
}

/**
 * Inline tag next to text (UP / LV / 关注 / 置顶 / 热评…), on the accent's container by
 * default. The text is set tight ([centered]) with equal padding above and below, so the
 * block is about as tall as a labelLarge name and centres on it in a CenterVertically row.
 */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.tertiaryContainer,
    content: Color = MaterialTheme.colorScheme.onTertiaryContainer,
) {
    Text(
        text,
        modifier = modifier
            .seamText()
            .clip(TagDefaults.Shape)
            .background(container)
            .padding(horizontal = TagDefaults.HorizontalPadding, vertical = TagDefaults.VerticalPadding),
        style = TagDefaults.textStyle,
        color = content,
        maxLines = 1,
    )
}

/** [Tag]'s metrics, shared with tags drawn inline in text ([rememberTagPlaceholder]). */
object TagDefaults {
    val HorizontalPadding = 4.dp
    val VerticalPadding = 2.dp
    val Shape = ContinuousRoundedCornerShape(4.dp)
    val textStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.centered()
}

/**
 * An inline-text placeholder exactly the size of a [Tag] showing [text], centred on the
 * line's text: an inline tag matches the tags in a row header and sits on the text's centre.
 */
@Composable
fun rememberTagPlaceholder(text: String): Placeholder {
    val measurer = rememberTextMeasurer()
    val style = TagDefaults.textStyle
    val density = LocalDensity.current
    return remember(text, style, density) {
        val size = measurer.measure(text, style, maxLines = 1).size
        with(density) {
            val w = size.width + 2 * TagDefaults.HorizontalPadding.toPx()
            val h = size.height + 2 * TagDefaults.VerticalPadding.toPx()
            Placeholder(w.toSp(), h.toSp(), PlaceholderVerticalAlign.TextCenter)
        }
    }
}
