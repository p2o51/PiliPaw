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

/** What a released pinch does: where the bar comes to rest (a pinch never opens the video). */
internal sealed interface MiniRelease {
    data class Rest(val rest: MiniRest) : MiniRelease
}

/**
 * The size axis' landmarks, in one unit (px on screen, plain numbers in tests): [circle] in the
 * nav bar, [row] the bar's tile, past [row] + [detachExtra] a floating window.
 */
internal data class MiniDims(
    val circle: Float,
    val row: Float,
    val detachExtra: Float,
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
     * to merge into), else the bar. Past the largest window: back to the largest (never the video).
     */
    fun release(raw: Float, velocity: Float, d: MiniDims, max: Float, canMerge: Boolean): MiniRelease {
        // Past the largest window it only rubber-bands; a pinch never opens the video (a tap does).
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
}

/**
 * How the bar's blocks stand: the row ([−15][picture][pause][+15]) over or beside the nav bar,
 * or the side rail's strip (picture on top, then pause, −15, +15), on the rail's edge.
 */
internal enum class MiniOrient {
    Row, StripLeft, StripRight;

    val strip: Boolean get() = this != Row

    /** The rail (and the strip) are on the right edge. */
    val right: Boolean get() = this == StripRight

    companion object {
        fun of(rail: Boolean, right: Boolean) = when {
            !rail -> Row
            right -> StripRight
            else -> StripLeft
        }
    }
}

/** One frame of the bar, in the host's px: its four blocks, the hit area and the goo. */
internal class MiniGeo(
    val left: Rect,
    val tile: Rect,
    val pause: Rect,
    val right: Rect,
    val sideAlpha: Float,
    val tileCorner: Float,
    /** Where touches land: the drawn blocks (merged: with the nav bar's margin around the circle). */
    val hit: Rect,
    val merge: Float,
    val windowed: Boolean,
    val neck: Neck?,
    /** The floating window's play / pause (bottom left) and close (bottom right) keys; empty otherwise. */
    val play: Rect = Rect.Zero,
    val close: Rect = Rect.Zero,
    val buttonsAlpha: Float = 0f,
    /** The picture's top corners (the strip's rounded top end); [tileCorner] elsewhere. */
    val tileTopCorner: Float = tileCorner,
)

/** The liquid bridge between the shrinking picture and the nav bar it merges into. */
internal class Neck(val cx: Float, val top: Float, val bottom: Float, val halfTop: Float, val pinch: Float, val flare: Float, val up: Boolean = false)

/** Lengths the geometry needs, in px. */
internal data class MiniPx(
    val gap: Float,
    val inner: Float,
    val windowCorner: Float,
    val margin: Float,
    val neckInset: Float,
    /** The window's keys (never smaller than this), and their inset from its corners. */
    val button: Float = 0f,
    val buttonInset: Float = 0f,
    /** The strip's two ends: the picture's top corners and the last block's bottom ones. */
    val stripEnd: Float = 0f,
)

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
 * The bar at [axis] on its size axis. At the row: [−15][picture 16:9][pause][+15] in [bar]
 * ([picture][pause] for a live stream: no [sides], the side blocks are empty at the bar's ends).
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
    /** Popping out under a finger: the picture's centre is the finger's ([windowCenter]) at once. */
    pinned: Boolean = false,
    sides: Boolean = true,
    orient: MiniOrient = MiniOrient.Row,
): MiniGeo {
    val m = if (circle != null) MiniAxis.merge(axis, d) else 0f
    val det = MiniAxis.detach(axis, d)
    val windowed = axis >= d.detach
    val l0: Rect
    val t0: Rect
    val p0: Rect
    val r0: Rect
    if (orient.strip) {
        // The strip: the picture as wide as the strip on top (16:9), the keys stacked under it.
        val tileH = d.row * 9f / 16f
        val n = if (sides) 3 else 1
        val keyH = ((bar.height - tileH - px.gap * n) / n).coerceAtLeast(0f)
        t0 = Rect(bar.left, bar.top, bar.left + d.row, bar.top + tileH)
        p0 = Rect(bar.left, t0.bottom + px.gap, bar.right, t0.bottom + px.gap + keyH)
        l0 = if (sides) Rect(bar.left, p0.bottom + px.gap, bar.right, p0.bottom + px.gap + keyH) else Rect(bar.left, bar.bottom, bar.right, bar.bottom)
        r0 = if (sides) Rect(bar.left, l0.bottom + px.gap, bar.right, bar.bottom) else Rect(bar.left, bar.bottom, bar.right, bar.bottom)
    } else {
        val sideW = if (sides) ((bar.width - d.row - px.gap * 3f) / 3f).coerceAtLeast(0f) else (bar.width - d.row - px.gap).coerceAtLeast(0f)
        l0 = Rect(bar.left, bar.top, if (sides) bar.left + sideW else bar.left, bar.bottom)
        t0 = if (sides) Rect(l0.right + px.gap, bar.top, l0.right + px.gap + d.row, bar.bottom) else Rect(bar.left, bar.top, bar.left + d.row, bar.bottom)
        p0 = Rect(t0.right + px.gap, bar.top, t0.right + px.gap + sideW, bar.bottom)
        r0 = if (sides) Rect(p0.right + px.gap, bar.top, bar.right, bar.bottom) else Rect(bar.right, bar.top, bar.right, bar.bottom)
    }
    // Where a window made from the bar sits: centred over the bar, its bottom on the bar's; from
    // the strip, its top on the picture's and its rail side on the strip's.
    fun home(w: Float, h: Float) = when {
        windowCenter.isSpecified -> windowCenter
        orient == MiniOrient.StripLeft -> Offset(t0.left + w / 2f, t0.top + h / 2f)
        orient == MiniOrient.StripRight -> Offset(t0.right - w / 2f, t0.top + h / 2f)
        else -> Offset(bar.center.x, bar.bottom - h / 2f)
    }
    val tile: Rect
    val corner: Float
    when {
        m > 0f -> {
            tile = lerpRect(t0, circle!!, m)
            corner = lerp(px.inner, circle.width / 2f, m)
        }
        windowed -> {
            val (w, h) = MiniAxis.pictureSize(axis, aspect)
            tile = rectAt(clampCenter(home(w, h), w, h, bounds), w, h)
            corner = px.windowCorner
        }
        det > 0f -> {
            // Growing out of the bar: the picture turns from the bar's 16:9 to the video's shape.
            val (w, h) = MiniAxis.pictureSize(axis, lerp(16f / 9f, aspect, det))
            val (dw, dh) = MiniAxis.pictureSize(d.detach, aspect)
            val c = if (pinned && windowCenter.isSpecified) clampCenter(windowCenter, w, h, bounds)
            else lerpOffset(t0.center, clampCenter(home(dw, dh), dw, dh, bounds), det)
            tile = rectAt(c, w, h)
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
        // Along the bar they fold away; across it they only thin a little.
        return if (orient.strip) rectAt(c, r.width * (1f - 0.4f * fold), r.height * (1f - fold))
        else rectAt(c, r.width * (1f - fold), r.height * (1f - 0.4f * fold))
    }
    val left = folded(l0)
    val pause = folded(p0)
    val right = folded(r0)
    val sideAlpha = (1f - fold * 1.6f).coerceIn(0f, 1f)
    val hit = when {
        // Merged: the circle plus the nav bar's margin around it, for the sideways swipe; never
        // past the circle's right edge (the first tab starts there).
        // The rail's strip: the circle at the pill's end with the pill's side margin, never over its tabs.
        !windowed && m >= 0.999f && nav != null && orient.strip -> Rect(minOf(nav.left, tile.left), tile.top, maxOf(nav.right, tile.right), tile.bottom + (tile.left - nav.left).coerceAtLeast(0f))
        !windowed && m >= 0.999f && nav != null -> Rect(minOf(nav.left, tile.left), minOf(nav.top, tile.top), tile.right, maxOf(nav.bottom, tile.bottom))
        windowed || m >= 0.999f -> tile
        sideAlpha <= 0f -> tile
        else -> tile.union(left).union(pause).union(right)
    }
    // The goo: only while the bar, stacked over the nav bar, pours down into it (the strip under
    // the rail's pill pours up into it).
    val neck = if (orient.strip && nav != null && circle != null && m > 0.02f && m < 0.98f && bar.top >= nav.bottom - 1f) {
        val s = sin(PI.toFloat() * m)
        val halfTop = tile.width * 0.32f * (0.4f + 0.6f * s)
        Neck(
            cx = tile.center.x,
            top = nav.bottom - px.neckInset,
            bottom = tile.center.y,
            halfTop = halfTop,
            pinch = halfTop * (0.35f + 0.25f * s),
            flare = halfTop * 1.5f,
            up = true,
        )
    } else if (!orient.strip && nav != null && circle != null && m > 0.02f && m < 0.98f && bar.bottom <= nav.top + 1f) {
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
    // The window's two keys grow and shrink with it, and come on just after it detaches.
    val k = if (px.button > 0f) (tile.width / (px.button * 9f)).coerceIn(1f, 1.15f) else 0f
    val bs = px.button * k
    val inset = px.buttonInset * k
    val play = if (windowed) Rect(tile.left + inset, tile.bottom - inset - bs, tile.left + inset + bs, tile.bottom - inset) else Rect.Zero
    val close = if (windowed) Rect(tile.right - inset - bs, tile.bottom - inset - bs, tile.right - inset, tile.bottom - inset) else Rect.Zero
    // Always there on a window, the smallest included (they may reach over its edge).
    val buttonsAlpha = if (windowed) 1f else 0f
    // The strip's picture keeps its rounded top end until it turns into the circle or a window.
    val topCorner = if (orient.strip) {
        when {
            m > 0f -> lerp(px.stripEnd, circle!!.width / 2f, m)
            windowed -> px.windowCorner
            det > 0f -> lerp(px.stripEnd, px.windowCorner, det)
            else -> px.stripEnd
        }
    } else corner
    return MiniGeo(left, tile, pause, right, sideAlpha, corner, hit, m, windowed, neck, play, close, buttonsAlpha, topCorner)
}

internal fun Rect.union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

/** The floating window's two keys. */
internal enum class MiniKey { Play, Close }

/** What one finger's drag does, decided once it passes touch slop. */
internal enum class MiniDragIntent { MoveWindow, SwipeAway, PopOut, DockSwipe, None,

    /** The rail's strip pulled down: it follows the finger and, let go far or fast enough, closes. */
    SwipeDown,

    /** The rail's strip pulled toward the screen edge: it gives a little and springs back. */
    Nudge,
}

/** What one gesture on the bar or the window was, once every finger is up. */
internal enum class MiniGestureKind { Tap, Pinch, Drag, None }

internal object MiniGestures {
    /**
     * A tap only when exactly one finger ever went down (on the bar or anywhere else on the
     * screen), it moved less than [slop] and lifted within [tapTimeoutMs]. A second finger makes
     * the whole gesture a pinch until every finger is up.
     */
    fun classify(maxPointers: Int, travel: Float, durationMs: Long, dragged: Boolean, slop: Float, tapTimeoutMs: Long): MiniGestureKind = when {
        maxPointers >= 2 -> MiniGestureKind.Pinch
        dragged -> MiniGestureKind.Drag
        travel < slop && durationMs <= tapTimeoutMs -> MiniGestureKind.Tap
        else -> MiniGestureKind.None
    }

    /**
     * Where a flung window comes to rest: the corner of [area] (insets already taken off) nearest
     * the point its centre was thrown to, as YouTube's does.
     */
    fun restingCenter(projected: Offset, w: Float, h: Float, area: Rect): Offset =
        cornerCenter(nearestCorner(projected, w, h, area), w, h, area)

    /**
     * The corner a window let go at [center] moving at [velocity] goes to: a light flick carries
     * far (a long, low-friction projection), and a flick that clearly goes one way (past [bias]
     * px/s) picks that way's corner even if it falls short. Never anything but a corner: a
     * window thrown hard off a side lands in that side's corner, it does not close.
     */
    fun flungCorner(center: Offset, velocity: Offset, w: Float, h: Float, area: Rect, bias: Float): Int {
        var corner = nearestCorner(center + velocity * 0.38f, w, h, area)
        if (kotlin.math.abs(velocity.y) > bias) corner = (corner and 1) or (if (velocity.y > 0f) 2 else 0)
        if (kotlin.math.abs(velocity.x) > bias) corner = (corner and 2) or (if (velocity.x > 0f) 1 else 0)
        return corner
    }

    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    fun nearestCorner(projected: Offset, w: Float, h: Float, area: Rect): Int {
        val left = area.left + w / 2f
        val right = (area.right - w / 2f).coerceAtLeast(left)
        val top = area.top + h / 2f
        val bottom = (area.bottom - h / 2f).coerceAtLeast(top)
        val r = if (projected.x - left < right - projected.x) 0 else 1
        val b = if (projected.y - top < bottom - projected.y) 0 else 2
        return r + b
    }

    /** A [w] × [h] window's centre resting in [corner] of [area] (centred where it does not fit). */
    fun cornerCenter(corner: Int, w: Float, h: Float, area: Rect): Offset {
        val left = area.left + w / 2f
        val right = area.right - w / 2f
        val top = area.top + h / 2f
        val bottom = area.bottom - h / 2f
        val x = if (right < left) area.center.x else if (corner and 1 == 0) left else right
        val y = if (bottom < top) area.center.y else if (corner and 2 == 0) top else bottom
        return Offset(x, y)
    }

    /**
     * One finger past slop, [travel] from where it went down: the window moves; on the bar a
     * sideways drag swipes it away and an upward one that started on the picture pops it out as a
     * window under the finger; the merged circle only answers a sideways drag, which closes it.
     */
    fun dragIntent(travel: Offset, windowed: Boolean, merged: Boolean, onPicture: Boolean): MiniDragIntent = when {
        windowed -> MiniDragIntent.MoveWindow
        merged && kotlin.math.abs(travel.x) > kotlin.math.abs(travel.y) -> MiniDragIntent.DockSwipe
        merged -> MiniDragIntent.None
        kotlin.math.abs(travel.x) > kotlin.math.abs(travel.y) -> MiniDragIntent.SwipeAway
        travel.y < 0f && onPicture -> MiniDragIntent.PopOut
        else -> MiniDragIntent.None
    }

    /**
     * [dragIntent] for the side rail's strip: the merged circle as on the bar; the strip closes by
     * a pull down; a sideways drag that started on the picture and heads into the window pops it
     * out under the finger; toward the screen edge it only gives a little (it never closes that way).
     */
    fun stripDragIntent(travel: Offset, windowed: Boolean, merged: Boolean, onPicture: Boolean, railRight: Boolean): MiniDragIntent = when {
        windowed -> MiniDragIntent.MoveWindow
        merged && kotlin.math.abs(travel.x) > kotlin.math.abs(travel.y) -> MiniDragIntent.DockSwipe
        merged -> MiniDragIntent.None
        kotlin.math.abs(travel.y) >= kotlin.math.abs(travel.x) -> if (travel.y > 0f) MiniDragIntent.SwipeDown else MiniDragIntent.None
        intoWindow(travel, railRight) > 0f -> if (onPicture) MiniDragIntent.PopOut else MiniDragIntent.Nudge
        else -> MiniDragIntent.Nudge
    }

    /** The strip's pull down, past this share of its height, closes it when let go. */
    const val SwipeDownAt = 0.35f

    /** Let go: a strip pulled down [dy] of its [height] closes past [SwipeDownAt], or flung down faster than [minSpeed]. */
    fun swipeDownCloses(dy: Float, velocityY: Float, height: Float, minSpeed: Float): Boolean =
        dy > height * SwipeDownAt || (velocityY > minSpeed && dy > 0f)

    /** How far a strip's picture has been pulled into the window (px; negative: toward the rail). */
    fun intoWindow(travel: Offset, railRight: Boolean): Float = if (railRight) -travel.x else travel.x

    /**
     * With the side rail, a window merges back into the strip from the bottom corner on the
     * rail's side only (2 on the left, 3 on the right), pushed mostly toward the rail.
     */
    fun startsStripMerge(corner: Int?, travel: Offset, railRight: Boolean): Boolean {
        if (corner == null || corner and 2 == 0 || (corner and 1 == 1) != railRight) return false
        val toward = -intoWindow(travel, railRight)
        return toward > 0f && toward > kotlin.math.abs(travel.y)
    }

    /**
     * The rail moved to the other edge: a window resting in a corner on the side it now takes
     * goes to the mirrored corner at the same height; one on the other side stays.
     */
    fun cornerAfterRailFlip(corner: Int, railRight: Boolean): Int =
        if ((corner and 1 == 1) == railRight) corner xor 1 else corner

    /**
     * A drag that merges a window back into the bar: only from a bottom corner (2, 3), once it
     * goes mostly down past slop. From a top corner a downward drag just moves the window.
     */
    fun startsMerge(corner: Int?, travel: Offset): Boolean =
        corner != null && corner and 2 != 0 && travel.y > 0f && travel.y > kotlin.math.abs(travel.x)

    /** How far the merge has gone: the finger's way down against [range] (window bottom → nav bar). */
    fun mergeProgress(dy: Float, range: Float): Float = (dy / range.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** How far down a window's merge must go for a plain release to dock it. */
    const val MergeAt = 0.4f

    /** How far across the bar a plain release must go to swipe it away. */
    const val SwipeAwayAt = 0.35f

    /** The merged circle's sideways swipe against [range] (px): 1 is where letting go closes it. */
    fun dockSwipeProgress(dx: Float, range: Float): Float = (kotlin.math.abs(dx) / range.coerceAtLeast(1f)).coerceAtMost(1.5f)

    /**
     * True when [before] → [after] crosses [at] either way: the drag just passed the point where
     * letting go would change the outcome (or came back over it).
     */
    fun crosses(before: Float, after: Float, at: Float): Boolean = (before > at) != (after > at)

    /** Let go: past [threshold] of the way, or still moving down faster than [minSpeed], it docks. */
    fun mergeCompletes(progress: Float, velocityY: Float, minSpeed: Float, threshold: Float = MergeAt): Boolean =
        progress > threshold || velocityY > minSpeed

    /**
     * The key a finger came down on: its own circle, plus [pad] only outward (past the window's
     * edges: left and below for play, right and below for close), never over the picture.
     */
    fun keyAt(at: Offset, play: Rect, close: Rect, pad: Float): MiniKey? = when {
        !play.isEmpty && Rect(play.left - pad, play.top, play.right, play.bottom + pad).contains(at) -> MiniKey.Play
        !close.isEmpty && Rect(close.left, close.top, close.right + pad, close.bottom + pad).contains(at) -> MiniKey.Close
        else -> null
    }

    /**
     * A key fires only for a tap that began on it (the gesture's [kind] decides: any drag, fling
     * or second finger is not one). Close is stricter: not while the window is still settling,
     * nor within [quietMs] of it moving.
     */
    fun keyFires(kind: MiniGestureKind, key: MiniKey?, msSinceMoved: Long, settling: Boolean, quietMs: Long = 300): Boolean = when {
        key == null || kind != MiniGestureKind.Tap -> false
        key == MiniKey.Close -> !settling && msSinceMoved >= quietMs
        else -> true
    }

    /**
     * Whether a touch lands on a floating window: by where it rests, not by its live size (the
     * liquid spring dips below the detach size while it settles; that must not make it the bar).
     */
    fun isWindow(restIsWindow: Boolean, liveWindowed: Boolean): Boolean = restIsWindow || liveWindowed

    /** How far the bar's picture has lifted off as a window (0 the bar, 1 a window): its way up over [range]. */
    fun popProgress(dyUp: Float, range: Float): Float = (dyUp / range.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** Let go: a pop-out past halfway stays a window, else it settles back into the bar. */
    fun popKeeps(progress: Float): Boolean = progress >= 0.5f

    /** Scaling a window by [k] about [centroid] (the fingers' middle) moves its centre with them. */
    fun scaleAbout(center: Offset, centroid: Offset, k: Float): Offset = centroid + (center - centroid) * k
}

/**
 * Every finger on the screen, seen by the activity before any view: the bar learns of a second
 * finger that landed outside it (a 56dp bar rarely holds both), so a pinch never ends as a tap,
 * and the span can be read from both.
 */
internal object MiniTouch {
    /** Fingers down now, and the most there were since the first went down. */
    var count = 0
        private set
    var maxCount = 0
        private set
    private val xs = FloatArray(2)
    private val ys = FloatArray(2)

    fun observe(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> maxCount = 1
            android.view.MotionEvent.ACTION_CANCEL -> maxCount = 0
        }
        val up = e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        val lifting = if (e.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP) 1 else 0
        count = if (up) 0 else e.pointerCount - lifting
        if (count > maxCount) maxCount = count
        for (i in 0 until minOf(2, e.pointerCount)) {
            xs[i] = e.getX(i)
            ys[i] = e.getY(i)
        }
    }

    /** The first two fingers' distance and middle (window px), while two are down. */
    fun span(): Float = Offset(xs[0] - xs[1], ys[0] - ys[1]).getDistance()
    fun centroid(): Offset = Offset((xs[0] + xs[1]) / 2f, (ys[0] + ys[1]) / 2f)
}

/**
 * The side rail's strip (window px): as wide as the rail's pill and under it, [navGap] below; on a
 * page without the rail (it slid away) in the rail's column at the bottom, [bottomMargin] up.
 * The picture 16:9 on top, then pause and (unless live: no [sides]) −15 and +15, [keyH] each.
 */
internal fun railStripRect(
    nav: Rect?,
    railLeft: Float,
    railWidth: Float,
    h: Float,
    stripW: Float,
    keyH: Float,
    gap: Float,
    navGap: Float,
    bottomMargin: Float,
    sides: Boolean = true,
): Rect {
    val height = stripW * 9f / 16f + (if (sides) 3 else 1) * (gap + keyH)
    val left = nav?.let { it.center.x - stripW / 2f } ?: (railLeft + (railWidth - stripW) / 2f)
    val top = nav?.let { it.bottom + navGap } ?: (h - bottomMargin - height)
    return Rect(left, top, left + stripW, top + height)
}

/**
 * The bar's width for its height [barH]: −15, pause and +15 square, the picture 16:9, [gap]
 * between; without the ±15 [sides] (a live stream), the picture and pause only.
 */
internal fun miniBarWidth(barH: Float, gap: Float, sides: Boolean = true): Float =
    if (sides) barH * 3f + barH * 16f / 9f + gap * 3f else barH + barH * 16f / 9f + gap
