package com.gpo.pilipala.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.roundToInt

/**
 * Enter-always behaviour for a main-tab header: it slides away while the
 * content scrolls up, comes back on any scroll down, and settles on the
 * spatial spring.
 */
@Composable
internal fun rememberHeaderScrollBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = rememberTopAppBarState(),
        snapAnimationSpec = YoinMotion.defaultSpatialSpec(),
        flingAnimationSpec = rememberSplineBasedDecay(),
    )

/**
 * Lays [content] out at its natural height minus the behaviour's offset, so
 * the header collapses in the layout phase only (no recomposition per frame)
 * and fades as it goes.
 */
@Composable
internal fun CollapsingHeader(
    behavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = behavior.state
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val h = (placeable.height + state.heightOffset).roundToInt().coerceIn(0, placeable.height)
                layout(placeable.width, h) { placeable.place(0, h - placeable.height) }
            },
    ) {
        Box(
            Modifier
                .onSizeChanged { state.heightOffsetLimit = -it.height.toFloat() }
                .graphicsLayer { alpha = 1f - state.collapsedFraction },
        ) { content() }
    }
}

/**
 * Yoin's segment chips as a tab row: filled tonal pills (secondaryContainer
 * when on, surfaceContainerHigh when off) that animate their colours on the
 * effects spring. The row scrolls and keeps the selected chip in view.
 */
@Composable
internal fun SegmentTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    var viewport by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(selected, viewport) {
        val (x, w) = bounds[selected] ?: return@LaunchedEffect
        if (viewport == 0) return@LaunchedEffect
        val target = (x + w / 2 - viewport / 2).coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target, spec)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            SegmentChip(
                label = label,
                selected = i == selected,
                onClick = {
                    if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(i)
                },
                modifier = Modifier.onPlaced { c ->
                    bounds[i] = c.positionInParent().x.roundToInt() to c.size.width
                },
            )
        }
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "segmentContainer",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "segmentContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .heightIn(min = 40.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Section label inside a feed (最近追番 / 推荐 / 关注). */
@Composable
internal fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!trailing.isNullOrEmpty()) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
