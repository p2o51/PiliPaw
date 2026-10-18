package com.gpo.pilipala.feature.rank

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.home.feedItemMotion
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.YoinLightColorScheme
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapes
import com.gpo.pilipala.ui.theme.YoinTypography

/** The rank page's three arrangements, by the width the page actually gets. */
internal enum class RankLayout { Compact, Medium, Expanded }

/** Compact < 600 ≤ Medium < 840 ≤ Expanded (tablet portrait 800 = Medium, landscape 1280 = Expanded). */
internal fun rankLayoutFor(width: Dp): RankLayout = when {
    width < 600.dp -> RankLayout.Compact
    width < 840.dp -> RankLayout.Medium
    else -> RankLayout.Expanded
}

/** Page margin: 16dp on phones, 24dp from Medium up. */
internal val RankLayout.margin: Dp get() = if (this == RankLayout.Compact) 16.dp else 24.dp

private const val PodiumKey = "rank_podium"

/**
 * A zone's ranking. Everything reads top-down, rank by rank:
 *  - Compact: one scrolling grid. #1 is a full-width hero card, #2 and #3 sit
 *    side by side under it, then 4…N as rows.
 *  - Medium: a podium row (#2 left, #1 raised in the centre, #3 right) above
 *    a single reading-width column of 4…N.
 *  - Expanded: two panes. Left (40%) the podium stacked (#1 large, #2 and #3
 *    under it), right a single column of 4…N; each pane scrolls on its own.
 */
@Composable
internal fun RankBoard(
    items: List<VideoItem>,
    layout: RankLayout,
    listState: LazyGridState,
    podiumScroll: ScrollState,
    bottomPadding: Dp,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (layout) {
        RankLayout.Compact -> CompactBoard(items, listState, bottomPadding, onOpen, onLongPress, modifier)
        RankLayout.Medium -> MediumBoard(items, listState, bottomPadding, onOpen, onLongPress, modifier)
        RankLayout.Expanded -> ExpandedBoard(items, listState, podiumScroll, bottomPadding, onOpen, onLongPress, modifier)
    }
}

// ── Compact ─────────────────────────────────────────────────────────────────

@Composable
private fun CompactBoard(
    items: List<VideoItem>,
    listState: LazyGridState,
    bottomPadding: Dp,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier,
) {
    val margin = RankLayout.Compact.margin
    // #2 and #3 share a line only when both exist; a lone #2 takes the full width.
    val paired = items.size >= 3
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = listState,
        // The zone chips above are pinned: rows sink under the tide line, and graphics
        // break into the floating bar's halftone field below (ui/components/seam).
        modifier = modifier.fillMaxSize().seamViewport(listState),
        contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items,
            key = { _, item -> item.bvid },
            span = { i, _ -> GridItemSpan(if (paired && (i == 1 || i == 2)) 1 else maxLineSpan) },
            contentType = { i, _ ->
                when (i) {
                    0 -> "hero"
                    1, 2 -> "podium"
                    else -> "row"
                }
            },
        ) { i, item ->
            val motion = feedItemMotion()
            when (i) {
                0 -> PodiumCard(1, item, PodiumSize.Hero, onOpen, onLongPress, motion)
                1, 2 -> PodiumCard(i + 1, item, PodiumSize.Small, onOpen, onLongPress, motion.padding(bottom = 8.dp))
                else -> RankRow(i + 1, item, wide = false, onOpen = onOpen, onLongPress = onLongPress, modifier = motion)
            }
        }
    }
}

// ── Medium ──────────────────────────────────────────────────────────────────

@Composable
private fun MediumBoard(
    items: List<VideoItem>,
    listState: LazyGridState,
    bottomPadding: Dp,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier,
) {
    val margin = RankLayout.Medium.margin
    val podium = remember(items) { items.take(3) }
    val rest = remember(items) { items.drop(3) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        state = listState,
        modifier = modifier.fillMaxSize().readingWidth().seamViewport(listState),
        contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = PodiumKey, contentType = "podium") {
            PodiumRow(podium, onOpen, onLongPress, feedItemMotion().padding(bottom = 8.dp))
        }
        itemsIndexed(rest, key = { _, item -> item.bvid }, contentType = { _, _ -> "row" }) { i, item ->
            RankRow(i + 4, item, wide = true, onOpen = onOpen, onLongPress = onLongPress, modifier = feedItemMotion())
        }
    }
}

/**
 * #2 | #1 | #3 on one floor: bottoms aligned, #1 wider (so its 16:9 cover is
 * taller) with a bigger sticker and title, so it stands above its neighbours.
 * Accessibility services still read #1, #2, #3.
 */
@Composable
private fun PodiumRow(
    podium: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PodiumSlot(
            podium.getOrNull(1), 2, PodiumSize.Regular, onOpen, onLongPress,
            Modifier.weight(1f).semantics { isTraversalGroup = true; traversalIndex = 1f },
        )
        PodiumSlot(
            podium.getOrNull(0), 1, PodiumSize.Tall, onOpen, onLongPress,
            Modifier.weight(1.3f).semantics { isTraversalGroup = true; traversalIndex = 0f },
        )
        PodiumSlot(
            podium.getOrNull(2), 3, PodiumSize.Regular, onOpen, onLongPress,
            Modifier.weight(1f).semantics { isTraversalGroup = true; traversalIndex = 2f },
        )
    }
}

// ── Expanded ────────────────────────────────────────────────────────────────

@Composable
private fun ExpandedBoard(
    items: List<VideoItem>,
    listState: LazyGridState,
    podiumScroll: ScrollState,
    bottomPadding: Dp,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier,
) {
    val margin = RankLayout.Expanded.margin
    val rest = remember(items) { items.drop(3) }
    Row(
        modifier.fillMaxSize().padding(horizontal = margin),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .seamViewport(podiumScroll)
                .verticalScroll(podiumScroll)
                .padding(top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PodiumSlot(items.getOrNull(0), 1, PodiumSize.Large, onOpen, onLongPress, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PodiumSlot(items.getOrNull(1), 2, PodiumSize.Regular, onOpen, onLongPress, Modifier.weight(1f))
                PodiumSlot(items.getOrNull(2), 3, PodiumSize.Regular, onOpen, onLongPress, Modifier.weight(1f))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            state = listState,
            modifier = Modifier.weight(0.6f).fillMaxHeight().seamViewport(listState),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(rest, key = { _, item -> item.bvid }, contentType = { _, _ -> "row" }) { i, item ->
                RankRow(i + 4, item, wide = true, onOpen = onOpen, onLongPress = onLongPress, modifier = feedItemMotion())
            }
        }
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────

/**
 * One podium place outside a lazy list. When the video holding the place
 * changes (refresh, a removal moving everyone up) the cards cross-fade on the
 * effects springs and any size change settles on the spatial spring.
 */
@Composable
private fun PodiumSlot(
    item: VideoItem?,
    rank: Int,
    size: PodiumSize,
    onOpen: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    val resize = YoinMotion.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = item,
        modifier = modifier,
        contentKey = { it?.bvid },
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            (fadeIn(enter) togetherWith fadeOut(exit)).using(SizeTransform(clip = false) { _, _ -> resize })
        },
        label = "podium$rank",
    ) { target ->
        if (target == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            PodiumCard(rank, target, size, onOpen, onLongPress, Modifier.fillMaxWidth())
        }
    }
}

// ── Preview ─────────────────────────────────────────────────────────────────

private val PreviewItems = List(14) { i ->
    VideoItem(
        bvid = "BVpreview$i",
        title = if (i % 3 == 0) "【4K】一段足够长的标题，用来检查两行省略和排行榜卡片的对齐" else "短标题 ${i + 1}",
        ownerName = "UP 主 ${i + 1}",
        views = 4_321_000L - i * 210_000L,
        danmaku = 23_456L - i * 900L,
        likes = 298_765L - i * 15_000L,
        duration = 615L + i * 37L,
    )
}

@Preview(name = "Compact 412dp", widthDp = 412, heightDp = 915)
@Preview(name = "Medium 800dp (tablet portrait)", widthDp = 800, heightDp = 1280)
@Preview(name = "Expanded 1280dp (tablet landscape)", widthDp = 1280, heightDp = 800)
@Composable
private fun RankBoardPreview() {
    MaterialTheme(colorScheme = YoinLightColorScheme, typography = YoinTypography, shapes = YoinShapes) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            RankBoard(
                items = PreviewItems,
                layout = rankLayoutFor(maxWidth),
                listState = rememberLazyGridState(),
                podiumScroll = rememberScrollState(),
                bottomPadding = 24.dp,
                onOpen = {},
                onLongPress = {},
            )
        }
    }
}
