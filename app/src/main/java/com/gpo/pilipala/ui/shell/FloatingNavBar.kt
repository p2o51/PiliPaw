package com.gpo.pilipala.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.graphicsLayer
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
) {
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
        // 继续播放: a floating window dragged over the bar swells it a little (letting go docks it).
        val hint by animateFloatAsState(
            if (com.gpo.pilipala.feature.sources.KeepPlaying.navHint) 1.04f else 1f,
            YoinMotion.fastSpatialSpec(),
            label = "navDockHint",
        )
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = hint
                    scaleY = hint
                }
                .seamBarSource(MaterialTheme.shapes.extraLarge, barColor),
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

@Composable
private fun NavButton(tab: NavTab, selected: Boolean, height: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
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
    Row(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 14.dp + extra / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
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
