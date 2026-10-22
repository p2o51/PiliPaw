package com.gpo.pilipala.feature.sources

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

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

/** One frame of the bar, in the host's px: its four blocks, the hit area and the goo. */
internal class MiniGeo(
    val left: Rect,
    val tile: Rect,
    val pause: Rect,
    val right: Rect,
    val sideAlpha: Float,
    val tileCorner: Float,
    /** Where touches land: the drawn blocks, never more. */
    val hit: Rect,
    val merge: Float,
    val windowed: Boolean,
    val neck: Neck?,
)

/** The liquid bridge between the shrinking picture and the nav bar it merges into. */
internal class Neck(val cx: Float, val top: Float, val bottom: Float, val halfTop: Float, val pinch: Float, val flare: Float)

/** Lengths the geometry needs, in px. */
internal data class MiniPx(val gap: Float, val inner: Float, val windowCorner: Float, val margin: Float, val neckInset: Float)

internal fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

internal fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

internal fun rectAt(c: Offset, w: Float, h: Float) = Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)

/** Keeps a [w] × [h] picture centred at [c] inside [bounds] (centred on it when larger). */
internal fun clampCenter(c: Offset, w: Float, h: Float, bounds: Rect): Offset {
    fun axis(v: Float, lo: Float, hi: Float, size: Float) =
        if (hi - lo <= size) (lo + hi) / 2f else v.coerceIn(lo + size / 2f, hi - size / 2f)
    return Offset(axis(c.x, bounds.left, bounds.right, w), axis(c.y, bounds.top, bounds.bottom, h))
}

/**
 * The bar at [axis] on its size axis. At the row: [−15][picture 16:9][pause][+15] in [bar].
 * Below it the picture shrinks into [circle] (the nav bar's start) and the side blocks fold into
 * it; above it they fold into the growing picture, which leaves as a window of the video's own
 * [aspect], centred at [windowCenter] (or where it detached) and kept inside [bounds].
 */
internal fun miniGeo(
    axis: Float,
    d: MiniDims,
    bar: Rect,
    circle: Rect?,
    aspect: Float,
    windowCenter: Offset,
    bounds: Rect,
    nav: Rect?,
    px: MiniPx,
): MiniGeo {
    val m = if (circle != null) MiniAxis.merge(axis, d) else 0f
    val det = MiniAxis.detach(axis, d)
    val windowed = axis >= d.detach
    val sideW = ((bar.width - d.row - px.gap * 3f) / 3f).coerceAtLeast(0f)
    val l0 = Rect(bar.left, bar.top, bar.left + sideW, bar.bottom)
    val t0 = Rect(l0.right + px.gap, bar.top, l0.right + px.gap + d.row, bar.bottom)
    val p0 = Rect(t0.right + px.gap, bar.top, t0.right + px.gap + sideW, bar.bottom)
    val r0 = Rect(p0.right + px.gap, bar.top, bar.right, bar.bottom)
    // Where a window made from the bar sits: centred over the bar, its bottom on the bar's.
    fun home(h: Float) = if (windowCenter.isSpecified) windowCenter else Offset(bar.center.x, bar.bottom - h / 2f)
    val tile: Rect
    val corner: Float
    when {
        m > 0f -> {
            tile = lerpRect(t0, circle!!, m)
            corner = lerp(px.inner, circle.width / 2f, m)
        }
        windowed -> {
            val (w, h) = MiniAxis.pictureSize(axis, aspect)
            tile = rectAt(clampCenter(home(h), w, h, bounds), w, h)
            corner = px.windowCorner
        }
        det > 0f -> {
            // Growing out of the bar: the picture turns from the bar's 16:9 to the video's shape.
            val (w, h) = MiniAxis.pictureSize(axis, lerp(16f / 9f, aspect, det))
            val (dw, dh) = MiniAxis.pictureSize(d.detach, aspect)
            tile = rectAt(lerpOffset(t0.center, clampCenter(home(dh), dw, dh, bounds), det), w, h)
            corner = lerp(px.inner, px.windowCorner, det)
        }
        else -> {
            tile = t0
            corner = px.inner
        }
    }
    val fold = max(m, det)
    fun folded(r: Rect): Rect {
        val c = lerpOffset(r.center, tile.center, fold)
        return rectAt(c, r.width * (1f - fold), r.height * (1f - 0.4f * fold))
    }
    val left = folded(l0)
    val pause = folded(p0)
    val right = folded(r0)
    val sideAlpha = (1f - fold * 1.6f).coerceIn(0f, 1f)
    val hit = when {
        windowed || m >= 0.999f -> tile
        sideAlpha <= 0f -> tile
        else -> tile.union(left).union(pause).union(right)
    }
    // The goo: only while the bar, stacked over the nav bar, pours down into it.
    val neck = if (nav != null && circle != null && m > 0.02f && m < 0.98f && bar.bottom <= nav.top + 1f) {
        val s = sin(PI.toFloat() * m)
        val halfTop = tile.width * 0.32f * (0.4f + 0.6f * s)
        Neck(
            cx = tile.center.x,
            top = tile.center.y,
            bottom = nav.top + px.neckInset,
            halfTop = halfTop,
            pinch = halfTop * (0.35f + 0.25f * s),
            flare = halfTop * 1.5f,
        )
    } else null
    return MiniGeo(left, tile, pause, right, sideAlpha, corner, hit, m, windowed, neck)
}

internal fun Rect.union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
