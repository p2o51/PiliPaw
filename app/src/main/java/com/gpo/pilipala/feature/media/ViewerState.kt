package com.gpo.pilipala.feature.media

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// ── Origins ─────────────────────────────────────────────────────────────────

/**
 * Picture cells that can grow into the viewer, by key. A cell only stores its
 * [LayoutCoordinates] (no per-frame work); bounds are resolved when the viewer
 * opens and on every frame of a flight, so a cell that moves (the status bar
 * coming back, a page settling, a sheet sliding) is followed. Bounds go
 * through SCREEN coordinates: the viewer has a window of its own, and a cell
 * may sit in the app's window or in a sheet's. The cell of the picture showing
 * in the viewer is [hiddenKey]: it draws nothing, the flying picture is it.
 */
@Stable
internal class OriginRegistry {
    /**
     * Every live cell per key, in attach order. One key can be on screen twice:
     * the same dynamic in the feed and in its detail, or one comment in the list
     * and in its 楼中楼 sheet. The copy the user pressed wins; for the
     * other pages of the viewer, the copy nearest to it (its own grid).
     */
    private val nodes = HashMap<String, MutableList<OriginNode>>()
    private var touched: OriginNode? = null
    private var touchedAt: Offset? = null

    var hiddenKey by mutableStateOf<String?>(null)

    fun attach(node: OriginNode) {
        val list = nodes.getOrPut(node.key) { ArrayList(1) }
        if (list.none { it === node }) list.add(node)
    }

    fun detach(node: OriginNode) {
        nodes[node.key]?.let { list ->
            list.removeAll { it === node }
            if (list.isEmpty()) nodes.remove(node.key)
        }
        if (touched === node) touched = null
    }

    /** A press on [node]: the next open (and its flights home) resolve duplicate keys around it. */
    fun touch(node: OriginNode) {
        touched = node
        node.centerOnScreen()?.let { touchedAt = it }
    }

    fun find(key: String?): OriginNode? {
        val list = key?.let { nodes[it] } ?: return null
        val at = touchedAt
        var best: OriginNode? = null
        var bestDistance = Float.MAX_VALUE
        for (n in list) {
            if (!n.isAttached || n.coordinates?.isAttached != true) continue
            if (n === touched) return n
            if (at == null) {
                best = n
                continue
            }
            val d = n.centerOnScreen()?.let { (it - at).getDistanceSquared() } ?: continue
            if (d < bestDistance) {
                bestDistance = d
                best = n
            }
        }
        return best
    }

    /** Whether [node] is the cell of the picture now in the viewer (it draws nothing meanwhile). */
    fun hides(node: OriginNode): Boolean = hiddenKey == node.key && find(node.key) === node
}

internal class OriginElement(
    private val key: String,
    private val shape: Shape,
    private val alignment: Alignment?,
    private val registry: OriginRegistry,
) : ModifierNodeElement<OriginNode>() {
    override fun create() = OriginNode(key, shape, alignment, registry)

    override fun update(node: OriginNode) {
        node.update(key, shape, alignment, registry)
    }

    override fun equals(other: Any?): Boolean =
        other is OriginElement && other.key == key && other.shape == shape &&
            other.alignment == alignment && other.registry === registry

    override fun hashCode(): Int =
        ((key.hashCode() * 31 + shape.hashCode()) * 31 + (alignment?.hashCode() ?: 0)) * 31 + registry.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "imageViewerOrigin"
        properties["key"] = key
        properties["shape"] = shape
        properties["alignment"] = alignment
    }
}

internal class OriginNode(
    var key: String,
    shape: Shape,
    var alignment: Alignment?,
    private var registry: OriginRegistry,
) : Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode, PointerInputModifierNode {
    var coordinates: LayoutCoordinates? = null
        private set

    var shape: Shape = shape
        private set

    // The measured contour of [shape] at the cell's own size (a path is probed once, not per frame).
    private var contour: Contour? = null
    private var contourSize = IntSize.Zero
    private var contourDensity = 0f

    fun update(key: String, shape: Shape, alignment: Alignment?, registry: OriginRegistry) {
        if (key != this.key || registry !== this.registry) {
            if (isAttached) this.registry.detach(this)
            this.key = key
            this.registry = registry
            if (isAttached) registry.attach(this)
            invalidateDraw()
        }
        if (shape != this.shape) {
            this.shape = shape
            contour = null
        }
        this.alignment = alignment
    }

    /** The cell's clip in its own px (unscaled by any ancestor layer); a path shape is measured once. */
    fun contour(): Contour? {
        if (!isAttached) return null
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        val size = c.size
        if (size.width <= 0 || size.height <= 0) return null
        val density = requireDensity()
        contour?.let { cached ->
            if (contourDensity == density.density) {
                if (contourSize == size) return cached
                // A path is probed, not read: a cell resizing under a flight (cards sliding into a new
                // layout) keeps its last measure while within a quarter of it (its corners are dp anyway).
                if (shape !is CornerBasedShape && near(contourSize.width, size.width) && near(contourSize.height, size.height)) return cached
            }
        }
        val fallback = with(density) { DefaultInner.toPx() }
        val fresh = shape.contourAt(Size(size.width.toFloat(), size.height.toFloat()), density, fallback)
        contour = fresh
        contourSize = size
        contourDensity = density.density
        return fresh
    }

    private fun near(a: Int, b: Int): Boolean = a > 0 && kotlin.math.abs(b - a) <= a / 4

    fun centerOnScreen(): Offset? {
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        return runCatching { c.localToScreen(Offset(c.size.width / 2f, c.size.height / 2f)) }
            .getOrNull()?.takeIf { it.isFinite }
    }

    /**
     * The cell's bounds in [target]'s coordinates, through the screen: the
     * viewer's window and the cell's (the app's, a sheet's) are different
     * windows. Falls back to the shared root when the screen is not known.
     */
    fun boundsIn(target: LayoutCoordinates): Rect? {
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        val viaScreen = runCatching {
            val a = target.screenToLocal(c.localToScreen(Offset.Zero))
            val b = target.screenToLocal(c.localToScreen(Offset(c.size.width.toFloat(), c.size.height.toFloat())))
            if (a.isFinite && b.isFinite) Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)) else null
        }.getOrNull()
        return viaScreen ?: runCatching { target.localBoundingBoxOf(c, clipBounds = false) }.getOrNull()
    }

    override fun onAttach() {
        registry.attach(this)
    }

    override fun onDetach() {
        registry.detach(this)
        coordinates = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        // hiddenKey is snapshot state: every cell redraws when it changes.
        if (registry.hiddenKey != key || !registry.hides(this@OriginNode)) drawContent()
    }

    // Only notes the press (never consumes): the tapped copy of a duplicated key is the origin.
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Press) registry.touch(this)
    }

    override fun onCancelPointerInput() = Unit
}

// ── Controller ──────────────────────────────────────────────────────────────

/** The real [ImageViewerController], provided by [ImageViewerHost]. */
@Stable
internal class ViewerState : ImageViewerController {
    val origins = OriginRegistry()

    var session by mutableStateOf<ViewerSession?>(null)
        private set

    private var nextId = 0

    override fun open(images: List<ViewerImage>, index: Int, originKeys: List<String>) {
        if (images.isEmpty()) return
        val current = session
        // A tap that lands while a picture is still flying home opens the new one at once.
        if (current != null && current.phase != ViewerPhase.Closing) return
        val start = index.coerceIn(0, images.lastIndex)
        // Measure the tapped cell's outline now, on the tap, not in the flight's first frame.
        origins.find(originKeys.getOrNull(start))?.contour()
        // The cell stays drawn until the viewer draws its picture in the same place (see runOpen).
        origins.hiddenKey = null
        session = ViewerSession(nextId++, images, start, originKeys, this)
    }

    /** [s]'s picture is [key]'s cell now (null: no cell is hidden). Ignored once [s] was replaced. */
    fun hide(s: ViewerSession, key: String?) {
        if (session === s) origins.hiddenKey = key
    }

    fun finish(s: ViewerSession) {
        if (session !== s) return
        origins.hiddenKey = null
        session = null
    }
}

internal enum class ViewerPhase { Opening, Open, Dragging, Back, Closing }

/** One frame of the picture: where it is drawn, the part of it visible, that part's outline, its alpha. */
@Immutable
internal class Pose(val image: Rect, val clip: Rect, val contour: Contour, val alpha: Float) {
    fun withAlpha(a: Float) = Pose(image, clip, contour, a)

    companion object {
        fun full(rect: Rect) = Pose(rect, rect, Contour.Zero, 1f)

        fun lerp(a: Pose, b: Pose, t: Float) = Pose(
            lerpRect(a.image, b.image, t),
            lerpRect(a.clip, b.clip, t),
            Contour.lerp(a.contour, b.contour, t),
            lerp(a.alpha, b.alpha, t).coerceIn(0f, 1f),
        )
    }
}

/** The corner of a cell whose outline cannot be read (the cells' own 12dp). */
private val DefaultInner = 12.dp

/**
 * One opening of the viewer: Hero B's single controller. The tap, the drag
 * down, the system back and the tap to close all move the same numbers:
 *
 *  - [reveal] 0 → 1: the picture grows from its cell (its crop and its
 *    corners) into its rest pose; the wash fades in with it.
 *  - [dismiss] 0 → 1 and [offset]: the picture follows the finger (or the back
 *    gesture), shrinks about the touch point, its corners grow back and the
 *    wash fades.
 *  - [flight] 0 → 1 after a commit: from the release pose into the cell of the
 *    page now showing (followed live), or a fade in place when that cell is
 *    gone or motion is reduced.
 *
 * The rest pose is the page's fit (a long picture: a reading column from its
 * top, taller than the screen) clipped to what the page's zoom leaves on
 * screen, so what is dragged or flown home is exactly what was seen.
 */
@Stable
internal class ViewerSession(
    val id: Int,
    val images: List<ViewerImage>,
    val initial: Int,
    val keys: List<String>,
    private val host: ViewerState,
) {
    var phase by mutableStateOf(ViewerPhase.Opening)
        private set
    var page by mutableIntStateOf(initial)
    var reveal by mutableFloatStateOf(0f)
        private set
    var dismiss by mutableFloatStateOf(0f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var flight by mutableFloatStateOf(0f)
        private set

    /** False until the open starts: the window draws nothing and the cell is still the cell's own. */
    var started by mutableStateOf(false)
        private set

    /** System bars hide once the picture has landed and come back as soon as it starts to leave. */
    var barsHidden by mutableStateOf(false)
        private set

    /** Per page: the rest rect of the whole picture (overlay px). A long picture's runs below the screen. */
    val fits = mutableStateMapOf<Int, Rect>()

    /** Pages whose first pixels are on screen (the cell's cached bitmap, or the large size once loaded). */
    val ready = mutableStateMapOf<Int, Boolean>()

    /** Each composed page's zoom, registered by the page itself. */
    val zooms = HashMap<Int, ZoomState>()

    // Set from composition.
    var overlay: LayoutCoordinates? = null
    var size by mutableStateOf(IntSize.Zero)
    var density: Density = Density(1f)
    var reduced = false
    var haptics: HapticFeedback? = null

    /** The frame time while the open waits with the reveal at 0 (read by [pose] so the hero layer re-runs). */
    private var holdFrame by mutableLongStateOf(0L)

    private var pivot = Offset.Zero
    private var release: Pose? = null
    private var releaseBackground = 1f
    private var closeToCell = false
    private var lastCell: Pose? = null
    private var pastThreshold = false
    private var job: Job? = null

    val interactive: Boolean get() = phase == ViewerPhase.Open

    private val thresholdPx: Float
        get() = maxOf(with(density) { 96.dp.toPx() }, size.height * 0.12f)

    private val cornerGrowPx: Float get() = with(density) { 24.dp.toPx() }

    /** The fallback corner (also a notch's own corners, should a cell ever carry one). */
    val innerPx: Float get() = with(density) { DefaultInner.toPx() }

    private val viewport: Rect get() = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())

    /** A long picture taller than the screen: it scrolls, and the chrome gives up its band. */
    fun scrolls(page: Int): Boolean = fits[page]?.let { it.height > size.height + 0.5f } == true

    // ── Geometry ────────────────────────────────────────────────────────────

    /** Page px → the hero layer's px of [page] (undoing its zoom). */
    private fun toHero(page: Int, fit: Rect, p: Offset): Offset {
        val z = zooms[page] ?: return p
        val s = z.scale.coerceAtLeast(0.01f)
        val c = fit.center
        return Offset(c.x + (p.x - c.x - z.offset.x) / s, c.y + (p.y - c.y - z.offset.y) / s)
    }

    /** The part of [fit] the page's zoom leaves on screen, in hero px. */
    private fun visible(page: Int, fit: Rect): Rect {
        val v = viewport
        if (v.width <= 0f || v.height <= 0f) return fit
        val a = toHero(page, fit, Offset(v.left, v.top))
        val b = toHero(page, fit, Offset(v.right, v.bottom))
        val shown = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        return if (shown.overlaps(fit)) fit.intersect(shown) else fit
    }

    private fun rest(page: Int, fit: Rect) = Pose(fit, visible(page, fit), Contour.Zero, 1f)

    /** [pose] (hero px of [page]) as the page's zoom draws it: page px. */
    private fun throughZoom(page: Int, fit: Rect, pose: Pose): Pose {
        val z = zooms[page] ?: return pose
        val s = z.scale
        val o = z.offset
        if (s == 1f && o == Offset.Zero) return pose
        val c = fit.center
        fun map(r: Rect) = Rect(
            c.x + (r.left - c.x) * s + o.x,
            c.y + (r.top - c.y) * s + o.y,
            c.x + (r.right - c.x) * s + o.x,
            c.y + (r.bottom - c.y) * s + o.y,
        )
        return Pose(map(pose.image), map(pose.clip), pose.contour.scale(s), pose.alpha)
    }

    /** The cell of [page] as it is drawn right now, in overlay px: its bounds, crop and outline. */
    private fun cell(page: Int, fit: Rect): Pose? {
        val node = host.origins.find(keys.getOrNull(page)) ?: return null
        val ov = overlay?.takeIf { it.isAttached } ?: return null
        val bounds = node.boundsIn(ov) ?: return null
        if (bounds.width < 1f || bounds.height < 1f) return null
        val own = node.coordinates?.size ?: return null
        // Measured at the cell's own size, then scaled with it (a page shrinking in a back gesture).
        val contour = node.contour()?.scale(bounds.width / own.width.coerceAtLeast(1))
            ?: Contour.uniform(innerPx)
        val aspect = fit.width / fit.height.coerceAtLeast(1f)
        // The cell's own crop anchor; the CDN's `_1c` crops (and ContentScale.Crop) cut the middle.
        val bias = node.alignment?.bias() ?: Offset.Zero
        return Pose(cropRect(aspect, bounds, bias), bounds, contour, 1f)
    }

    private fun fade(rest: Pose, p: Float): Pose {
        val k = if (reduced) 1f else lerp(0.92f, 1f, p)
        val c = rest.clip.center
        return Pose(rest.image.scaleAbout(k, c), rest.clip.scaleAbout(k, c), Contour.Zero, p.coerceIn(0f, 1f))
    }

    /** The pose of [page] drawn at [fit] this frame, in its hero px. Pages other than the current one rest at their fit. */
    fun pose(page: Int, fit: Rect): Pose {
        if (page != this.page) return Pose.full(fit)
        val rest = rest(page, fit)
        if (!started) return rest.withAlpha(0f)
        if (phase == ViewerPhase.Closing) return closingPose(fit, rest)
        val p = reveal
        // While the cell still holds the picture, the layer runs every frame: a sliding cell is followed.
        if (phase == ViewerPhase.Opening && holdFrame < 0L) return rest
        val base = when {
            p >= 1f -> rest
            reduced -> fade(rest, p)
            else -> {
                val from = cell(page, fit)?.also { lastCell = it } ?: lastCell
                if (from != null) Pose.lerp(from, rest, p) else fade(rest, p)
            }
        }
        return applyDismiss(base, zooms[page]?.scale ?: 1f)
    }

    private fun applyDismiss(base: Pose, zoom: Float): Pose {
        val d = dismiss.coerceIn(0f, 1f)
        if (d == 0f && offset == Offset.Zero) return base
        val z = zoom.coerceAtLeast(0.01f)
        val k = 1f - DismissShrink * d
        // The finger's travel and the growing corners are screen px; the layer above scales by the zoom.
        val move = offset / z
        val image = base.image.scaleAbout(k, pivot).translate(move)
        val clip = base.clip.scaleAbout(k, pivot).translate(move)
        return Pose(image, clip, base.contour.scale(k).atLeast(cornerGrowPx * d / z), base.alpha)
    }

    private fun closingPose(fit: Rect, rest: Pose): Pose {
        val from = release ?: rest
        val q = flight
        if (closeToCell) {
            val to = cell(page, fit)?.also { lastCell = it } ?: lastCell
            if (to != null) return Pose.lerp(from, to, q.coerceAtMost(1f))
        }
        val k = if (reduced) 1f else lerp(1f, 0.92f, q)
        val pivot = from.clip.center
        return Pose(
            from.image.scaleAbout(k, pivot),
            from.clip.scaleAbout(k, pivot),
            from.contour.scale(k),
            from.alpha * (1f - q).coerceIn(0f, 1f),
        )
    }

    /** The wash behind the picture. */
    val backgroundAlpha: Float
        get() = when (phase) {
            ViewerPhase.Closing -> releaseBackground * (1f - flight).coerceIn(0f, 1f)
            else -> reveal.coerceIn(0f, 1f) * (1f - dismiss).coerceIn(0f, 1f)
        }

    /** The current page's zoom (1 = fitted), published by that page. */
    var zoomScale by mutableFloatStateOf(1f)

    /**
     * The page indicator and labels below the picture: gone well before the
     * wash, and while the picture is zoomed (it then spreads over their band).
     */
    val chromeAlpha: Float
        get() = ((backgroundAlpha - 0.5f) / 0.5f).coerceIn(0f, 1f) *
            (1f - (zoomScale - 1f) / 0.15f).coerceIn(0f, 1f)

    // ── Motion ──────────────────────────────────────────────────────────────

    /** A back before anything was drawn: just go. */
    fun abort() {
        job?.cancel()
        host.finish(this)
    }

    /**
     * The open: the picture is drawn over its cell at once (identical pixels,
     * identical outline), [windowShown] waits until the viewer's window is
     * on screen, and only a couple of frames later does the cell let go and the
     * picture start to grow. Two windows never leave a frame with neither.
     */
    fun runOpen(scope: CoroutineScope, spec: FiniteAnimationSpec<Float>, windowShown: suspend () -> Unit) {
        if (phase != ViewerPhase.Opening || started) return
        job?.cancel()
        started = true
        job = scope.launch {
            // The reveal is still at 0 while the window comes up: tick the frames so the picture
            // drawn over the cell keeps to it (a card still sliding into its new place).
            val hold = launch { while (true) withFrameNanos { holdFrame = it } }
            try {
                windowShown()
                // Focus can come with the window's first (empty) frame: let the picture's own frames land first.
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                hold.cancel()
            }
            host.hide(this@ViewerSession, keys.getOrNull(page))
            animate(reveal, 1f, animationSpec = spec) { v, _ -> reveal = v }
            phase = ViewerPhase.Open
            barsHidden = true
        }
    }

    /** [at]: the touch in page px. */
    fun startDrag(at: Offset) {
        job?.cancel()
        phase = ViewerPhase.Dragging
        pivot = fits[page]?.let { toHero(page, it, at) } ?: at
        pastThreshold = false
        barsHidden = false
    }

    fun dragBy(delta: Offset) {
        var dy = delta.y
        // Upward the picture follows reluctantly; only a pull down dismisses.
        if (offset.y + dy < 0f) dy *= 0.35f
        offset += Offset(delta.x, dy)
        dismiss = (offset.y / (size.height * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val past = offset.y > thresholdPx
        if (past != pastThreshold) {
            pastThreshold = past
            haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    fun endDrag(velocityY: Float, scope: CoroutineScope, specs: ViewerSpecs) {
        val flingPx = with(density) { 1200.dp.toPx() }
        if (offset.y > thresholdPx || (velocityY > flingPx && offset.y > 0f)) {
            close(scope, specs)
        } else {
            settleOpen(scope, specs.settle)
        }
    }

    fun startBack() {
        job?.cancel()
        phase = ViewerPhase.Back
        pivot = fits[page]?.let { visible(page, it).center } ?: Offset(size.width / 2f, size.height / 2f)
        barsHidden = false
    }

    /** [progress] already eased (the back-gesture curve); [dy] is the finger's vertical travel. */
    fun backProgress(progress: Float, dy: Float) {
        dismiss = BackDismiss * progress.coerceIn(0f, 1f)
        offset = Offset(0f, dy * 0.2f * progress.coerceIn(0f, 1f))
    }

    /** Back to the open picture: a cancelled back or a drag that did not go far enough. */
    fun settleOpen(scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) {
        job?.cancel()
        val fromOffset = offset
        val fromDismiss = dismiss
        val fromReveal = reveal
        job = scope.launch {
            // A back started before the open let go of the cell (then cancelled): the picture is the
            // cell from here on, or the cell would show beside it on the way home.
            host.hide(this@ViewerSession, keys.getOrNull(page))
            animate(0f, 1f, animationSpec = spec) { t, _ ->
                offset = lerp(fromOffset, Offset.Zero, t)
                dismiss = lerp(fromDismiss, 0f, t)
                reveal = lerp(fromReveal, 1f, t.coerceAtMost(1f))
            }
            offset = Offset.Zero
            dismiss = 0f
            reveal = 1f
            phase = ViewerPhase.Open
            barsHidden = true
        }
    }

    fun close(scope: CoroutineScope, specs: ViewerSpecs) {
        if (phase == ViewerPhase.Closing) return
        job?.cancel()
        val fit = fits[page]
        if (fit == null || !started) {
            host.finish(this)
            return
        }
        // What is on screen, zoom and scroll folded in; the zoom lets go in the same frame,
        // so the flight home is one motion from exactly what was seen.
        release = throughZoom(page, fit, pose(page, fit))
        zooms[page]?.snapHome()
        releaseBackground = backgroundAlpha
        lastCell = null
        val target = if (reduced) null else cell(page, fit)
        closeToCell = target != null && target.clip.overlaps(viewport)
        flight = 0f
        phase = ViewerPhase.Closing
        barsHidden = false
        job = scope.launch {
            animate(0f, 1f, animationSpec = if (closeToCell) specs.flight else specs.fade) { v, _ -> flight = v }
            if (closeToCell) haptics?.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            // The cell draws again under the landed picture first; the window goes a frame later.
            host.hide(this@ViewerSession, null)
            withFrameNanos { }
            withFrameNanos { }
            host.finish(this@ViewerSession)
        }
    }

    companion object {
        /** A full dismiss leaves the picture at half its size. */
        const val DismissShrink = 0.5f

        /** The back gesture at its end: the picture at 75%, the wash at half. */
        const val BackDismiss = 0.5f
    }
}

/** The springs a session runs on, resolved from the theme in composition. */
@Immutable
internal class ViewerSpecs(
    val open: FiniteAnimationSpec<Float>,
    val settle: FiniteAnimationSpec<Float>,
    val flight: FiniteAnimationSpec<Float>,
    val fade: FiniteAnimationSpec<Float>,
)
