package com.gpo.pilipala.feature.video

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/** One playable entry in a 分P / 合集 list. */
@Immutable
data class EpisodeEntry(
    val key: Long,
    val index: String,
    val title: String,
    val cover: String = "",
    val duration: Long = 0,
    val badge: String = "",
)

@Immutable
data class EpisodeGroup(val title: String, val entries: List<EpisodeEntry>)

/**
 * Section header + horizontally scrolling entry cards (bleeds to the page edges,
 * 16dp content padding). The current entry is a primary-container block.
 */
@Composable
internal fun EpisodeRow(
    title: String,
    entries: List<EpisodeEntry>,
    currentKey: Long,
    onSelect: (EpisodeEntry) -> Unit,
    onShowAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    withCovers: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val currentIndex = entries.indexOfFirst { it.key == currentKey }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(
                if (currentIndex >= 0) "${currentIndex + 1}/${entries.size}" else "${entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (onShowAll != null) {
                TextButton(onClick = onShowAll) {
                    Text("全部", style = MaterialTheme.typography.labelLarge)
                    Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp))
                }
            }
        }
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        val listState = rememberLazyListState()
        LaunchedEffect(currentKey, entries.size) {
            if (currentIndex > 0) listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> e.key }) { _, e ->
                EpisodeCard(
                    entry = e,
                    selected = e.key == currentKey,
                    withCover = withCovers && e.cover.isNotEmpty(),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(e)
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(entry: EpisodeEntry, selected: Boolean, withCover: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "epContainer",
    )
    val content by animateColorAsState(
        if (selected) colors.onPrimaryContainer else colors.onSurface,
        YoinMotion.defaultEffectsSpec(),
        label = "epContent",
    )
    Column(
        Modifier
            .width(if (withCover) 168.dp else 148.dp)
            .clip(YoinContainerShapes.Card)
            .background(container)
            .clickable(onClick = onClick),
    ) {
        if (withCover) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(6.dp)) {
                NetImage(entry.cover, Modifier.fillMaxSize(), shape = ContinuousRoundedCornerShape(10.dp), thumbWidth = 168.dp)
                if (entry.duration > 0) DurationChip(Fmt.duration(entry.duration), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (withCover) 4.dp else 10.dp).padding(bottom = if (withCover) 6.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(YoinSymbols.Equalizer, null, Modifier.size(14.dp), tint = colors.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    entry.index,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.badge.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onTertiaryContainer,
                        modifier = Modifier
                            .clip(ContinuousRoundedCornerShape(4.dp))
                            .background(colors.tertiaryContainer)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Full list (sections) in a bottom sheet, current entry highlighted and scrolled into view. */
@Composable
internal fun EpisodeListSheet(
    title: String,
    groups: List<EpisodeGroup>,
    currentKey: Long,
    onSelect: (EpisodeEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text("${groups.sumOf { it.entries.size }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // Flatten into rows: headers + entries.
            val rows = buildList {
                groups.forEach { g ->
                    if (groups.size > 1 && g.title.isNotEmpty()) add(g.title to null)
                    g.entries.forEach { add("" to it) }
                }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                val i = rows.indexOfFirst { it.second?.key == currentKey }
                if (i > 2) listState.scrollToItem(i - 2)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(maxHeight.coerceAtMost((rows.size * 72 + 32).dp)),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                itemsIndexed(rows, key = { i, r -> r.second?.key?.let { "e$it" } ?: "h$i" }) { _, (header, entry) ->
                    if (entry == null) {
                        Text(
                            header,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                        )
                    } else {
                        EpisodeListRow(entry, entry.key == currentKey) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeListRow(entry: EpisodeEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerLow.copy(alpha = 0f),
        YoinMotion.defaultEffectsSpec(),
        label = "epRow",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.cover.isNotEmpty()) {
            Box(Modifier.width(96.dp).aspectRatio(16f / 10f)) {
                NetImage(entry.cover, Modifier.fillMaxSize(), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 96.dp)
                if (entry.duration > 0) DurationChip(Fmt.duration(entry.duration), Modifier.align(Alignment.BottomEnd).padding(3.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(YoinSymbols.Equalizer, null, Modifier.size(14.dp), tint = colors.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    entry.index,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.badge.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onTertiaryContainer,
                        modifier = Modifier
                            .clip(ContinuousRoundedCornerShape(4.dp))
                            .background(colors.tertiaryContainer)
                            .padding(horizontal = 4.dp),
                    )
                }
                if (entry.cover.isEmpty() && entry.duration > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.duration(entry.duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
