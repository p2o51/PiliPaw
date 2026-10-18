package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.player.PlayMode
import com.gpo.pilipala.feature.player.PlayQueue
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.player.playQueueItem
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberEqualizerSymbolPainter
import com.gpo.yoin.symbols.rememberExpandSymbolPainter
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

// 播放列表: a 收藏夹 / 稍后再看 played as a queue. The player steps it; these views follow it.

/** How many rows the inline block shows around the current item. */
private const val InlineWindow = 5

/**
 * Compact / Medium: a collapsible block in the intro tab — header, mode + 仅听声音,
 * a few rows around the current item, and 全部 for the full list ([onShowAll]).
 */
@Composable
internal fun QueueBlock(
    queue: PlayQueue,
    player: BiliPlayerState,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index by queue.index.collectAsState()
    var expanded by rememberSaveable(queue.id) { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize(YoinMotion.defaultSpatialSpec()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    expanded = !expanded
                }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueTitle(queue, index, Modifier.weight(1f))
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    expanded = !expanded
                },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(rememberExpandSymbolPainter(expanded), contentDescription = if (expanded) "收起" else "展开")
            }
        }
        QueueControls(queue, Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = if (expanded) 4.dp else 12.dp)) {
            TextButton(onClick = onShowAll) {
                Text("全部", style = MaterialTheme.typography.labelLarge)
                Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = shrinkVertically(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            val n = queue.size
            val first = (index - 1).coerceIn(0, (n - InlineWindow).coerceAtLeast(0))
            val last = (first + InlineWindow).coerceAtMost(n)
            Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                for (i in first until last) {
                    QueueRow(
                        item = queue.items[i],
                        position = i,
                        current = i == index,
                        playing = player.isPlaying,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            if (i != index) player.playQueueItem(i)
                        },
                    )
                }
            }
        }
    }
}

/** Expanded: the right pane's 播放列表 tab — the whole list, the current item kept in view. */
@Composable
internal fun QueueTab(queue: PlayQueue, player: BiliPlayerState, contentPadding: PaddingValues) {
    val index by queue.index.collectAsState()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    LaunchedEffect(index) {
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index + 1 }
        if (!visible) listState.animateScrollToItem(index.coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item("queueHeader") {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                QueueTitle(queue, index)
                Spacer(Modifier.height(8.dp))
                QueueControls(queue)
            }
        }
        itemsIndexed(queue.items, key = { _, it -> "q_${it.bvid}" }) { i, item ->
            QueueRow(
                item = item,
                position = i,
                current = i == index,
                playing = player.isPlaying,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    if (i != index) player.playQueueItem(i)
                },
            )
        }
    }
}

/** The full list in a bottom sheet (Compact / Medium 全部). */
@Composable
internal fun QueueSheet(queue: PlayQueue, player: BiliPlayerState, onDismiss: () -> Unit) {
    val index by queue.index.collectAsState()
    val haptic = LocalHapticFeedback.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    val navBottom = sheetNavBottom()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                QueueTitle(queue, index, large = true)
                Spacer(Modifier.height(8.dp))
            }
            QueueControls(queue, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                if (index > 2) listState.scrollToItem(index - 2)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight + navBottom),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + navBottom),
            ) {
                itemsIndexed(queue.items, key = { _, it -> "q_${it.bvid}" }) { i, item ->
                    QueueRow(
                        item = item,
                        position = i,
                        current = i == index,
                        playing = player.isPlaying,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            if (i != index) player.playQueueItem(i)
                        },
                    )
                }
            }
        }
    }
}

/** "播放列表" label, the queue's title and the position as its own quiet number. */
@Composable
private fun QueueTitle(queue: PlayQueue, index: Int, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier) {
        Text("播放列表", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row {
            Text(
                queue.title,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${index + 1}/${queue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** Play mode (one button, three modes) + 仅听声音, then whatever the host adds at the end. */
@Composable
private fun QueueControls(
    queue: PlayQueue,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val mode by queue.mode.collectAsState()
    val audioOnly by queue.audioOnly.collectAsState()
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayModeButton(mode) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            queue.cycleMode()
        }
        AudioOnlyToggle(audioOnly) { on ->
            haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            queue.setAudioOnly(on)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Yoin's play-mode key: 列表循环 rests on tertiaryContainer, 随机 / 单曲循环 light up in primary. */
@Composable
private fun PlayModeButton(mode: PlayMode, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lit = mode != PlayMode.ListLoop
    val container by animateColorAsState(
        if (lit) colors.primary else colors.tertiaryContainer,
        YoinMotion.defaultEffectsSpec(),
        label = "modeContainer",
    )
    val content by animateColorAsState(
        if (lit) colors.onPrimary else colors.onTertiaryContainer,
        YoinMotion.defaultEffectsSpec(),
        label = "modeContent",
    )
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(rememberPlayModeSymbolPainter(mode.symbol), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        val effects = YoinMotion.defaultEffectsSpec<Float>()
        AnimatedContent(mode.label, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "modeLabel") {
            Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun AudioOnlyToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (checked) colors.primary else colors.surfaceContainerHighest,
        YoinMotion.defaultEffectsSpec(),
        label = "audioContainer",
    )
    val content by animateColorAsState(
        if (checked) colors.onPrimary else colors.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "audioContent",
    )
    ToggleButton(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = container,
            contentColor = content,
            checkedContainerColor = container,
            checkedContentColor = content,
        ),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(YoinSymbols.Headphones, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("仅听声音", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** One queue entry; the current one is a primary-container block with a live equalizer. */
@Composable
private fun QueueRow(item: QueueItem, position: Int, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (current) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f),
        YoinMotion.defaultEffectsSpec(),
        label = "queueRow",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Icon(rememberEqualizerSymbolPainter(playing), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    "${position + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(96.dp).aspectRatio(16f / 10f)) {
            NetImage(item.cover, Modifier.fillMaxSize(), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 96.dp)
            if (item.duration > 0) DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) colors.onPrimaryContainer else colors.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.owner.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.owner,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current) colors.onPrimaryContainer.copy(alpha = 0.72f) else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
