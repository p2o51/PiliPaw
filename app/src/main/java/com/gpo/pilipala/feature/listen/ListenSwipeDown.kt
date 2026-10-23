package com.gpo.pilipala.feature.listen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * A swipe down on the listen page takes it where 收起 goes, with the finger, as the video page's
 * swipe down on its picture does: B站's page closes under the finger (into the mini bar with
 * 继续播放), YouTube's player folds down into its bar. The part playing provides it.
 */
internal interface ListenSwipeDown {
    /** The swipe began; false: nothing to leave to, the drag is ignored. */
    fun start(): Boolean
    fun drag(dy: Float)

    /** Released after [dy] px at [vy] px/s (0, 0: cancelled). */
    fun end(dy: Float, vy: Float)
}

/**
 * The page's one-finger drag down. It starts only on a drag that first goes down, and only where
 * nothing beneath took the drag (the lyrics' scroll, the seek bar, a sheet's handle keep theirs);
 * a drag up, or one cut short, is left alone. Two fingers belong to the cover's spread.
 */
@Composable
internal fun Modifier.listenSwipeDown(target: ListenSwipeDown?): Modifier {
    if (target == null) return this
    val latest by rememberUpdatedState(target)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var over = 0f
            val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, o ->
                if (o > 0f) {
                    change.consume()
                    over = o
                }
            } ?: return@awaitEachGesture
            if (currentEvent.changes.count { it.pressed } > 1) return@awaitEachGesture
            val swipe = latest
            if (!swipe.start()) return@awaitEachGesture
            val velocity = VelocityTracker()
            var travel = over
            velocity.addPosition(slopChange.uptimeMillis, Offset(0f, travel))
            swipe.drag(travel)
            var ended = false
            try {
                ended = verticalDrag(slopChange.id) { c ->
                    // A second finger: the spread is the cover's, this swipe lets go.
                    if (currentEvent.changes.count { it.pressed } > 1) throw SecondFinger()
                    travel += c.position.y - c.previousPosition.y
                    c.consume()
                    velocity.addPosition(c.uptimeMillis, Offset(0f, travel))
                    swipe.drag(travel)
                }
            } catch (_: SecondFinger) {
                ended = false
            } finally {
                if (ended) swipe.end(travel, velocity.calculateVelocity().y) else swipe.end(0f, 0f)
            }
        }
    }
}

private class SecondFinger : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
