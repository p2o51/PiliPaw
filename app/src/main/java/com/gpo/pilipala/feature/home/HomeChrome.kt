package com.gpo.pilipala.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.gpo.yoin.symbols.YoinSymbols
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.launch.splashReveal
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
 * Yoin's segment chips as a tab row: filled tonal pills (primary when on,
 * surfaceContainerHigh when off) that animate their colours on the effects
 * spring and tighten their corners while pressed. The row scrolls and keeps
 * the selected chip in view. Chips for which [longClickable] holds answer a
 * long press with [onLongClick]; the selected one of them also grows a small
 * trailing chevron (on the spatial spring) that opens the same [onLongClick], so the
 * menu has a visible door. [trailing] sits after the last chip (the home row's "+").
 */
@Composable
internal fun SegmentTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    onLongClick: ((Int) -> Unit)? = null,
    longClickable: (Int) -> Boolean = { onLongClick != null },
    trailing: (@Composable () -> Unit)? = null,
    /** Inline after chip i, in the same row (Home's account chips after 推荐 / 关注). */
    afterChip: (@Composable (Int) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    var viewport by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    // Also waits for a chip that is not measured yet (a feed tab created and selected at once).
    LaunchedEffect(selected) {
        snapshotFlow { bounds[selected] to viewport }.collectLatest { (b, vp) ->
            val (x, w) = b ?: return@collectLatest
            if (vp == 0) return@collectLatest
            val target = (x + w / 2 - vp / 2).coerceIn(0, scroll.maxValue)
            scroll.animateScrollTo(target, spec)
        }
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
                onLongClick = if (onLongClick != null && longClickable(i)) ({ onLongClick(i) }) else null,
                showAction = onLongClick != null && i == selected && longClickable(i),
                modifier = Modifier
                    .onPlaced { c ->
                        bounds[i] = c.positionInParent().x.roundToInt() to c.size.width
                    }
                    .splashReveal(),
            )
            afterChip?.invoke(i)
        }
        trailing?.invoke()
    }
}

/** Pill corner at rest (a 40dp chip is fully round) and while pressed (M3 press morph). */
private val ChipCorner = 20.dp
private val ChipPressedCorner = 8.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showAction: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The chevron's room comes out of the end padding, so the chip grows by less than the glyph.
    val endPad by animateDpAsState(if (showAction && onLongClick != null) 6.dp else 16.dp, YoinMotion.defaultSpatialSpec(), label = "segmentEndPad")
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
    val corner by animateDpAsState(if (pressed) ChipPressedCorner else ChipCorner, YoinMotion.fastSpatialSpec(), label = "segmentCorner")
    Box(
        modifier
            .semantics { this.selected = selected }
            .heightIn(min = 40.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                role = Role.Tab,
                onLongClickLabel = if (onLongClick != null) "管理" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(start = 16.dp, end = endPad, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(
                visible = showAction && onLongClick != null,
                enter = expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start) +
                    fadeIn(YoinMotion.defaultEffectsSpec()) +
                    scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
                exit = shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start) +
                    fadeOut(YoinMotion.fastEffectsSpec()) +
                    scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
            ) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(content.copy(alpha = 0.16f))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "管理",
                            onClick = { onLongClick?.invoke() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YoinSymbols.ChevronDown, contentDescription = "管理", modifier = Modifier.size(18.dp), tint = content)
                }
            }
        }
    }
}

/**
 * Chip at the end of a segment row (same pill, same press morph): the icon alone, or
 * the icon with a short [label] beside it. The label comes and goes on the spatial
 * spring, the pill's width following it.
 */
@Composable
internal fun SegmentIconChip(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) ChipPressedCorner else ChipCorner, YoinMotion.fastSpatialSpec(), label = "iconChipCorner")
    val scheme = MaterialTheme.colorScheme
    // Keeps the text on screen while the label folds away.
    val lastLabel = remember { arrayOf(label.orEmpty()) }
    if (label != null) lastLabel[0] = label
    val shownLabel = lastLabel[0]
    Row(
        modifier
            .height(40.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(corner))
            .background(scheme.surfaceContainerHigh)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = scheme.onSurfaceVariant),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = if (label == null) contentDescription else null, modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        AnimatedVisibility(
            visible = label != null,
            enter = expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(6.dp))
                Text(shownLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
        }
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
