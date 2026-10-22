package com.gpo.pilipala.feature.listen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.video.drawTripleBloom
import com.gpo.pilipala.feature.video.drawTripleSparks
import com.gpo.pilipala.feature.video.rememberTripleBurst
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.rememberRichHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * The listen page's 点赞, holding a 三连 the way the detail page's action row does: a tap
 * toggles; a hold past the long-press timeout charges (the button fills with primary under a
 * rising hum) and [onCharged] fires once it is full. Letting go early drains it and does
 * nothing. Each bump of [celebration] plays the burst around the button.
 */
@Composable
internal fun ChargeLikeButton(
    checked: Boolean,
    icon: ImageVector,
    checkedIcon: ImageVector,
    label: String,
    checkedLabel: String,
    onClick: () -> Unit,
    onCharged: () -> Unit,
    celebration: Int,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val rich = rememberRichHaptics()
    val interaction = remember { MutableInteractionSource() }
    val charge = remember { Animatable(0f) }
    var consumed by remember { mutableStateOf(false) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val latestCharged by rememberUpdatedState(onCharged)
    val drain = YoinMotion.fastSpatialSpec<Float>()
    val settle = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Expressive)
    DisposableEffect(Unit) { onDispose { rich.cancel() } }
    LaunchedEffect(interaction) {
        var timer: Job? = null
        var charging = false
        var full = false
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    timer?.cancel()
                    consumed = false
                    charging = false
                    full = false
                    timer = launch {
                        delay(longPressMs)
                        consumed = true
                        charging = true
                        rich.charge()
                        val start = withFrameNanos { it }
                        var t = 0f
                        while (t < 1f) {
                            t = ((withFrameNanos { it } - start) / 1_000_000f / ChargeMs).coerceAtMost(1f)
                            charge.snapTo(t.pow(1.6f))
                        }
                        full = true
                        charging = false
                        rich.chargeFull()
                        latestCharged()
                        this@LaunchedEffect.launch {
                            delay(90)
                            charge.animateTo(0f, settle)
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    timer?.cancel()
                    timer = null
                    if (charging && !full) {
                        charging = false
                        rich.cancel()
                        launch { charge.animateTo(0f, drain) }
                    }
                }
            }
        }
    }
    val burst = rememberTripleBurst(celebration, count = 1)
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.tertiary
    val rest by animateColorAsState(if (checked) scheme.primary else scheme.secondaryContainer, YoinMotion.defaultEffectsSpec(), label = "likeContainer")
    val restContent by animateColorAsState(if (checked) scheme.onPrimary else scheme.onSecondaryContainer, YoinMotion.defaultEffectsSpec(), label = "likeContent")
    Box(
        modifier.drawBehind {
            val r = size.height / 2f
            drawTripleBloom(burst, 0, center, radius = r + 12.dp.toPx(), color = accent)
            drawTripleSparks(burst, 0, center, from = r + 4.dp.toPx(), to = r + 22.dp.toPx(), dot = 3.dp.toPx(), color = accent)
        },
        contentAlignment = Alignment.Center,
    ) {
        val c = charge.value
        FilledTonalIconButton(
            onClick = {
                if (consumed) {
                    consumed = false
                } else {
                    haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onClick()
                }
            },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = lerp(rest, scheme.primary, c),
                contentColor = lerp(restContent, scheme.onPrimary, c),
            ),
            interactionSource = interaction,
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    val s = 1f + 0.08f * c + 0.3f * (burst.pop[0].value - 1f)
                    scaleX = s
                    scaleY = s
                },
        ) {
            Icon(if (checked) checkedIcon else icon, contentDescription = if (checked) checkedLabel else label)
        }
    }
}

/** How long the finger holds, past the long-press timeout, to fill the charge (as ActionGroup's). */
private const val ChargeMs = 620f
