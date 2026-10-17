package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/** One slot of the connected action group. [toggle] = false for plain actions (分享). */
@Immutable
data class ActionSpec(
    val label: String,
    val icon: ImageVector,
    val checkedIcon: ImageVector = icon,
    val checked: Boolean = false,
    val toggle: Boolean = true,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * M3 Expressive connected button group: leading / middle / trailing shapes,
 * press morph from the toggle buttons, checked state washing in with the
 * effects spring and the glyph swapping to its filled twin.
 */
@Composable
internal fun ConnectedActionGroup(items: List<ActionSpec>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { i, item ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ActionToggle(item, shapes, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionToggle(item: ActionSpec, shapes: ToggleButtonShapes, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var consumedByLongPress by remember { mutableStateOf(false) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val onLong by rememberUpdatedState(item.onLongClick)
    LaunchedEffect(interaction) {
        // Explicit press bookkeeping: the long-press timer only survives while the
        // finger is still down (Release / Cancel kill it), so a slow or delayed tap
        // can never fire the long action.
        var timer: kotlinx.coroutines.Job? = null
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    timer?.cancel()
                    consumedByLongPress = false
                    val cb = onLong
                    if (cb != null) {
                        timer = launch {
                            delay(longPressMs)
                            consumedByLongPress = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            cb()
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    timer?.cancel()
                    timer = null
                }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (item.checked) colors.primaryContainer else colors.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "actionContainer",
    )
    val content by animateColorAsState(
        if (item.checked) colors.onPrimaryContainer else colors.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "actionContent",
    )
    ToggleButton(
        checked = item.checked,
        onCheckedChange = {
            if (consumedByLongPress) {
                consumedByLongPress = false
            } else {
                if (item.toggle) {
                    haptic.performHapticFeedback(if (item.checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
                item.onClick()
            }
        },
        modifier = modifier.height(64.dp),
        shapes = shapes,
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = container,
            contentColor = content,
            checkedContainerColor = container,
            checkedContentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        interactionSource = interaction,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val spatial = YoinMotion.fastSpatialSpec<Float>()
            val effects = YoinMotion.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = item.checked,
                transitionSpec = { (scaleIn(spatial, initialScale = 0.5f) + fadeIn(effects)) togetherWith fadeOut(effects) },
                label = "actionIcon",
            ) { checked ->
                Icon(if (checked) item.checkedIcon else item.icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(3.dp))
            AnimatedContent(
                targetState = item.label,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                label = "actionLabel",
            ) { label ->
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
