package com.gpo.pilipala.feature.sources

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * The mini bar's springs (stiffness k, damping ratio z, as the prototype gives them). Every
 * release hands its finger velocity to one of these.
 */
internal object MiniSprings {
    fun <T> fast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** Page open / collapse, the bar following the nav bar. */
    fun <T> def(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 700f)
    fun <T> slow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 320f)

    /** Merge, split, snap, dock: the liquid one. */
    fun <T> liq(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.68f, stiffness = 420f)
}

/**
 * Where the mini bar rests on its one size axis: the picture's long side, from the 48dp
 * circle in the nav bar, through the bar's 16:9 tile, to a free floating window.
 */
internal sealed interface MiniRest {
    data object Merged : MiniRest
    data object Row : MiniRest

    /** A floating window whose picture's long side is [axisDp]. */
    data class Window(val axisDp: Float) : MiniRest
}

/** What a released pinch does. */
internal sealed interface MiniRelease {
    data class Rest(val rest: MiniRest) : MiniRelease
    data object Open : MiniRelease
}

/**
 * The size axis' landmarks, in one unit (px on screen, plain numbers in tests): [circle] in the
 * nav bar, [row] the bar's tile, past [row] + [detachExtra] a floating window.
 */
internal data class MiniDims(
    val circle: Float,
    val row: Float,
    val detachExtra: Float,
    /** Spread past the largest window by this much and the video opens. */
    val openPast: Float,
) {
    val detach: Float get() = row + detachExtra
}

internal object MiniAxis {
    const val RubberBand = 0.3f

    /** Past [min] or [max] the size gives only [RubberBand] of the fingers' travel. */
    fun rubber(raw: Float, min: Float, max: Float): Float = when {
        raw > max -> max + (raw - max) * RubberBand
        raw < min -> min - (min - raw) * RubberBand
        else -> raw
    }

    /** 0 at the bar, 1 in the circle. */
    fun merge(axis: Float, d: MiniDims): Float = ((d.row - axis) / (d.row - d.circle)).coerceIn(0f, 1f)

    /** 0 at the bar, 1 where it leaves as a window. */
    fun detach(axis: Float, d: MiniDims): Float = ((axis - d.row) / d.detachExtra).coerceIn(0f, 1f)

    /** The picture's size for a long side [axis] at [aspect] (width / height). */
    fun pictureSize(axis: Float, aspect: Float): Pair<Float, Float> =
        if (aspect >= 1f) axis to axis / aspect else axis * aspect to axis

    /** The largest window's long side that fits [widthCap] × [heightCap] at [aspect]. */
    fun maxAxis(widthCap: Float, heightCap: Float, aspect: Float): Float =
        if (aspect >= 1f) minOf(widthCap, heightCap * aspect) else minOf(heightCap, widthCap / aspect)

    /**
     * Where a pinch released at [raw] (the fingers' size, before rubber banding) moving at
     * [velocity] (per second) lands. Above the bar: a window once ~70% of the way to the detach
     * point, else back to the bar; below it: the circle past halfway (when there is a nav bar
     * to merge into), else the bar. Past the largest window by [MiniDims.openPast]: the video.
     */
    fun release(raw: Float, velocity: Float, d: MiniDims, max: Float, canMerge: Boolean): MiniRelease {
        if (raw > max + d.openPast) return MiniRelease.Open
        // A quick flick counts a little further along its way.
        val at = raw + velocity * 0.08f
        val extra = at - d.row
        return MiniRelease.Rest(
            when {
                extra > 0f && extra >= d.detachExtra * 0.7f -> MiniRest.Window(raw.coerceIn(d.detach, max))
                extra >= 0f -> MiniRest.Row
                canMerge && merge(at, d) > 0.5f -> MiniRest.Merged
                else -> MiniRest.Row
            },
        )
    }

    /**
     * A floating window let go of: back into the bar when its bottom edge is within [near] of
     * the [dockLine] (the nav bar's top) and it is not thrown upward faster than [upFast], or
     * when it is thrown past the line by [past].
     */
    fun docks(bottom: Float, projectedBottom: Float, velocityY: Float, dockLine: Float, near: Float, past: Float, upFast: Float): Boolean =
        (bottom >= dockLine - near && velocityY > -upFast) || projectedBottom > dockLine + past
}
