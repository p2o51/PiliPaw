package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/** What the picture's gestures are doing, for the layers that show it. */
@Stable
internal class GestureFeedback {
    /** Hold to speed up: the detents of the current hold and which one plays (-1: no hold). */
    var holdRates by mutableStateOf<List<Float>>(emptyList())
    var holdIndex by mutableIntStateOf(-1)

    /** Double tap on a side third: skip 10 s, quick follow-up taps add more. */
    val skip = DoubleTapSeek()
}

/**
 * The picture's gestures (PiliPaw's, without the drags): a tap goes to [onTap]; a double tap
 * on the left / right third skips 10 s back / forward and quick follow-up taps keep adding
 * ([DoubleTapSeek]), in the middle third it plays / pauses; holding still on a playing picture
 * speeds it up and sliding while held sets the speed ([holdSpeed]).
 *
 * Two detectors on the node, as in PiliPaw: the tap detector (outer) only does taps; the press
 * detector (inner, so it sees each event first) turns a press that stays put past the
 * long-press timeout into a hold, which consumes everything until release, so it never also
 * ends as a tap.
 */
internal fun Modifier.pictureGestures(
    feedback: GestureFeedback,
    haptics: HapticFeedback,
    onTap: () -> Unit,
): Modifier = this
    .pointerInput(feedback) {
        detectPlayerTaps(
            continues = { at ->
                // A follow-up tap of a running skip streak counts at once, on its side.
                val third = tapThird(at.x, size.width)
                if (feedback.skip.active() && third != 0) {
                    feedback.skip.step(third, at)
                    true
                } else {
                    false
                }
            },
            onTap = { onTap() },
            onDoubleTap = { at: Offset ->
                when (val third = tapThird(at.x, size.width)) {
                    0 -> Player.toggle()
                    else -> feedback.skip.step(third, at)
                }
            },
        )
    }
    .pointerInput(feedback) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitLeave(down.id, viewConfiguration.touchSlop)
            }
            if (press != null) return@awaitEachGesture
            // Still down and in place: a hold. Only a playing picture speeds up; a paused one
            // swallows the press so it does not end as a tap.
            if (Player.isPlaying) {
                holdSpeed(down.id, HoldSpeedStep.toPx(), feedback, haptics)
            } else {
                consumeUntilRelease(down.id)
            }
        }
    }
