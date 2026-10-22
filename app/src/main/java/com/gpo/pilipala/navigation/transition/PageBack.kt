package com.gpo.pilipala.navigation.transition

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.preferredFrameRate
import com.gpo.pilipala.ui.theme.BackMotionTokens
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The host's back: consumes the system gesture (and 3-button / a11y back)
 * for every pushed page. Compose it BEFORE the NavDisplay — handler priority
 * is registration order (LIFO), so every in-page BackHandler registered by a
 * page (fullscreen player, WebView history, login web pane, the shell's
 * tab → home) outranks this one while it is enabled.
 */
@Composable
internal fun PageBackHandler(transitions: PageTransitions, canGoBack: Boolean) {
    PredictiveBackHandler(enabled = canGoBack || transitions.phase != BackPhase.Idle) { events ->
        transitions.handleBack(events)
    }
}

/**
 * The closing page: AOSP CrossActivityBackAnimation pre-commit math on the
 * whole page stack (NavDisplay + shared-transition overlay). Every value is
 * read in the layer block, so gesture frames never recompose a page.
 */
internal fun Modifier.pageClosingTransform(t: PageTransitions): Modifier = graphicsLayer {
    when (t.phase) {
        BackPhase.Idle -> return@graphicsLayer
        BackPhase.Revealing -> {
            alpha = t.reveal.value
            return@graphicsLayer
        }
        else -> Unit
    }
    val p = t.closing.value
    val pose = closingPose(t, size)
    scaleX = pose.scale
    scaleY = pose.scale
    translationX = pose.tx
    translationY = pose.ty

    // Out in the first tenth of the ride (~45 ms): the page and the list beneath, riding home,
    // never stand half over each other for long (a double exposure read as a jolt).
    alpha = max(1f - 10f * t.exit.value, 0f)
    shape = RoundedCornerShape(BackMotionTokens.PopPageCornerRadius * p)
    clip = p > 0f
}

/**
 * The page a back reveals, as it was last drawn: parked at −96dp, scaling in
 * sync with the closing page and following the finger vertically; on commit
 * it rides home on EMPHASIZED while the closing page dissolves.
 *
 * [over] is the same pixels drawn ABOVE the closing page during the commit,
 * at alpha min(10t, 1) — the complement of the closing page's max(1 − 10t, 0).
 * A video page's SurfaceView ignores layer alpha (its hole punch stays clear
 * through the fade), so without this cover its picture would float over the
 * revealed page until the pop; opaque window pixels drawn above bury it.
 * [fill] stands in when there is no snapshot. It is drawn again, whole and still, while the
 * live page draws hidden after the pop ([PageTransitions.holding]): the page that left may
 * still be composed for a frame, its SurfaceView hole open whatever its alpha.
 */
@Composable
internal fun BeneathPage(t: PageTransitions, fill: Color, over: Boolean = false) {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (t.phase == BackPhase.Idle) return@graphicsLayer
                if (over) {
                    if (t.phase == BackPhase.Revealing) {
                        // Holding: the pixels at rest, opaque (beneathPose is at rest by now).
                        alpha = if (t.holding) 1f else 0f
                    } else {
                        if (t.phase != BackPhase.Committing) return@graphicsLayer
                        alpha = min(10f * t.exit.value, 1f)
                    }
                }
                val pose = beneathPose(t, size)
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.tx
                translationY = pose.ty
            }
            .drawBehind {
                if (t.phase == BackPhase.Idle) return@drawBehind
                if (over && !t.holding && (t.phase != BackPhase.Committing || t.exit.value <= 0f)) return@drawBehind
                val snap = t.beneath
                if (snap == null) {
                    if (over) drawRect(fill)
                    return@drawBehind
                }
                // A captured video page has a hole where its SurfaceView was: the player's black.
                drawRect(Color.Black)
                drawImage(snap.image)
            },
    )
}

/** A page's pose during a back: scale about its centre, then these offsets (px). */
internal class BackPose(val scale: Float, val tx: Float, val ty: Float)

/** The closing page's pose: AOSP CrossActivityBackAnimation pre-commit math. */
internal fun Density.closingPose(t: PageTransitions, size: Size): BackPose {
    val p = t.closing.value
    val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
    val marginPx = BackMotionTokens.DisplayBoundsMargin.toPx()
    // LEFT-edge swipes park the scaled page's right edge 8dp from the screen
    // edge; RIGHT-edge swipes stay centred (AOSP asymmetry).
    val dxTarget = if (t.swipeEdge == BackEventCompat.EDGE_LEFT) {
        size.width * (1f - BackMotionTokens.PopPageScaleTarget) / 2f - marginPx
    } else {
        0f
    }
    return BackPose(scale, dxTarget * p, verticalFollow(t.touchYDelta, size.height, scale, marginPx))
}

/** The revealed page's pose: parked at −96dp, scaling with the gesture, riding home on commit. */
internal fun Density.beneathPose(t: PageTransitions, size: Size): BackPose {
    val b = t.behind.value
    val p = if (t.phase == BackPhase.Gesture) t.closing.value else t.seedProgress
    val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p * b
    val ty = if (t.phase == BackPhase.Gesture || t.phase == BackPhase.Committing) t.touchYDelta else 0f
    return BackPose(
        scale,
        -BackMotionTokens.EnteringStartOffset.toPx() * b,
        verticalFollow(ty, size.height, scale, BackMotionTokens.DisplayBoundsMargin.toPx()) * b,
    )
}

/** Swallows touches while a back runs: neither the closing page nor the pixels beneath are live. */
@Composable
internal fun BackInputBlocker(t: PageTransitions) {
    if (t.phase == BackPhase.Idle) return
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** ARR: no touch boost after release, so settles would pace at 60Hz on the 120Hz panel without a vote. */
internal fun Modifier.voteHighFrameRate(active: Boolean): Modifier =
    preferredFrameRate(if (active) FrameRateCategory.High else FrameRateCategory.Default)

/**
 * AOSP getYOffset: decelerated ratio of the raw finger travel (capped at half
 * the page), scaled to the slack the shrunken page has before the 8dp margin.
 */
private fun verticalFollow(rawDy: Float, height: Float, scale: Float, marginPx: Float): Float {
    if (rawDy == 0f || height <= 0f) return 0f
    val halfH = height / 2f
    val ratio = min(halfH, abs(rawDy)) / halfH
    val decelerated = 1f - (1f - ratio) * (1f - ratio)
    val maxShift = max(0f, (height - height * scale) / 2f - marginPx)
    return maxShift * decelerated * (if (rawDy < 0f) -1f else 1f)
}
