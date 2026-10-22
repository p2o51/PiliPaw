package com.gpo.pilipala.feature.listen

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

/**
 * Two fingers pinching the inline picture in: 仅听声音. The picture shrinks toward the listen
 * page's cover with the fingers ([update]: 0 untouched, 1 closed), and the release either
 * goes on into the listen page ([end] true) or springs back. The video page provides it.
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
 * The pinch on the picture. It claims the gesture only once a second finger is down (on the
 * Initial pass, consuming it from then on, so the one-finger drags and taps beneath see it
 * gone); one finger alone is never touched. [enabled]: the inline picture, unlocked.
 */
@Composable
internal fun Modifier.pinchToListen(enabled: () -> Boolean): Modifier {
    val pinch by rememberUpdatedState(LocalPicturePinchIn.current)
    val latestEnabled by rememberUpdatedState(enabled)
    val haptics = LocalHapticFeedback.current
    return pointerInput(Unit) {
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
                        val target = pinch
                        if (target == null || !latestEnabled()) break
                        if (!target.start()) break
                        claimed = target
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
                            progress = ((1f - span / startSpan) / ClosedAt).coerceIn(0f, 1f)
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

/** How far along a release goes on into the listen page. */
private const val CommitAt = 0.35f
