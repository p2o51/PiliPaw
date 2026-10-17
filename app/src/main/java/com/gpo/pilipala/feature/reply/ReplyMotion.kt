package com.gpo.pilipala.feature.reply

import android.os.SystemClock
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Yoin spring specs captured in composition so non-composable transition lambdas can use them. */
@Immutable
internal class ReplyMotionSpecs(
    val fade: FiniteAnimationSpec<Float>,
    val scale: FiniteAnimationSpec<Float>,
    val offset: FiniteAnimationSpec<IntOffset>,
    val size: FiniteAnimationSpec<IntSize>,
)

@Composable
internal fun replyMotion(): ReplyMotionSpecs = ReplyMotionSpecs(
    fade = YoinMotion.defaultEffectsSpec(),
    scale = YoinMotion.fastSpatialSpec(),
    offset = YoinMotion.fastSpatialSpec(),
    size = YoinMotion.fastSpatialSpec(),
)

internal fun ReplyMotionSpecs.crossfade(): ContentTransform = fadeIn(fade) togetherWith fadeOut(fade)

// ── Row entrances ────────────────────────────────────────────────────────────

/**
 * How a row arrives: a short rise in a stagger (a page landing) or an expand
 * (the reply just sent). Identity matters: a lazy slot reused for another row
 * gets a new instance, so its animation restarts instead of inheriting one
 * that already finished.
 */
@Immutable
internal sealed interface Entrance {
    class Rise(val delayMs: Int) : Entrance

    class Expand : Entrance
}

/**
 * Decides which rows animate in. It lives in the ViewModel, so a page that
 * comes back from the thread does not replay its cascade. A row rises only
 * the first time it composes shortly after its batch landed; whatever landed
 * off-screen is marked seen once the batch settles ([settle]), so scrolling
 * down to it later is a plain scroll.
 */
internal class ReplyEntrance {
    private val seen = HashSet<Long>()
    private var lastClaimAt = 0L
    private var slot = 0

    /** The reply the composer just sent: it expands in once. */
    var sent: Long = 0L

    fun claim(rpid: Long): Entrance? {
        if (rpid != 0L && rpid == sent) {
            sent = 0L
            seen += rpid
            return Entrance.Expand()
        }
        if (!seen.add(rpid)) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimAt > BurstGapMs) slot = 0
        lastClaimAt = now
        return Entrance.Rise(min(slot++, MaxStaggerSlots) * StaggerMs)
    }

    /** A sub-reply line of a preview block: only the one just sent animates. */
    fun claimSent(rpid: Long): Boolean {
        if (rpid == 0L || rpid != sent) return false
        sent = 0L
        return true
    }

    fun settle(ids: Iterable<Long>) {
        seen += ids
    }

    /** A new list (sort switch): everything in it rises again. */
    fun reset() {
        seen.clear()
        slot = 0
    }

    private companion object {
        /** Claims further apart than this start a new cascade. */
        const val BurstGapMs = 120L
        const val StaggerMs = 32
        const val MaxStaggerSlots = 8
    }
}

/** Delay before a batch is considered settled (its unseen rows stop rising). */
internal const val EntranceSettleMs = 450L

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
internal fun Modifier.entrance(entrance: Entrance?, layout: ReplyLayoutMotion?): Modifier = when (entrance) {
    null -> this
    is Entrance.Rise -> this.then(riseIn(entrance))
    is Entrance.Expand -> this.then(expandIn(entrance, layout))
}

private val RiseDistance = 16.dp

/** A short lift + fade after its delay: alpha on the effects spring, the lift on the spatial one. */
@Composable
private fun riseIn(rise: Entrance.Rise): Modifier {
    val alpha = remember(rise) { Animatable(0f) }
    val lift = remember(rise) { Animatable(1f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(rise) {
        if (rise.delayMs > 0) delay(rise.delayMs.toLong())
        launch { alpha.animateTo(1f, effects) }
        lift.animateTo(0f, spatial)
    }
    return Modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = lift.value * RiseDistance.toPx()
    }
}

/** Grows from zero height (top anchored, clipped) while fading in; reads only in layout / draw. */
@Composable
private fun expandIn(expand: Entrance.Expand, layout: ReplyLayoutMotion?): Modifier {
    val grow = remember(expand) { Animatable(0f) }
    val alpha = remember(expand) { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(expand) {
        layout?.begin()
        try {
            coroutineScope {
                launch { alpha.animateTo(1f, effects) }
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
