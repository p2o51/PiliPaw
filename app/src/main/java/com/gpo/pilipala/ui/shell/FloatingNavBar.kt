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
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Bar height + margins the content must clear (portrait: 68 + 16 + 8; centred: 60 + 24 + 8). */
@Composable
fun floatingBarInset(): Dp = if (widthClass() == WidthClass.Compact) 92.dp else 92.dp

/**
 * Yoin's floating Button Group, rebuilt for four tabs: a pill surface
 * (surfaceContainerHigh, no shadow) holding connected buttons. The selected
 * one widens with a label on primaryContainer; pressing a button pushes its
 * neighbours (shape-morph feel) — all with the fast spatial spring.
 * Medium+ windows center the bar and cap it at 600dp.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(rowHeight)
                    .padding(horizontal = 10.dp),
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
                    )
                }
            }
        }
    }
}

@Composable
private fun NavButton(tab: NavTab, selected: Boolean, height: Dp, onClick: () -> Unit) {
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
    val colors = MaterialTheme.colorScheme
    val container = lerp(colors.surfaceContainerHighest, colors.primaryContainer, sel)
    val content = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, sel)
    Row(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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
