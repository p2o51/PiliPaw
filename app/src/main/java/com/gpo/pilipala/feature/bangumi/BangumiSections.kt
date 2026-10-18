package com.gpo.pilipala.feature.bangumi

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.video.ExpandableText
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinArtworkShapes
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets
import androidx.compose.foundation.layout.heightIn
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered
import com.gpo.pilipala.ui.components.Tag

/** Poster + title + score + styles + stats, with the 追番 toggle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BangumiHeader(
    d: BangumiDetail,
    following: Boolean,
    followBusy: Boolean,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NetImage(
            d.cover,
            Modifier.width(112.dp).aspectRatio(3f / 4f),
            shape = YoinArtworkShapes.Hero,
            thumbWidth = 224.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (d.score > 0) {
                    Text(
                        String.format(java.util.Locale.US, "%.1f", d.score),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("分", style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(bottom = 3.dp))
                    if (d.ratingCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        // 名声模式: the raters count is a line (a tap shows it); 人评 stays.
                        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FameCount(
                                Fmt.count(d.ratingCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            Text("人评", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("暂无评分", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Stat(PiliSymbols.Views, Fmt.count(d.views), revealable = true)
                Stat(PiliSymbols.Danmaku, Fmt.count(d.danmakus), revealable = true)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                d.areas.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                if (d.pubTimeShow.isNotEmpty()) Text(d.pubTimeShow, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (d.newEpDesc.isNotEmpty()) Text(d.newEpDesc, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            BangumiFollowToggle(d.followVerb, following, followBusy, onFollow)
        }
    }
}

@Composable
private fun BangumiFollowToggle(verb: String, following: Boolean, busy: Boolean, onFollow: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (following) colors.secondaryContainer else colors.primary,
        YoinMotion.defaultEffectsSpec(),
        label = "followContainer",
    )
    val content by animateColorAsState(
        if (following) colors.onSecondaryContainer else colors.onPrimary,
        YoinMotion.defaultEffectsSpec(),
        label = "followContent",
    )
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    ToggleButton(
        checked = following,
        onCheckedChange = {
            if (!busy) {
                haptic.performHapticFeedback(if (following) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onFollow()
            }
        },
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = container,
            contentColor = content,
            checkedContainerColor = container,
            checkedContentColor = content,
        ),
    ) {
        AnimatedContent(following, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "bgmFollow") { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (f) YoinSymbols.FavoriteFilled else YoinSymbols.Favorite, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (f) "已$verb" else verb, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Styles as tonal chips + the evaluate text (actors / staff unfold with it). */
@Composable
internal fun BangumiDescription(d: BangumiDetail, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(d.seasonId) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        if (d.styles.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(d.styles) { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSecondaryContainer,
                        modifier = Modifier
                            .clip(ContinuousRoundedCornerShape(10.dp))
                            .background(colors.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (d.evaluate.isNotBlank()) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(colors.surfaceContainerLow)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                ExpandableText(
                    text = AnnotatedString(d.evaluate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    collapsedLines = 3,
                    expandedState = expanded,
                    onToggle = { expanded = !expanded },
                )
                val effects = YoinMotion.defaultEffectsSpec<Float>()
                AnimatedVisibility(
                    visible = expanded && (d.actors.isNotBlank() || d.staff.isNotBlank()),
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(effects),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(effects),
                ) {
                    Column {
                        if (d.actors.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("角色声优", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(d.actors, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (d.staff.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("制作信息", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(d.staff, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Other seasons of the series as selectable blocks. */
@Composable
internal fun SeasonSwitcher(seasons: List<BangumiSeasonRef>, currentId: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.seasonId }) { s ->
            val selected = s.seasonId == currentId
            val colors = MaterialTheme.colorScheme
            val container by animateColorAsState(
                if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
                YoinMotion.defaultEffectsSpec(),
                label = "seasonContainer",
            )
            Row(
                Modifier
                    .clip(YoinContainerShapes.Card)
                    .background(container)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(s.seasonId)
                    }
                    .padding(6.dp)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetImage(s.cover, Modifier.width(36.dp).aspectRatio(3f / 4f), shape = YoinArtworkShapes.Thumb, thumbWidth = 72.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                        maxLines = 1,
                    )
                    if (s.badge.isNotEmpty()) {
                        Text(s.badge, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                }
            }
        }
    }
}

/** Episode tiles in a grid (sheet / side pane). */
@Composable
internal fun EpisodeGrid(
    groups: List<BangumiSection>,
    currentId: Long,
    onSelect: (BangumiEpisode) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val haptic = LocalHapticFeedback.current
    val state = rememberLazyGridState()
    LaunchedEffect(Unit) {
        var idx = 0
        for (g in groups) {
            if (groups.size > 1) idx++
            val i = g.episodes.indexOfFirst { it.id == currentId }
            if (i >= 0) {
                idx += i
                if (idx > 4) state.scrollToItem(idx - 2)
                break
            }
            idx += g.episodes.size
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEachIndexed { gi, g ->
            if (groups.size > 1) {
                item(key = "h$gi", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        g.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (gi == 0) 0.dp else 12.dp, start = 4.dp),
                    )
                }
            }
            items(g.episodes, key = { "e${it.id}" }) { ep ->
                EpisodeTile(ep, ep.id == currentId) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(ep)
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(ep: BangumiEpisode, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "tile",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.heightIn(min = IndexRowHeight), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(YoinSymbols.Equalizer, null, Modifier.size(IndexStyle().inlineIconSize()), tint = colors.primary)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                ep.title,
                style = IndexStyle(),
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (ep.badge.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Tag(ep.badge)
            }
        }
        Text(
            ep.longTitle.ifEmpty { ep.showTitle },
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EpisodeGridSheet(
    title: String,
    groups: List<BangumiSection>,
    currentId: Long,
    onSelect: (BangumiEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    val navBottom = sheetNavBottom()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("${groups.sumOf { it.episodes.size }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.7f).dp + navBottom)) {
                EpisodeGrid(
                    groups,
                    currentId,
                    onSelect,
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + navBottom),
                )
            }
        }
    }
}


/** The index line of an episode tile / row: tight label, so the playing glyph and tag centre on it. */
@Composable
private fun IndexStyle() = MaterialTheme.typography.labelMedium.centered()

/** Index lines keep one height whether or not they carry a tag. */
private val IndexRowHeight = 18.dp
