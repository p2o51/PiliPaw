package com.gpo.pilipala.feature.listen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Two fingers between 仅听声音 and the picture: pinching the inline picture in flies it toward
 * the listen page's cover ([pinchToListen]); spreading them on the cover flies it back into the
 * picture ([pinchOutToVideo]). [update]: 0 untouched, 1 all the way; the release either goes on
 * ([end] true) or springs back. The video page (B站) or the player (YouTube) provides it.
 */
internal interface PicturePinchIn {
    /** Two fingers came down; false: nothing to pinch into, the gesture is left alone. */
    fun start(): Boolean
    fun update(progress: Float)
    fun end(commit: Boolean)
}

internal val LocalPicturePinchIn = staticCompositionLocalOf<PicturePinchIn?> { null }

/** The video page's end of the pinch, its listen overlay filling in [target] once composed. */
internal class PinchToListenHost : PicturePinchIn {
    var target: PicturePinchIn? = null
    override fun start(): Boolean = target?.start() == true
    override fun update(progress: Float) {
        target?.update(progress)
    }

    override fun end(commit: Boolean) {
        target?.end(commit)
    }
}

/**
 * Moves a flight's progress with the fingers on one coroutine (the latest value only, however
 * many pointer events arrive), and runs the release after the last of them has landed.
 */
internal class PinchDriver(private val scope: CoroutineScope, private val value: Animatable<Float, AnimationVector1D>) {
    private var channel: Channel<Float>? = null
    private var job: Job? = null

    fun begin() {
        channel?.close()
        val ch = Channel<Float>(Channel.CONFLATED)
        channel = ch
        job = scope.launch { for (v in ch) value.snapTo(v) }
    }

    fun drive(v: Float) {
        channel?.trySend(v)
    }

    fun finish(then: suspend () -> Unit) {
        val j = job
        channel?.close()
        channel = null
        job = null
        scope.launch {
            j?.join()
            then()
        }
    }
}

/**
 * The pinch on the picture. It claims the gesture only once a second finger is down (on the
 * Initial pass, consuming it from then on, so the one-finger drags and taps beneath see it
 * gone); one finger alone is never touched. [enabled]: the inline picture, unlocked.
 */
@Composable
internal fun Modifier.pinchToListen(enabled: () -> Boolean, target: PicturePinchIn? = LocalPicturePinchIn.current): Modifier =
    twoFingerPinch(target, enabled, outward = false)

/** Spreading two fingers on the listen page's cover: 显示画面 (see [PicturePinchIn]). */
@Composable
internal fun Modifier.pinchOutToVideo(target: PicturePinchIn?): Modifier =
    if (target == null) this else twoFingerPinch(target, { true }, outward = true)

@Composable
private fun Modifier.twoFingerPinch(target: PicturePinchIn?, enabled: () -> Boolean, outward: Boolean): Modifier {
    val pinch by rememberUpdatedState(target)
    val latestEnabled by rememberUpdatedState(enabled)
    val haptics = LocalHapticFeedback.current
    return pointerInput(outward) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var claimed: PicturePinchIn? = null
            var startSpan = 0f
            var progress = 0f
            var crossed = false
            var event = currentEvent
            var commit = false
            try {
                while (true) {
                    var down = 0
                    event.changes.fastForEach { if (it.pressed) down++ }
                    if (down == 0) break
                    if (claimed == null && down >= 2) {
                        val t = pinch
                        if (t == null || !latestEnabled()) break
                        if (!t.start()) break
                        claimed = t
                    }
                    val c = claimed
                    if (c != null) {
                        event.changes.fastForEach { it.consume() }
                        // A finger lifted: the pinch ends where it got to.
                        if (down < 2) {
                            commit = progress >= CommitAt
                            break
                        }
                        // The spread is measured from the first event that has one (the second
                        // finger's own down can report none yet).
                        val span = spread(event.changes)
                        if (startSpan <= 1f) {
                            startSpan = span
                        } else {
                            progress = pinchProgress(startSpan, span, outward)
                            if (crossed != (progress >= CommitAt)) {
                                crossed = progress >= CommitAt
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            c.update(progress)
                        }
                    }
                    event = awaitPointerEvent(PointerEventPass.Initial)
                }
            } finally {
                claimed?.let {
                    if (commit) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    it.end(commit)
                }
            }
        }
    }
}

/**
 * How far the fingers have gone (0..1): in, the share of the spread lost against [ClosedAt];
 * out, the share gained against [OpenedAt].
 */
internal fun pinchProgress(startSpan: Float, span: Float, outward: Boolean): Float {
    if (startSpan <= 1f) return 0f
    val p = if (outward) (span / startSpan - 1f) / OpenedAt else (1f - span / startSpan) / ClosedAt
    return p.coerceIn(0f, 1f)
}

/** The pressed fingers' mean distance from their centre (px). */
private fun spread(changes: List<PointerInputChange>): Float {
    var n = 0
    var cx = 0f
    var cy = 0f
    changes.fastForEach {
        if (it.pressed) {
            n++
            cx += it.position.x
            cy += it.position.y
        }
    }
    if (n < 2) return 0f
    cx /= n
    cy /= n
    var sum = 0f
    changes.fastForEach { if (it.pressed) sum += (it.position - Offset(cx, cy)).getDistance() }
    return sum / n
}

/** The fingers' spread lost (a share of where it began) for the picture to reach the cover. */
private const val ClosedAt = 0.6f

/** The spread gained (a share of where it began) for the cover to reach the picture. */
private const val OpenedAt = 1.2f

/** How far along a release goes on (into the listen page, or back to the picture). */
private const val CommitAt = 0.35f
