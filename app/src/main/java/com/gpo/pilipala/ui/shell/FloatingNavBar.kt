package com.gpo.pilipala.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.seam.seamBarSource
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Bar height + margins the content must clear (portrait: 68 + 16 + 8; centred: 60 + 24 + 8). */
@Composable
fun floatingBarInset(): Dp = if (widthClass() == WidthClass.Compact) 92.dp else 92.dp

/**
 * Yoin's floating Button Group, rebuilt for four tabs: a pill surface
 * (surfaceContainerHigh, no shadow — the seams' bottom field parts it from the
 * content) holding connected buttons. The selected
 * one widens with a label on primaryContainer; a pressed one blooms
 * tertiaryContainer; pressing a button pushes its neighbours (shape-morph
 * feel) — all with the fast spatial spring.
 * Medium+ windows center the bar and cap it at 600dp.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Long press on a tab; null for a tab ([hasLongPress] false) leaves it a plain button. */
    onLongPress: (Int) -> Unit = {},
    hasLongPress: (Int) -> Boolean = { false },
    /** A small dot on a tab's icon (动态 with new posts). */
    badge: (Int) -> Boolean = { false },
    /** The side rail's pill: a column, the selected tab taller with its label under the icon. */
    vertical: Boolean = false,
) {
    if (vertical) {
        VerticalNavBar(tabs, selected, onSelect, modifier, onLongPress, hasLongPress, badge)
        return
    }
    val compact = widthClass() == WidthClass.Compact
    val rowHeight = if (compact) 68.dp else 60.dp
    val buttonHeight = if (compact) 48.dp else 44.dp
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = if (compact) 8.dp else 24.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = if (compact) 480.dp else 600.dp),
    ) {
        // Opaque and shadowless: the content runs on under it as the seams' halftone
        // field (ui/components/seam), which is what separates bar and content.
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            modifier = Modifier.seamBarSource(MaterialTheme.shapes.extraLarge, barColor),
            shape = MaterialTheme.shapes.extraLarge,
            color = barColor,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(rowHeight)
                    .padding(horizontal = 10.dp)
                    // 继续播放: the mini bar's picture merges into the nav bar's start as a circle;
                    // the bar opens room for it as it pours in (read while laying out, every frame).
                    .layout { measurable, constraints ->
                        val room = (com.gpo.pilipala.feature.sources.KeepPlaying.navRoom *
                            com.gpo.pilipala.feature.sources.NavRoom.toPx()).roundToInt()
                        val placeable = measurable.measure(constraints.offset(horizontal = -room))
                        layout(placeable.width + room, placeable.height) { placeable.place(room, 0) }
                    },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavButton(
                        tab = tab,
                        selected = index == selected,
                        badge = badge(index),
                        height = buttonHeight,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        },
                        onLongClick = if (hasLongPress(index)) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress(index)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The same button group stood up for the side rail: a 68dp pill of 52dp buttons, the selected one
 * taller with its label under the icon, a press pushing its neighbours up and down. The merged
 * mini player's circle joins at the pill's bottom end, which grows down to let it in.
 */
@Composable
private fun VerticalNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    onLongPress: (Int) -> Unit,
    hasLongPress: (Int) -> Boolean,
    badge: (Int) -> Boolean,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .width(RailPillWidth)
                .padding(vertical = 8.dp)
                .layout { measurable, constraints ->
                    val room = (com.gpo.pilipala.feature.sources.KeepPlaying.navRoom *
                        com.gpo.pilipala.feature.sources.NavRoom.toPx()).roundToInt()
                    val placeable = measurable.measure(constraints.offset(vertical = -room))
                    layout(placeable.width, placeable.height + room) { placeable.place(0, 0) }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.forEachIndexed { index, tab ->
                NavButton(
                    tab = tab,
                    selected = index == selected,
                    badge = badge(index),
                    height = RailButtonSize,
                    vertical = true,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(index)
                    },
                    onLongClick = if (hasLongPress(index)) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(index)
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The side rail's pill and its buttons. */
internal val RailPillWidth = 68.dp
private val RailButtonSize = 52.dp

@Composable
private fun NavButton(tab: NavTab, selected: Boolean, height: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, badge: Boolean = false, vertical: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sel by animateFloatAsState(if (selected) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "sel")
    val extra by animateDpAsState(if (pressed) 10.dp else 0.dp, YoinMotion.fastSpatialSpec(), label = "press")
    // Shape morph: selected = squircle-ish 16dp, idle = full pill; pressed tightens.
    val corner by animateDpAsState(
        when {
            pressed -> 12.dp
            selected -> 16.dp
            else -> height / 2
        },
        YoinMotion.fastSpatialSpec(),
        label = "corner",
    )
    // A press blooms the accent (tertiaryContainer) through the button before it settles
    // on primaryContainer, so the bar answers in the theme's second colour too.
    val press by animateFloatAsState(if (pressed) 1f else 0f, YoinMotion.fastEffectsSpec(), label = "pressTint")
    val colors = MaterialTheme.colorScheme
    val container = lerp(lerp(colors.surfaceContainerHighest, colors.primaryContainer, sel), colors.tertiaryContainer, press)
    val content = lerp(lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, sel), colors.onTertiaryContainer, press)
    val frame = Modifier
        .clip(RoundedCornerShape(corner))
        .background(container)
        .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
    val icon: @Composable () -> Unit = {
            Box {
                Icon(
                    if (selected) tab.selectedIcon else tab.icon,
                    contentDescription = tab.label,
                    tint = content,
                    modifier = Modifier.size(24.dp),
                )
                val dot by animateFloatAsState(if (badge) 1f else 0f, YoinMotion.fastSpatialSpec(), label = "badge")
                if (dot > 0.01f) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-1).dp)
                            .size(8.dp)
                            .graphicsLayer {
                                scaleX = dot
                                scaleY = dot
                            }
                            .background(colors.error, CircleShape),
                    )
                }
            }
    }
    if (vertical) {
        Column(
            modifier = frame
                .width(height)
                .padding(vertical = 14.dp + extra / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon()
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.fastSpatialSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
            ) {
                Text(tab.label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
            }
        }
        return
    }
    Row(
        modifier = frame
            .height(height)
            .padding(horizontal = 14.dp + extra / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.fastSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.fastSpatialSpec()),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(tab.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}
