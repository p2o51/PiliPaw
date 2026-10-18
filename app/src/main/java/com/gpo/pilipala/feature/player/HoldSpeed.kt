package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Hold to speed up, slide to set. A long press plays at 2x; while the finger stays down,
 * sliding up / down steps through the detents (one haptic tick each) and a pill in the
 * middle of the picture shows the speed with a small dial and a ladder of the detents.
 * Letting go returns to the speed it had.
 */
private val HoldDetents = listOf(1.5f, 2f, 3f, 4f)

/** Finger travel per detent. */
internal val HoldSpeedStep = 36.dp

/** The detents above the current speed (holding never slows a video down). */
internal fun holdRates(base: Float): List<Float> = HoldDetents.filter { it > base + 0.01f }.ifEmpty { listOf(HoldDetents.last()) }

/** Where a hold starts: 2x, or the first detent above it when the video already plays at 2x or more. */
internal fun holdStart(rates: List<Float>): Int = rates.indexOfFirst { it >= 2f }.takeIf { it >= 0 } ?: rates.lastIndex

/**
 * Plays at [rate] for as long as the boost lasts. Rides on the controller's boost (which
 * restores the normal speed on [PiliPlayerController.endBoost]); only the rate differs.
 */
internal fun PiliPlayerController.holdAt(rate: Float) {
    if (!boosting) beginBoost()
    player?.setPlaybackSpeed(rate)
}

internal fun holdLabel(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${String.format(Locale.US, "%.2f", v).trimEnd('0')}x"

/** How a press on the picture went before the long-press timeout. */
internal sealed interface Press {
    /** Lifted without moving: a tap (the tap detector has it). */
    data object Released : Press

    /** Taken by someone else (a scrolling parent) or cancelled. */
    data object Gone : Press

    /** Moved past the touch slop: a drag starts from [change] with [overSlop] already travelled. */
    class Moved(val change: PointerInputChange, val overSlop: Offset) : Press
}

/**
 * Follows [pointer] until it lifts, is taken, or moves past [slop]. [drags] false never
 * reports a move (a locked player). [horizontalOnly] (the minimal chrome inside a vertical
 * pager): only a sideways move is a drag, a vertical one belongs to the pager. A move that
 * reaches the slop is consumed here, which cancels the tap detector's tap.
 */
internal suspend fun AwaitPointerEventScope.awaitPress(
    pointer: PointerId,
    slop: Float,
    drags: Boolean,
    horizontalOnly: Boolean,
): Press {
    var total = Offset.Zero
    while (true) {
        val event = awaitPointerEvent()
        val c = event.changes.fastFirstOrNull { it.id == pointer } ?: return Press.Gone
        if (c.changedToUpIgnoreConsumed()) return if (c.isConsumed) Press.Gone else Press.Released
        if (c.isConsumed) return Press.Gone
        total += c.positionChangeIgnoreConsumed()
        if (drags) {
            if (horizontalOnly) {
                if (abs(total.x) > slop) {
                    c.consume()
                    return Press.Moved(c, Offset(total.x - sign(total.x) * slop, 0f))
                }
                if (abs(total.y) > slop) return Press.Gone
            } else {
                val d = total.getDistance()
                if (d > slop) {
                    c.consume()
                    return Press.Moved(c, total * ((d - slop) / d))
                }
            }
        }
        // Someone later in the pass (a scrolling parent) may have taken it.
        awaitPointerEvent(PointerEventPass.Final)
        if (c.isConsumed) return Press.Gone
    }
}

/** Swallows the rest of a press, so a long press that did nothing does not end as a tap. */
internal suspend fun AwaitPointerEventScope.consumeUntilRelease(pointer: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.fastForEach { it.consume() }
        val c = event.changes.fastFirstOrNull { it.id == pointer } ?: return
        if (!c.pressed) return
    }
}

/**
 * The hold itself, from the long press to the release: starts the boost at the start
 * detent, steps the detent every [step] px of vertical travel (up is faster, with a little
 * hysteresis so a finger resting on a boundary does not chatter) and ends the boost on
 * release, or when the gesture is cancelled. Every event is consumed, so neither the tap
 * detector nor a parent sees the hold.
 */
internal suspend fun AwaitPointerEventScope.holdSpeed(
    pointer: PointerId,
    step: Float,
    ctrl: PiliPlayerController,
    feedback: GestureFeedback,
    haptics: HapticFeedback,
) {
    val rates = holdRates(ctrl.speed)
    var index = holdStart(rates)
    ctrl.holdAt(rates[index])
    feedback.holdRates = rates
    feedback.holdIndex = index
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    var position = index.toFloat()
    try {
        while (true) {
            val event = awaitPointerEvent()
            val c = event.changes.fastFirstOrNull { it.id == pointer }
            val dy = c?.positionChangeIgnoreConsumed()?.y ?: 0f
            event.changes.fastForEach { it.consume() }
            if (c == null || !c.pressed) break
            position = (position - dy / step).coerceIn(-0.35f, rates.lastIndex + 0.35f)
            val next = when {
                position > index + 0.6f -> index + 1
                position < index - 0.6f -> index - 1
                else -> index
            }.coerceIn(0, rates.lastIndex)
            if (next != index) {
                index = next
                ctrl.holdAt(rates[index])
                feedback.holdIndex = index
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    } finally {
        feedback.holdIndex = -1
        if (ctrl.boosting) {
            ctrl.endBoost()
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        }
    }
}

/**
 * The pill in the middle of the picture while a hold lasts: a dial whose needle sits at the
 * speed, the speed itself (rolling up or down with the finger) and the ladder of detents,
 * fastest on top. While the faster picture buffers, the dial gives way to a loading shape.
 */
@Composable
internal fun HoldSpeedPill(ctrl: PiliPlayerController, feedback: GestureFeedback, big: Boolean, modifier: Modifier = Modifier) {
    val holding = feedback.holdIndex >= 0 && feedback.holdRates.isNotEmpty()
    AnimatedVisibility(
        visible = holding,
        enter = fadeIn(YoinMotion.fastEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), initialScale = 0.7f),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.85f),
        modifier = modifier,
    ) {
        // Keep the last values while it leaves.
        val last = remember { HoldSnapshot() }
        if (holding) {
            last.rates = feedback.holdRates
            last.index = feedback.holdIndex.coerceIn(0, feedback.holdRates.lastIndex)
        }
        val rates = last.rates
        val index = last.index
        val rate = rates.getOrElse(index) { 2f }
        Surface(
            shape = YoinShapeTokens.Full,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                Modifier.padding(start = if (big) 16.dp else 14.dp, end = if (big) 14.dp else 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val glyph = if (big) 32.dp else 28.dp
                val buffering = ctrl.isBuffering && ctrl.firstFrameRendered
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = buffering,
                    transitionSpec = {
                        (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    contentAlignment = Alignment.Center,
                    label = "holdGlyph",
                ) { b ->
                    if (b) {
                        LoadingIndicator(Modifier.size(glyph), color = LocalContentColor.current)
                    } else {
                        SpeedDial(rate = rate, modifier = Modifier.size(glyph))
                    }
                }
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = rate,
                    transitionSpec = {
                        // Faster rolls up (the finger went up), slower rolls down.
                        val up = targetState > initialState
                        (slideInVertically(motion.fastSpatialSpec()) { if (up) it else -it } + fadeIn(motion.fastEffectsSpec())) togetherWith
                            (slideOutVertically(motion.fastSpatialSpec()) { if (up) -it else it } + fadeOut(motion.fastEffectsSpec()))
                    },
                    contentAlignment = Alignment.Center,
                    label = "holdRate",
                ) { r ->
                    Text(
                        holdLabel(r),
                        style = (if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).withTabularFigures(),
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = if (big) 52.dp else 44.dp),
                    )
                }
                if (rates.size > 1) {
                    Spacer(Modifier.width(10.dp))
                    DetentLadder(count = rates.size, index = index)
                }
            }
        }
    }
}

private class HoldSnapshot {
    var rates: List<Float> = emptyList()
    var index: Int = 0
}

/**
 * The abstract speed glyph: a speedometer arc filled up to the speed (1x empty, 4x full)
 * with its needle, springing as the speed steps.
 */
@Composable
private fun SpeedDial(rate: Float, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val target = ((rate - 1f) / (HoldDetents.last() - 1f)).coerceIn(0.06f, 1f)
    val fraction by animateFloatAsState(target, YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), label = "holdDial")
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val inset = stroke / 2f + size.minDimension * 0.04f
        val arc = Size(size.width - inset * 2f, size.height - inset * 2f)
        val topLeft = Offset(inset, inset)
        val start = 135f
        val sweep = 270f
        val f = fraction.coerceIn(0f, 1.05f)
        drawArc(ink.copy(alpha = 0.32f), start, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(ink, start, sweep * f.coerceAtMost(1f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Math.toRadians((start + sweep * f).toDouble())
        val r = arc.width / 2f - stroke * 1.8f
        val tip = center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
        drawLine(ink, center, tip, strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(ink, radius = stroke * 1.15f, center = center)
    }
}

/** One mark per detent, fastest on top; the current one stretches into a pill. */
@Composable
private fun DetentLadder(count: Int, index: Int) {
    val ink = LocalContentColor.current
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (i in count - 1 downTo 0) {
            val on = i == index
            val h by animateDpAsState(if (on) 12.dp else 4.dp, YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), label = "detent")
            Box(
                Modifier
                    .width(5.dp)
                    .height(h)
                    .background(if (on) ink else ink.copy(alpha = 0.38f), YoinShapeTokens.Full),
            )
        }
    }
}
