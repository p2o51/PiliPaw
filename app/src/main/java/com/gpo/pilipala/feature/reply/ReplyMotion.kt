package com.gpo.pilipala.feature.reply

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Yoin spring specs captured in composition so non-composable transition
 * lambdas can use them. Comments are a reading surface: fades ride the
 * effects spring and everything that moves settles critically damped, so
 * nothing overshoots or draws the eye while reading.
 */
@Immutable
internal class ReplyMotionSpecs(
    val fade: FiniteAnimationSpec<Float>,
    /** The outgoing side of a cross-fade: clears a little quicker than the incoming one arrives. */
    val fadeOut: FiniteAnimationSpec<Float>,
    /** The composer's own controls (send button swap); not used on the reading surface. */
    val scale: FiniteAnimationSpec<Float>,
    /** Short moves: list placement, the like count's digits. */
    val offset: FiniteAnimationSpec<IntOffset>,
    val size: FiniteAnimationSpec<IntSize>,
    /** The list ⇄ thread step inside one pane. */
    val pane: FiniteAnimationSpec<IntOffset>,
)

@Composable
internal fun replyMotion(): ReplyMotionSpecs = ReplyMotionSpecs(
    fade = YoinMotion.defaultEffectsSpec(),
    fadeOut = YoinMotion.fastEffectsSpec(),
    scale = YoinMotion.fastSpatialSpec(),
    offset = settledFastSpatialSpec(),
    size = settledFastSpatialSpec(),
    pane = settledSpatialSpec(),
)

internal fun ReplyMotionSpecs.crossfade(): ContentTransform = fadeIn(fade) togetherWith fadeOut(fadeOut)

/**
 * A comment surface's item animation: rows that land (a page, a sort switch,
 * the reply just sent) only fade, on the effects spring; no lift, no stagger.
 * Placement rides [placement] (the gated, settled spring).
 */
internal fun LazyItemScope.replyItemMotion(
    motion: ReplyMotionSpecs,
    placement: FiniteAnimationSpec<IntOffset>,
): Modifier = Modifier.animateItem(fadeInSpec = motion.fade, placementSpec = placement, fadeOutSpec = motion.fade)

/** The default spatial spring at its own stiffness, critically damped (no overshoot). */
@Composable
internal fun <T> settledSpatialSpec(): FiniteAnimationSpec<T> = YoinMotion.defaultSpatialSpec<T>().settled()

/** The fast spatial spring at its own stiffness, critically damped (no overshoot). */
@Composable
internal fun <T> settledFastSpatialSpec(): FiniteAnimationSpec<T> = YoinMotion.fastSpatialSpec<T>().settled()

private fun <T> FiniteAnimationSpec<T>.settled(): FiniteAnimationSpec<T> {
    val base = this as? SpringSpec<T> ?: return this
    return spring(Spring.DampingRatioNoBouncy, base.stiffness, base.visibilityThreshold)
}

// ── List ⇄ thread (Medium / Expanded) ───────────────────────────────────────

/** M3 shared-axis travel: short, so the pane reads as stepping in rather than paging. */
internal val SharedAxisDistance = 30.dp

/**
 * Shared axis X between the comment list and a thread open in the same
 * pane: both sides travel [distancePx] the same way while cross-fading.
 * [forward] = into the thread.
 */
internal fun ReplyMotionSpecs.sharedAxisX(forward: Boolean, distancePx: Int): ContentTransform {
    val sign = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(pane) { sign * distancePx } + fadeIn(fade),
        initialContentExit = slideOutHorizontally(pane) { -sign * distancePx } + fadeOut(fadeOut),
        // Both levels fill the pane: no size animation.
        sizeTransform = null,
    )
}

// ── The reply just sent ─────────────────────────────────────────────────────

/**
 * The only row entrance left: the reply the composer just sent grows in.
 * Identity matters: a lazy slot reused for another row gets a new instance,
 * so its animation restarts instead of inheriting one that finished.
 * [fade]: false where the list's own item fade already covers the row.
 */
@Immutable
internal class Entrance(val fade: Boolean)

/**
 * Remembers the reply the composer just sent (kept in the ViewModel, so it
 * is claimed once). Pages landing do not animate their rows beyond the
 * list's plain item fade.
 */
internal class ReplyEntrance {
    var sent: Long = 0L

    /** A lazy row: only the reply just sent grows in (its fade is the list's item fade). */
    fun claim(rpid: Long): Entrance? {
        if (rpid == 0L || rpid != sent) return null
        sent = 0L
        return Entrance(fade = false)
    }

    /** A sub-reply line of a preview block: only the one just sent animates. */
    fun claimSent(rpid: Long): Boolean {
        if (rpid == 0L || rpid != sent) return false
        sent = 0L
        return true
    }
}

/**
 * Counts rows whose height is animating (a text expanding, a reply expanding
 * in). While any is, list placement springs are skipped ([gated]): rows below
 * follow the growing row exactly instead of lagging into its new lines.
 */
@Stable
internal class ReplyLayoutMotion {
    var resizing by mutableIntStateOf(0)
        private set

    fun begin() {
        resizing++
    }

    fun end() {
        resizing = (resizing - 1).coerceAtLeast(0)
    }

    /** [spec] normally; a snap while a row resizes. Read when a placement animation starts, never in composition. */
    fun gated(spec: FiniteAnimationSpec<IntOffset>): FiniteAnimationSpec<IntOffset> = GatedPlacementSpec(this, spec)
}

private class GatedPlacementSpec(
    private val motion: ReplyLayoutMotion,
    private val spec: FiniteAnimationSpec<IntOffset>,
) : FiniteAnimationSpec<IntOffset> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<IntOffset, V>): VectorizedFiniteAnimationSpec<V> =
        if (motion.resizing > 0) snap<IntOffset>().vectorize(converter) else spec.vectorize(converter)
}

/** Applies a row's [Entrance]; null leaves it as is. */
@Composable
internal fun Modifier.entrance(entrance: Entrance?, layout: ReplyLayoutMotion?): Modifier =
    if (entrance == null) this else this.then(expandIn(entrance, layout))

/**
 * Grows from zero height (top anchored, clipped) on the settled spatial
 * spring, fading in on the effects spring when asked; reads only in
 * layout / draw.
 */
@Composable
private fun expandIn(expand: Entrance, layout: ReplyLayoutMotion?): Modifier {
    val grow = remember(expand) { Animatable(0f) }
    val alpha = remember(expand) { Animatable(if (expand.fade) 0f else 1f) }
    val spatial = settledSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(expand) {
        layout?.begin()
        try {
            coroutineScope {
                if (expand.fade) launch { alpha.animateTo(1f, effects) }
                grow.animateTo(1f, spatial)
            }
        } finally {
            layout?.end()
        }
    }
    return Modifier
        .graphicsLayer {
            clip = true
            this.alpha = alpha.value
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (placeable.height * grow.value.coerceIn(0f, 1f)).roundToInt()
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}
