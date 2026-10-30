package com.gpo.pilipala.feature.reply

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.gpo.pilipala.ui.theme.MorphShape
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

// ── The comment a notification points at ────────────────────────────────────

/**
 * Marks the comment a notification opened the comments for: a surfaceContainerHighest
 * block behind the row, and when [full] (the comment itself, not the thread around it)
 * the avatar turning into a cookie (the home avatar's shape) and a flowing wave under the
 * name (the listen scrubber's wave). Drawn and layered only: nothing in the row moves.
 */
@Stable
internal class AnchorMark(val full: Boolean) {
    /** 0 … 1: the wash, the cookie, the wave's ink. */
    val presence = Animatable(0f)

    /** The wave's travel, in wavelengths. */
    val phase = Animatable(0f)

    /** Eases in, holds briefly while the comment is read, then eases out. */
    suspend fun play() = coroutineScope {
        val flow = launch {
            phase.animateTo(1f, infiniteRepeatable(tween(AnchorWavePeriodMs, easing = LinearEasing)))
        }
        show()
        delay(AnchorHoldMs)
        hide()
        flow.cancel()
    }

    suspend fun show() {
        presence.animateTo(1f, tween(AnchorInMs, easing = EaseOutCubic))
    }

    suspend fun hide() {
        presence.animateTo(0f, tween(AnchorOutMs, easing = EaseInOutCubic))
    }
}

@Composable
internal fun rememberAnchorMark(full: Boolean = true): AnchorMark = remember(full) { AnchorMark(full) }

/**
 * The wash behind a marked row: a surfaceContainerHighest rounded block, faded in by
 * [AnchorMark.presence]. Opaque once shown: the row's labels (outline, onSurfaceVariant) are
 * set against the surface roles, and a translucent container would leave them on a tone no
 * role pairs with.
 */
@Composable
internal fun Modifier.anchorWash(mark: AnchorMark?): Modifier {
    if (mark == null) return this
    val tint = MaterialTheme.colorScheme.surfaceContainerHighest
    return drawBehind {
        val p = mark.presence.value
        if (p <= 0f) return@drawBehind
        val inset = 4.dp.toPx()
        drawRoundRect(
            color = tint.copy(alpha = tint.alpha * p.coerceIn(0f, 1f)),
            topLeft = Offset(inset, 0f),
            size = Size(size.width - inset * 2, size.height),
            cornerRadius = CornerRadius(16.dp.toPx()),
        )
    }
}

/** A marked row's avatar: circle → Cookie9Sided and back. */
internal fun Modifier.anchorCrown(mark: AnchorMark?): Modifier {
    if (mark == null || !mark.full) return this
    return graphicsLayer {
        val p = mark.presence.value
        if (p > 0f) {
            clip = true
            shape = MorphShape(AnchorCrownMorph, p)
        }
    }
}

/**
 * The wave under a marked row's name, as wide as the name: on its bottom edge, so it reads
 * as the name's underline and clears the time below.
 */
@Composable
internal fun Modifier.anchorWave(mark: AnchorMark?): Modifier {
    if (mark == null || !mark.full) return this
    val ink = MaterialTheme.colorScheme.primary
    val path = remember { Path() }
    return drawBehind {
        val a = mark.presence.value
        if (a <= 0f) return@drawBehind
        val stroke = 1.5.dp.toPx()
        val length = size.width
        val wavelength = AnchorWavelength.toPx()
        val amplitude = AnchorWaveHeight.toPx()
        val y0 = size.height
        val shift = mark.phase.value * 2f * PI.toFloat()
        path.reset()
        var x = 0f
        while (x <= length) {
            val y = y0 + amplitude * sin(x / wavelength * 2f * PI.toFloat() - shift)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 1.dp.toPx()
        }
        drawPath(path, ink.copy(alpha = ink.alpha * a), style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

private val AnchorCrownMorph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
private val AnchorWavelength = 8.dp
private val AnchorWaveHeight = 1.2.dp

/** One wavelength of the wave's flow. */
private const val AnchorWavePeriodMs = 800
private const val AnchorInMs = 450
private const val AnchorHoldMs = 900L
private const val AnchorOutMs = 700

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
