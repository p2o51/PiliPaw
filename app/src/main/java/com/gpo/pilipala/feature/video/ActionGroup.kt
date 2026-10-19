package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.rememberRichHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

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
 * The 三连 gesture over a run of the group's slots (点赞 投币 收藏). With it, holding the
 * first slot no longer fires its [ActionSpec.onLongClick] at the long-press timeout: a
 * charge starts there and flows across the run (an accent cookie blooming behind each
 * glyph in turn, a rising hum under the finger) and the long-click (the 三连 sheet, which
 * still asks before anything is sent) fires only when the charge is full. Letting go
 * early drains it and does nothing; a quick tap is still a tap.
 *
 * [celebration]: [ArchiveActions.tripleCelebration]; each bump plays the 三连 burst.
 */
@Immutable
data class TripleCharge(
    val slots: IntRange = 0..2,
    val celebration: Int = 0,
)

/** How long the finger holds, past the long-press timeout, to fill the charge. */
private const val ChargeMs = 620f

/** Set once the first detail page has shown the 三连 run its charge (see [TripleHint]). */
private val TripleHintShown = AppSettings.register("video_triple_hint_shown", false)

/** How much of the accent the hint's cookies carry: a glimmer, not the real charge. */
private const val HintAlpha = 0.28f

/**
 * The one-time nudge that 点赞 holds a 三连: the first time a detail page with the run
 * settles, the cookies behind 点赞 投币 收藏 swell in turn at [HintAlpha] and ebb back,
 * on springs. No text, no haptic, no action; never again after that.
 */
private object TripleHint {
    /** Claimed by the first group in this process, so two groups composed at once never both play. */
    var claimed = false
}

/**
 * M3 Expressive connected button group: leading / middle / trailing shapes,
 * press morph from the toggle buttons, checked state washing in with the
 * effects spring and the glyph swapping to its filled twin.
 */
@Composable
internal fun ConnectedActionGroup(items: List<ActionSpec>, modifier: Modifier = Modifier, triple: TripleCharge? = null) {
    val slots = triple?.slots
    val slotCount = slots?.let { it.last - it.first + 1 }?.coerceAtLeast(0) ?: 0
    val charge = remember { Animatable(0f) }
    val hint = remember { Animatable(0f) }
    val settled = LocalPageSettled.current
    val hintRise = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    val hintEbb = YoinMotion.slowEffectsSpec<Float>()
    // Only a group that can charge, on its own page (a negative celebration = a neighbour in a pager).
    val hintable = slotCount > 0 && (triple?.celebration ?: -1) >= 0 && items.getOrNull(slots?.first ?: -1)?.onLongClick != null
    LaunchedEffect(hintable, settled) {
        if (!hintable || !settled || TripleHint.claimed || TripleHintShown.value) return@LaunchedEffect
        TripleHint.claimed = true
        AppSettings.set(TripleHintShown, true)
        delay(700)
        hint.animateTo(1f, hintRise)
        delay(160)
        hint.animateTo(0f, hintEbb)
    }
    val burst = rememberTripleBurst(triple?.celebration ?: 0, count = slotCount.coerceAtLeast(1))
    // Where each slot of the run sits in the group, for the blooms and sparks drawn around them.
    val rects = remember(slotCount) { arrayOfNulls<Rect>(slotCount) }
    val accent = MaterialTheme.colorScheme.tertiary
    Box(modifier.fillMaxWidth()) {
        if (slotCount > 0) {
            // Behind the buttons: the blooms peek out around each one as it lands.
            Canvas(Modifier.matchParentSize()) {
                for (k in 0 until slotCount) {
                    val r = rects[k] ?: continue
                    drawTripleBloom(burst, k, r.center, radius = r.height * 0.5f + 12.dp.toPx(), color = accent)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            items.forEachIndexed { i, item ->
                val shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
                val k = if (slots != null && i in slots) i - slots.first else -1
                ActionToggle(
                    item = item,
                    shapes = shapes,
                    charge = if (slots != null && i == slots.first && item.onLongClick != null) charge else null,
                    // Overlapping thirds: each slot starts filling while the one before is half full.
                    fill = if (k >= 0) {
                        { (((charge.value - k * 0.25f) / 0.5f).coerceIn(0f, 1f)).coerceAtLeast(burst.bloomShown(k)) }
                    } else {
                        null
                    },
                    pop = if (k >= 0) { { burst.pop[k].value } } else null,
                    hint = if (k >= 0) {
                        { ((hint.value - k * 0.25f) / 0.5f).coerceIn(0f, 1f) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (k >= 0) {
                                Modifier.onPlaced { rects[k] = Rect(it.positionInParent(), it.size.toSize()) }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        if (slotCount > 0) {
            // In front: the sparks fly over the button edges.
            Canvas(Modifier.matchParentSize()) {
                for (k in 0 until slotCount) {
                    val r = rects[k] ?: continue
                    val glyph = Offset(r.center.x, r.top + GlyphCentreY.toPx())
                    drawTripleSparks(burst, k, glyph, from = 14.dp.toPx(), to = 40.dp.toPx(), dot = 2.5.dp.toPx(), color = accent)
                }
            }
        }
    }
}

/** The glyph's centre below the button's top edge (6dp padding + half the 22dp glyph). */
private val GlyphCentreY = 17.dp

@Composable
private fun ActionToggle(
    item: ActionSpec,
    shapes: ToggleButtonShapes,
    modifier: Modifier,
    charge: Animatable<Float, AnimationVector1D>? = null,
    fill: (() -> Float)? = null,
    pop: (() -> Float)? = null,
    hint: (() -> Float)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val rich = rememberRichHaptics()
    val interaction = remember { MutableInteractionSource() }
    var consumedByLongPress by remember { mutableStateOf(false) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val onLong by rememberUpdatedState(item.onLongClick)
    val drain = YoinMotion.fastSpatialSpec<Float>()
    val settle = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Expressive)
    DisposableEffect(charge) { onDispose { if (charge != null) rich.cancel() } }
    LaunchedEffect(interaction, charge) {
        // Explicit press bookkeeping: the long-press timer only survives while the
        // finger is still down (Release / Cancel kill it), so a slow or delayed tap
        // can never fire the long action.
        var timer: Job? = null
        var charging = false
        var full = false
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    timer?.cancel()
                    consumedByLongPress = false
                    charging = false
                    full = false
                    val cb = onLong
                    if (cb != null) {
                        timer = launch {
                            delay(longPressMs)
                            consumedByLongPress = true
                            if (charge == null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                cb()
                                return@launch
                            }
                            // Hold to charge: the fill follows the held time, slow at first and
                            // gathering pace (a gesture-driven value, like a drag; springs take
                            // over the moment the finger lets go or the charge lands).
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
                            cb()
                            // Drain in the collector's scope so a release can't cut the spring short.
                            this@LaunchedEffect.launch {
                                delay(90)
                                charge.animateTo(0f, settle)
                            }
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    timer?.cancel()
                    timer = null
                    if (charge != null && charging && !full) {
                        charging = false
                        rich.cancel()
                        launch { charge.animateTo(0f, drain) }
                    }
                }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        // primary / onPrimary like FollowToggle: reads as "done" even under low-chroma CoverThemes.
        if (item.checked) colors.primary else colors.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "actionContainer",
    )
    val content by animateColorAsState(
        if (item.checked) colors.onPrimary else colors.onSurfaceVariant,
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
            Box(
                Modifier
                    .size(22.dp)
                    .then(
                        if (pop != null) {
                            Modifier.graphicsLayer {
                                val s = pop()
                                scaleX = s
                                scaleY = s
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (fill != null) ChargeCookie(fill, hint)
                AnimatedContent(
                    targetState = item.checked,
                    transitionSpec = { (scaleIn(spatial, initialScale = 0.5f) + fadeIn(effects)) togetherWith fadeOut(effects) },
                    label = "actionIcon",
                ) { checked ->
                    val glyph = if (checked) item.checkedIcon else item.icon
                    Box(contentAlignment = Alignment.Center) {
                        Icon(glyph, contentDescription = null, modifier = Modifier.size(22.dp))
                        if (fill != null) {
                            // The glyph turns to the accent's on-colour exactly as the cookie covers it.
                            Icon(
                                glyph,
                                contentDescription = null,
                                tint = colors.onTertiary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { alpha = fill().coerceIn(0f, 1f) },
                            )
                        }
                    }
                }
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

/**
 * The accent cookie behind a glyph of the 三连 run: grows and turns with [fill]. While
 * nothing fills it, [hint] swells it the same way at [HintAlpha] (the one-time nudge).
 */
@Composable
private fun ChargeCookie(fill: () -> Float, hint: (() -> Float)? = null) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    Box(
        Modifier
            .requiredSize(36.dp)
            .graphicsLayer {
                val f = fill().coerceIn(0f, 1.2f)
                val h = if (f > 0.001f) 0f else (hint?.invoke() ?: 0f).coerceIn(0f, 1.2f)
                val s = if (f > 0.001f) f else h
                scaleX = s
                scaleY = s
                rotationZ = s * 40f
                alpha = when {
                    f > 0.001f -> 1f
                    h > 0.001f -> HintAlpha
                    else -> 0f
                }
            }
            .clip(cookie)
            .background(MaterialTheme.colorScheme.tertiary),
    )
}
