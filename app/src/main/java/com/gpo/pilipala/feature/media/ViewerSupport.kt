package com.gpo.pilipala.feature.media

import android.graphics.Matrix
import android.graphics.Region
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.gpo.pilipala.feature.video.findActivity
import com.gpo.pilipala.ui.theme.ContinuousSmoothing
import hct.Hct
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Private plumbing for the viewer: its window and that window's system bars,
 * reduced motion, the wash's tones and the little geometry the hero flights
 * share (corner contours, crops, fits).
 */

// ── Windows ─────────────────────────────────────────────────────────────────

/**
 * The window [this] view is drawn in: a dialog window's (the viewer, a bottom
 * sheet: their compose views sit in a [DialogWindowProvider]) or the activity's.
 */
internal fun View.hostWindow(): Window? {
    (this as? DialogWindowProvider)?.let { return it.window }
    var p = parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = p.parent
    }
    return context.findActivity()?.window
}

/** "Remove animations" (animator scale 0): flights become fades in place. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Hides status and navigation bars while [hidden] (a swipe shows them
 * transiently); shows them again on dispose. It acts on the window this
 * composition is drawn in: the system bars follow the focused window, so the
 * viewer's own window, and a sheet opened over it, each ask for themselves.
 */
@Composable
internal fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.hostWindow()
        if (window == null || !hidden) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Turns a Compose Dialog's window into a clear, edge-to-edge layer for the
 * viewer: no dim, no window animation (the hero flight is the animation), no
 * enforced bar scrims, and the bar icons of the window beneath ([base]) to
 * start with, so nothing at the top of the screen flips the moment it opens.
 *
 * The bar icons of THIS window only: [com.gpo.pilipala.ui.theme.ApplySystemBars]
 * owns the activity window's, but the bars follow the focused window, and
 * while the viewer is up that is its own.
 */
@Suppress("DEPRECATION")
internal fun Window.prepareClearLayer(base: Window?) {
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setDimAmount(0f)
    // An empty animation style: the window neither fades nor scales on its own.
    setWindowAnimations(android.R.style.Animation)
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isNavigationBarContrastEnforced = false
        isStatusBarContrastEnforced = false
    }
    if (base != null) {
        val from = WindowCompat.getInsetsController(base, base.decorView)
        val to = WindowCompat.getInsetsController(this, decorView)
        to.isAppearanceLightStatusBars = from.isAppearanceLightStatusBars
        to.isAppearanceLightNavigationBars = from.isAppearanceLightNavigationBars
    }
}

// ── Colour ──────────────────────────────────────────────────────────────────

/** [argb] pulled down to a deep tone of its own hue (the viewer's wash). */
internal fun deepTone(argb: Int, tone: Double = 9.0, maxChroma: Double = 26.0): Color {
    val hct = Hct.fromInt(argb)
    return Color(Hct.from(hct.hue, hct.chroma.coerceAtMost(maxChroma), tone).toInt())
}

/** A light tone of the same hue, for the few labels drawn on a [deepTone] wash. */
internal fun lightTone(color: Color): Color {
    val hct = Hct.fromInt(color.toArgb())
    return Color(Hct.from(hct.hue, hct.chroma.coerceAtMost(18.0), 90.0).toInt())
}

// ── Pictures ────────────────────────────────────────────────────────────────

/** The 长图 rule (the dynamics grid's badge): taller than two and a half widths. Read from its top. */
internal fun ViewerImage.isLong(knownAspect: Float = 0f): Boolean {
    val a = aspect.takeIf { it > 0f } ?: knownAspect
    return a > 0f && 1f / a > 2.5f
}

// ── Geometry ────────────────────────────────────────────────────────────────

/** Four corner radii in px: top-left, top-right, bottom-right, bottom-left. */
@Immutable
internal class Corners(val tl: Float, val tr: Float, val br: Float, val bl: Float) {
    val max: Float get() = maxOf(tl, tr, br, bl)

    fun scale(s: Float) = Corners(tl * s, tr * s, br * s, bl * s)

    fun atLeast(r: Float) = Corners(maxOf(tl, r), maxOf(tr, r), maxOf(br, r), maxOf(bl, r))

    companion object {
        val Zero = Corners(0f, 0f, 0f, 0f)

        fun uniform(r: Float) = Corners(r, r, r, r)

        fun lerp(a: Corners, b: Corners, t: Float) = Corners(
            lerp(a.tl, b.tl, t).coerceAtLeast(0f),
            lerp(a.tr, b.tr, t).coerceAtLeast(0f),
            lerp(a.br, b.br, t).coerceAtLeast(0f),
            lerp(a.bl, b.bl, t).coerceAtLeast(0f),
        )
    }
}

/**
 * A block's outline in px, the way the app draws its connected shapes: four
 * continuous corners and, optionally, a [notchW] × [notchH] notch cut from the
 * bottom-right corner (where a badge sits on the colour behind). With a notch,
 * the bottom-right radius rounds the notch's two outer corners instead, so a
 * notch shrinking to nothing leaves exactly the rounded corner it grew from.
 */
@Immutable
internal class Contour(val corners: Corners, val notchW: Float = 0f, val notchH: Float = 0f) {
    val hasNotch: Boolean get() = notchW >= 0.5f && notchH >= 0.5f

    fun scale(s: Float) = Contour(corners.scale(s), notchW * s, notchH * s)

    fun atLeast(r: Float) = Contour(corners.atLeast(r), notchW, notchH)

    companion object {
        val Zero = Contour(Corners.Zero)

        fun uniform(r: Float) = Contour(Corners.uniform(r))

        fun lerp(a: Contour, b: Contour, t: Float) = Contour(
            Corners.lerp(a.corners, b.corners, t),
            lerp(a.notchW, b.notchW, t).coerceAtLeast(0f),
            lerp(a.notchH, b.notchH, t).coerceAtLeast(0f),
        )
    }
}

/**
 * [contour] drawn at [rect]: continuous corners (graphics-shapes rounding with
 * the app's smoothing), so a flying picture keeps the curvature of the blocks
 * it leaves and lands in. Square and notch-free → a plain rectangle.
 *
 * A notch is the union of two blocks — the band above it and the block left
 * of it, each rounding its own bottom-right by `corners.br` — plus a fillet of
 * [notchRadius] in its inside corner. Built that way (not as one polygon) a
 * notch only a few px across still reads as the rounded corner it is leaving.
 */
internal fun contourOutline(rect: Rect, contour: Contour, notchRadius: Float): Outline {
    val c = contour.corners
    if (rect.width <= 0f || rect.height <= 0f) return Outline.Rectangle(rect)
    if (!contour.hasNotch) {
        if (c.max < 0.5f) return Outline.Rectangle(rect)
        return Outline.Generic(continuousRect(rect, c))
    }
    val nw = contour.notchW.coerceAtMost(rect.width * 0.8f)
    val nh = contour.notchH.coerceAtMost(rect.height * 0.8f)
    val band = continuousRect(Rect(rect.left, rect.top, rect.right, rect.bottom - nh), Corners(c.tl, c.tr, c.br, 0f))
    val block = continuousRect(Rect(rect.left, rect.top, rect.right - nw, rect.bottom), Corners(c.tl, 0f, c.br, c.bl))
    var path = Path.combine(PathOperation.Union, band, block)
    val f = notchRadius.coerceAtMost(min(nw, nh) / 2f).coerceAtMost(min(rect.width - nw, rect.height - nh) / 2f)
    if (f >= 0.5f) {
        val x = rect.right - nw
        val y = rect.bottom - nh
        val square = Path().apply { addRect(Rect(x, y, x + f, y + f)) }
        val round = Path().apply { addOval(Rect(x, y, x + 2f * f, y + 2f * f)) }
        path = Path.combine(PathOperation.Union, path, Path.combine(PathOperation.Difference, square, round))
    }
    return Outline.Generic(path)
}

/** A rect with four continuous corners (radii clamped by graphics-shapes to what its sides allow). */
private fun continuousRect(rect: Rect, c: Corners): Path {
    if (rect.width <= 0f || rect.height <= 0f) return Path()
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    // Clockwise on screen: TL, TR, BR, BL.
    val polygon = RoundedPolygon(
        floatArrayOf(l, t, r, t, r, b, l, b),
        perVertexRounding = listOf(
            CornerRounding(c.tl, ContinuousSmoothing),
            CornerRounding(c.tr, ContinuousSmoothing),
            CornerRounding(c.br, ContinuousSmoothing),
            CornerRounding(c.bl, ContinuousSmoothing),
        ),
        centerX = rect.center.x,
        centerY = rect.center.y,
    )
    return polygon.toPath().asComposePath()
}

/** A [Shape] that draws [contour] at [rect] inside its layer (the hero layer's clip, rebuilt per frame). */
internal class ContourAt(private val rect: Rect, private val contour: Contour, private val notchRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        contourOutline(rect, contour, notchRadius)
}

/** The radii [shape] draws at [size] (LTR); a non-corner shape counts as [fallback] all round. */
internal fun Shape.cornersAt(size: Size, density: Density, fallback: Float = 0f): Corners {
    val s = this as? CornerBasedShape ?: return Corners.uniform(fallback)
    val max = size.minDimension / 2f
    return Corners(
        s.topStart.toPx(size, density).coerceIn(0f, max),
        s.topEnd.toPx(size, density).coerceIn(0f, max),
        s.bottomEnd.toPx(size, density).coerceIn(0f, max),
        s.bottomStart.toPx(size, density).coerceIn(0f, max),
    )
}

/**
 * The [Contour] of any [Shape] at [size] (physical corners, as the connected
 * grids place their cells). Corner shapes and round rects are read exactly; a
 * path (any other cell outline, a badge notch included) is measured: each
 * corner's radius from how deep its diagonal runs before the shape begins, and
 * a notch at the bottom-right when its walls stand as far in as that depth
 * (a round corner's flanks lie much further out). Not cheap: cache it.
 */
internal fun Shape.contourAt(size: Size, density: Density, fallback: Float): Contour {
    if (size.width < 1f || size.height < 1f) return Contour.uniform(fallback)
    if (this === RectangleShape) return Contour.Zero
    if (this is CornerBasedShape) return Contour(cornersAt(size, density, fallback))
    val outline = runCatching { createOutline(size, LayoutDirection.Ltr, density) }.getOrNull()
        ?: return Contour.uniform(fallback)
    return when (outline) {
        is Outline.Rectangle -> Contour.Zero
        is Outline.Rounded -> outline.roundRect.let {
            Contour(Corners(it.topLeftCornerRadius.x, it.topRightCornerRadius.x, it.bottomRightCornerRadius.x, it.bottomLeftCornerRadius.x))
        }
        is Outline.Generic -> runCatching { probeContour(outline.path, size, fallback) }.getOrNull() ?: Contour.uniform(fallback)
    }
}

/** [inner]: the radius of a notch's own corners (not measured: taken as the fallback corner). */
private fun probeContour(path: Path, size: Size, inner: Float): Contour? {
    // Sub-pixel resolution, but never a huge region.
    val k = min(4f, 2048f / max(size.width, size.height))
    val w = (size.width * k).roundToInt()
    val h = (size.height * k).roundToInt()
    if (w < 8 || h < 8) return null
    val scaled = android.graphics.Path(path.asAndroidPath())
    scaled.transform(Matrix().apply { setScale(k, k) })
    val region = Region()
    if (!region.setPath(scaled, Region(0, 0, w, h))) return null
    fun inside(x: Float, y: Float): Boolean =
        region.contains((x * k).toInt().coerceIn(0, w - 1), (y * k).toInt().coerceIn(0, h - 1))

    val step = 1f / k
    val maxD = size.minDimension / 2f
    val right = size.width - step
    val bottom = size.height - step

    // How far along the diagonal from a corner (inward) the shape begins.
    fun depth(cx: Float, cy: Float, dx: Float, dy: Float): Float {
        var d = 0f
        while (d < maxD) {
            if (inside(cx + dx * d, cy + dy * d)) return d
            d += step
        }
        return maxD
    }
    // A circular corner of radius r meets its diagonal r·(1 − 1/√2) in.
    val perDepth = 1f / (1f - 1f / sqrt(2f))
    fun radius(d: Float) = (d * perDepth).coerceIn(0f, maxD)

    val tl = radius(depth(0f, 0f, 1f, 1f))
    val tr = radius(depth(right, 0f, -1f, 1f))
    val bl = radius(depth(0f, bottom, 1f, -1f))
    val dBR = depth(right, bottom, -1f, -1f)

    // The notch: halfway into that depth, how far the walls stand from the edges.
    if (dBR > 1f) {
        val row = bottom - dBR / 2f
        val col = right - dBR / 2f
        var x = right
        while (x > 0f && !inside(x, row)) x -= step
        var y = bottom
        while (y > 0f && !inside(col, y)) y -= step
        val gapB = size.width - x
        val gapR = size.height - y
        if (min(gapB, gapR) < dBR * 1.35f && gapB < size.width * 0.9f && gapR < size.height * 0.9f) {
            return Contour(Corners(tl, tr, inner, bl), notchW = gapB, notchH = gapR)
        }
    }
    return Contour(Corners(tl, tr, radius(dBR), bl))
}

internal fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    lerp(a.left, b.left, t),
    lerp(a.top, b.top, t),
    lerp(a.right, b.right, t),
    lerp(a.bottom, b.bottom, t),
)

/** [rect] scaled by [k] about [pivot]. */
internal fun Rect.scaleAbout(k: Float, pivot: Offset) = Rect(
    pivot.x + (left - pivot.x) * k,
    pivot.y + (top - pivot.y) * k,
    pivot.x + (right - pivot.x) * k,
    pivot.y + (bottom - pivot.y) * k,
)

/** A picture of [aspect] (w/h) fitted inside [area], centred. */
internal fun fitRect(aspect: Float, area: Rect): Rect {
    if (area.width <= 0f || area.height <= 0f) return area
    val a = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
    val (w, h) = if (area.width / area.height > a) area.height * a to area.height else area.width to area.width / a
    val left = area.left + (area.width - w) / 2f
    val top = area.top + (area.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * A long picture to read: as wide as the screen allows without passing its
 * shorter side (a landscape tablet keeps a column, a phone goes edge to edge),
 * its top at the top. Null when that is no taller than the screen.
 */
internal fun readingRect(aspect: Float, viewport: Size): Rect? {
    if (viewport.width <= 0f || viewport.height <= 0f || aspect <= 0f || !aspect.isFinite()) return null
    val fitted = viewport.height * aspect
    val w = min(viewport.width, max(viewport.minDimension, fitted))
    val h = w / aspect
    if (h <= viewport.height + 0.5f) return null
    val left = (viewport.width - w) / 2f
    return Rect(left, 0f, left + w, h)
}

/** The horizontal and vertical bias (−1 … 1) of a crop [alignment], read off the alignment itself (LTR, as cells place physically). */
internal fun Alignment.bias(): Offset {
    val o = align(IntSize.Zero, IntSize(2000, 2000), LayoutDirection.Ltr)
    return Offset(o.x / 1000f - 1f, o.y / 1000f - 1f)
}

/** Where a picture of [aspect] sits when it crop-fills [cell], anchored by [bias] (as ContentScale.Crop + an alignment). */
internal fun cropRect(aspect: Float, cell: Rect, bias: Offset): Rect {
    val a = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
    val (w, h) = if (cell.width / cell.height.coerceAtLeast(1f) > a) cell.width to cell.width / a else cell.height * a to cell.height
    val left = cell.left + (cell.width - w) * (1f + bias.x) / 2f
    val top = cell.top + (cell.height - h) * (1f + bias.y) / 2f
    return Rect(left, top, left + w, top + h)
}
