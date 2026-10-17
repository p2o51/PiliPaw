package com.gpo.pilipala.feature.rank

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import kotlinx.coroutines.launch

private val CoverShape = ContinuousRoundedCornerShape(12.dp)

internal fun VideoItem.rankRoute(): Route =
    target ?: Route.Video(bvid = bvid, aid = aid, cid = cid, cover = cover, title = title)

/**
 * The podium card's scale. One per slot and width tier:
 *  - [Hero]: Compact #1, full width.
 *  - [Small]: Compact #2 / #3, half width.
 *  - [Tall]: Medium #1, the raised centre of the podium row.
 *  - [Regular]: Medium #2 / #3 beside it, Expanded #2 / #3 under #1.
 *  - [Large]: Expanded #1, the whole podium pane's width.
 */
internal enum class PodiumSize { Hero, Small, Tall, Regular, Large }

/**
 * What a podium place looks like: #1 primary on a 12-sided cookie (a seal),
 * #2 secondary on a 9-sided cookie, #3 tertiary on a 4-leaf clover. The card
 * carries the matching container, the sticker the strong role colour.
 */
private class PodiumTier(
    val shape: Shape,
    val sticker: Color,
    val onSticker: Color,
    val card: Color,
    val onCard: Color,
)

@Composable
private fun podiumTier(rank: Int): PodiumTier {
    val c = MaterialTheme.colorScheme
    return when (rank) {
        1 -> PodiumTier(MaterialShapes.Cookie12Sided.toShape(), c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer)
        2 -> PodiumTier(MaterialShapes.Cookie9Sided.toShape(), c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer)
        else -> PodiumTier(MaterialShapes.Clover4Leaf.toShape(), c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer)
    }
}

/**
 * A top-three video: a filled card in its place's container colour, a 16:9
 * cover with the rank as a big numeral on a MaterialShapes sticker (top
 * start) and the duration chip (bottom end), then title, UP and icon + number
 * stats. Titles always take two lines and the stats one, so cards of the same
 * size line up. The page it opens grows out of the card.
 */
@Composable
internal fun PodiumCard(
    rank: Int,
    item: VideoItem,
    size: PodiumSize,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = podiumTier(rank)
    val type = MaterialTheme.typography
    val stickerSize = when (size) {
        PodiumSize.Large -> 80.dp
        PodiumSize.Tall -> 72.dp
        PodiumSize.Hero -> 64.dp
        PodiumSize.Regular -> 56.dp
        PodiumSize.Small -> 44.dp
    }
    val numeral = when (size) {
        PodiumSize.Large -> type.displayMedium
        PodiumSize.Tall, PodiumSize.Hero -> type.displaySmall
        PodiumSize.Regular -> type.headlineLarge
        PodiumSize.Small -> type.headlineMedium
    }
    val titleStyle = when (size) {
        PodiumSize.Large, PodiumSize.Tall, PodiumSize.Hero -> type.titleLarge
        PodiumSize.Regular -> type.titleMedium
        PodiumSize.Small -> type.titleSmall
    }
    val thumb = when (size) {
        PodiumSize.Large -> 520.dp
        PodiumSize.Hero, PodiumSize.Tall -> 420.dp
        PodiumSize.Regular -> 300.dp
        PodiumSize.Small -> 220.dp
    }
    val meta = tier.onCard.copy(alpha = 0.78f)
    Column(
        modifier
            .pageSharedSource(item.rankRoute())
            .clip(YoinContainerShapes.Card)
            .background(tier.card)
            .combinedClickable(onLongClick = { onLongPress(item) }) { onOpen(item) }
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = thumb)
            if (item.duration > 0) {
                DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            RankSticker(
                rank = rank,
                key = item.bvid,
                tier = tier,
                size = stickerSize,
                style = numeral,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)) {
            Text(
                item.title,
                style = titleStyle,
                fontWeight = FontWeight.Medium,
                color = tier.onCard,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (size == PodiumSize.Small) 6.dp else 10.dp))
            // Always laid out (even when empty) so neighbouring cards keep one height.
            Text(
                item.ownerName,
                style = if (size == PodiumSize.Small) type.labelMedium else type.labelLarge,
                color = meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // One line: stats that do not fit the card's width are left out.
            FlowRow(
                modifier = Modifier.heightIn(min = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                maxLines = 1,
            ) {
                if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views), tint = meta)
                if (item.likes >= 0) Stat(PiliSymbols.ThumbUp, Fmt.count(item.likes), tint = meta)
                if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku), tint = meta)
            }
        }
    }
}

/**
 * The rank as a sticker: a MaterialShapes block in the place's strong colour
 * with the numeral set in a display/headline style. The first time a video
 * takes a podium place the sticker spins and springs in (spatial spring for
 * scale and turn, effects spring for the fade); scrolling back to it does not
 * replay, because the flag is saved per video.
 */
@Composable
private fun RankSticker(
    rank: Int,
    key: String,
    tier: PodiumTier,
    size: Dp,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var settled by rememberSaveable(key) { mutableStateOf(false) }
    val pop = remember(key) { Animatable(if (settled) 1f else 0f) }
    val fade = remember(key) { Animatable(if (settled) 1f else 0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(key) {
        if (settled) return@LaunchedEffect
        launch { fade.animateTo(1f, effects) }
        pop.animateTo(1f, spatial)
        settled = true
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val p = pop.value
                val s = 0.5f + 0.5f * p
                scaleX = s
                scaleY = s
                rotationZ = -72f * (1f - p)
                alpha = fade.value.coerceIn(0f, 1f)
            }
            .background(tier.sticker, tier.shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            rank.toString(),
            style = style,
            fontWeight = FontWeight.Bold,
            color = tier.onSticker,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Rank 4 and below: a large outline-coloured numeral in a fixed column (so
 * covers line up from 4 to 100), a 16:9 cover with its duration, then title,
 * UP and icon + number stats. Rows are bare (list rows, not cards); the press
 * ripple keeps the list-row shape.
 */
@Composable
internal fun RankRow(
    rank: Int,
    item: VideoItem,
    wide: Boolean,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverWidth = if (wide) 176.dp else 136.dp
    Row(
        modifier
            .fillMaxWidth()
            .pageSharedSource(item.rankRoute())
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(onLongClick = { onLongPress(item) }) { onOpen(item) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankNumeral(rank, wide)
        Box(Modifier.width(coverWidth).aspectRatio(16f / 9f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = coverWidth)
            if (item.duration > 0) {
                DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
        Column(
            Modifier.weight(1f).heightIn(min = coverWidth * 9f / 16f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                item.title,
                style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    if (item.likes >= 0) Stat(PiliSymbols.ThumbUp, Fmt.count(item.likes))
                    if (wide && item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku))
                }
            }
        }
    }
}

@Composable
private fun RankNumeral(rank: Int, wide: Boolean) {
    val type = MaterialTheme.typography
    val style = when {
        wide && rank < 100 -> type.headlineLarge
        wide -> type.headlineMedium
        rank < 100 -> type.headlineMedium
        else -> type.titleLarge
    }
    Text(
        rank.toString(),
        modifier = Modifier.width(if (wide) 64.dp else 44.dp),
        style = style.withTabularFigures(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
