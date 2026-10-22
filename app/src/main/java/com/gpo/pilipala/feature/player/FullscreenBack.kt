package com.gpo.pilipala.feature.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fullscreen predictive back. The system back gesture drives the fullscreen picture with
 * the finger: it shrinks toward the inline slot it came from (where the inline picture box
 * last sat in the window; a centred 16:9 card when that is unknown or the window will
 * turn), its corners round up to the card radius and the controls fade. A large-screen
 * turned picture also turns upright on the way. Letting go exits fullscreen; the inline
 * picture then starts exactly where the finger left it and springs into its slot (a FLIP
 * over the live inline bounds, so a slot that is still moving, the status bar coming
 * back for example, is followed). Cancelling springs back to the full picture.
 *
 * Everything runs in graphics layers: [fullscreenBackFrame] scales and moves the whole
 * player frame (outside the turn), [fullscreenBackClip] rounds the picture inside the turn.
 */
@Stable
internal class FullscreenBackState {
    /** Eased gesture progress: 0 is the fullscreen picture, 1 is the picture on its target. */
    val progress = Animatable(0f)

    /** After a commit: 1 is where the picture was let go, 0 is at rest in its inline slot. */
    val settle = Animatable(0f)

    /**
     * Fullscreen was just entered from the inline slot and the picture is to grow out of it:
     * until [settle] has been set to run from there, it is drawn where it was ([settleNow] 1).
     */
    internal var enterPending by mutableStateOf(false)

    /** [settle] as drawn ([enterPending] holds it at the start). */
    internal val settleNow: Float get() = if (enterPending) 1f else settle.value

    /**
     * Raised with the commit, before the progress drops back to 0: the chrome stays invisible
     * and its reveal snaps shut instead of folding up under the landing picture. The player
     * lowers it a couple of frames later, once the reveal has snapped.
     */
    var snapChrome by mutableStateOf(false)

    /** From the commit until the picture rests in its slot: the controls stay hidden and uncomposed. */
    val landing: Boolean
        get() = enterPending || settle.isRunning || settle.value != 0f

    /** True while the gesture is held or anything still moves (no bounds are trustworthy then). */
    val busy: Boolean
        get() = enterPending || progress.value != 0f || settle.value != 0f || progress.isRunning || settle.isRunning

    // Where the inline picture box last sat (window px), the window size then, and its corner.
    internal var inlineBox: Rect? = null
    internal var inlineWindow: IntSize = IntSize.Zero
    internal var inlineRadius: Float = 0f

    // Per gesture: the target (null: a centred card) and the angle a turned picture starts from.
    internal var target: Rect? = null
    internal var targetRadius: Float = 0f
    internal var turnFrom: Float = 0f

    // Per landing: where the picture was let go and the corner it lands with.
    internal var release: BackPose? = null
    internal var restRadius: Float = 0f

    /**
     * Starts a back ride driven by a finger on the picture (swipe down from the middle): send
     * raw progress 0..1, close the channel to exit, close it with a CancellationException to
     * spring back. False when fullscreen back is not animated right now (the caller then exits
     * plainly). Set by FullscreenBackHandler.
     */
    internal var startSwipe: ((kotlinx.coroutines.channels.ReceiveChannel<Float>) -> Boolean)? = null

    // Set from composition, for computing the release pose at commit time.
    internal var frame: LayoutCoordinates? = null
    internal var spec: BackFrameSpec? = null
    internal var density: Density? = null

    /**
     * Fullscreen entered with the window as it was: the picture grows out of the inline slot
     * it sat in ([inlineBox], the video fitted in it at [aspect]), the landing run the other
     * way. False when the slot is not known for this [window] (the caller then simply cuts).
     */
    fun beginEnter(window: IntSize, aspect: Float): Boolean {
        val box = inlineBox?.takeIf { inlineWindow == window && it.width > 1f && it.height > 1f } ?: return false
        val a = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        release = BackPose(centre = box.center, width = min(box.width, box.height * a), radius = inlineRadius)
        restRadius = 0f
        enterPending = true
        return true
    }

    fun captureInline(coords: LayoutCoordinates, radius: Float, window: IntSize) {
        if (!coords.isAttached) return
        val p = coords.positionInWindow()
        inlineBox = Rect(p, coords.size.toSize())
        inlineWindow = window
        inlineRadius = radius
    }

    /** The picture's visual pose as it is drawn right now. */
    fun poseNow(): BackPose? {
        val coords = frame?.takeIf { it.isAttached } ?: return null
        val s = spec ?: return null
        val d = density ?: return null
        return with(d) { solve(this@FullscreenBackState, s, coords.size.toSize(), coords.positionInWindow()).pose }
    }
}

/** Centre and width of the visible picture (unrotated), and its visual corner radius, all in window px. */
@Immutable
internal data class BackPose(val centre: Offset, val width: Float, val radius: Float)

/**
 * What the player frame looks like from inside, so the layers can find the picture.
 * [fill]: fullscreen frame (the picture box is the whole frame) vs inline (a 16:9 box on top).
 * [swapped]: the frame is laid out turned (large-screen turned picture). [reveal]: the
 * fullscreen picture card's reveal, null without the card.
 */
internal class BackFrameSpec(
    val fill: Boolean,
    val swapped: Boolean,
    val angle: () -> Float,
    val aspect: () -> Float,
    val reveal: (() -> Float)?,
    val topBand: Dp,
    val bottomBand: Dp,
    val minScale: Float,
)

private class Solved(
    val active: Boolean,
    val scale: Float,
    val translation: Offset,
    val clip: Rect,
    val clipRadius: Float,
    val pose: BackPose,
)

/**
 * The geometry, shared by both layers. Coordinates: window px for poses and targets; the
 * frame is [frame] big at [origin]; its content (inside the turn) is the frame, or the
 * frame turned on its side when [BackFrameSpec.swapped].
 */
private fun Density.solve(st: FullscreenBackState, spec: BackFrameSpec, frame: Size, origin: Offset): Solved {
    val g = st.progress.value.coerceAtLeast(0f)
    val s = st.settleNow
    val w = frame.width
    val h = frame.height
    val fw = if (spec.swapped) h else w
    val fh = if (spec.swapped) w else h
    val aspect = spec.aspect().takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    // The picture box inside the content, and the video fitted in it.
    val boxH = if (spec.fill) fh else fw * 9f / 16f
    val boxRelY = if (spec.fill) 0f else boxH / 2f - fh / 2f
    val vw = min(fw, boxH * aspect)
    val vh = vw / aspect
    val cardReveal = spec.reveal?.invoke() ?: 0f
    val (cardScale, cardDy) = if (spec.reveal != null) {
        cardPose(cardReveal, aspect, fw, fh, spec.topBand.toPx(), spec.bottomBand.toPx(), CardGap.toPx(), spec.minScale)
    } else {
        1f to 0f
    }
    val rel = Offset(0f, boxRelY + cardDy)
    val mid = origin + Offset(w / 2f, h / 2f)
    val angle = spec.angle()
    val fit = rotFit(fw, fh, w, h, angle)
    // Where the picture is drawn before this layer moves it.
    val inCentre = mid + rotate(rel, angle) * fit
    val inWidth = vw * cardScale * fit

    val release = st.release
    var pose: BackPose
    val morph: Float
    if (s != 0f && release != null) {
        pose = BackPose(
            centre = lerp(inCentre, release.centre, s),
            width = lerp(inWidth, release.width, s),
            radius = lerp(st.restRadius, release.radius, s).coerceAtLeast(0f),
        )
        morph = s.coerceIn(0f, 1f)
    } else {
        // The undriven pose: where the picture would be with the gesture at rest.
        val a0 = if (g > 0f) st.turnFrom else angle
        val fit0 = rotFit(fw, fh, w, h, a0)
        val c0 = mid + rotate(rel, a0) * fit0
        val w0 = vw * cardScale * fit0
        val target = st.target ?: centredCard(origin, w, h)
        val tw = min(target.width, target.height * aspect)
        val cardR = CardRadius.toPx()
        pose = BackPose(
            centre = lerp(c0, target.center, g),
            // A picture turning upright never grows past what fits the window at its angle.
            width = min(lerp(w0, tw, g), turnedFitWidth(w, h, aspect, angle)),
            // Up to the card radius early, then over to the slot's own corner as it lands.
            radius = cardR * (1f - g) * min(1f, 3f * g) + st.targetRadius * g,
        )
        morph = min(1f, 2f * g)
    }
    val k = if (inWidth > 1f) pose.width / inWidth else 1f
    val translation = pose.centre - origin - (inCentre - origin) * k
    // With the controls up the card's own rounded clip may be the corner that shows (it rides
    // inside this layer, so it shrinks with it); the landing starts from whichever is rounder.
    val cardCorner = CardRadius.toPx() * cardReveal.coerceIn(0f, 1f) * fit * k
    if (cardCorner > pose.radius) pose = pose.copy(radius = cardCorner)
    // Clip, in content px: from the whole content to the picture's own rect.
    val vc = Offset(fw / 2f, fh / 2f + boxRelY + cardDy)
    val hw = vw * cardScale / 2f
    val hh = vh * cardScale / 2f
    val clip = lerp(Rect(0f, 0f, fw, fh), Rect(vc.x - hw, vc.y - hh, vc.x + hw, vc.y + hh), morph)
    val clipRadius = pose.radius / max(0.05f, fit * k)
    return Solved(
        active = g != 0f || (s != 0f && release != null),
        scale = k,
        translation = translation,
        clip = clip,
        clipRadius = clipRadius,
        pose = pose,
    )
}

/** The widest [aspect] picture whose bounds, turned by [degrees], still fit [w] x [h]. */
private fun turnedFitWidth(w: Float, h: Float, aspect: Float, degrees: Float): Float {
    val r = Math.toRadians(degrees.toDouble())
    val c = abs(cos(r)).toFloat()
    val sn = abs(sin(r)).toFloat()
    val byWidth = w / max(0.001f, c + sn / aspect)
    val byHeight = h / max(0.001f, sn + c / aspect)
    return min(byWidth, byHeight)
}

/** The fallback target: a 16:9 card in the middle of the frame. */
private fun centredCard(origin: Offset, w: Float, h: Float): Rect {
    val cw = min(w * FallbackCardFraction, h * FallbackCardFraction * 16f / 9f)
    val ch = cw * 9f / 16f
    return Rect(origin.x + (w - cw) / 2f, origin.y + (h - ch) / 2f, origin.x + (w + cw) / 2f, origin.y + (h + ch) / 2f)
}

private const val FallbackCardFraction = 0.72f

/** Clockwise rotation in screen space (y down), as rotationZ draws it. */
private fun rotate(v: Offset, degrees: Float): Offset {
    if (degrees == 0f) return v
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val sn = sin(r).toFloat()
    return Offset(v.x * c - v.y * sn, v.x * sn + v.y * c)
}

/**
 * How much a [cw] x [ch] box turned by [degrees] is scaled so its bounds fit [w] x [h]
 * (never up): the same fit [turnedContent] and the inline turn use.
 */
internal fun rotFit(cw: Float, ch: Float, w: Float, h: Float, degrees: Float): Float {
    if (degrees == 0f) return minOf(1f, if (cw > 0f) w / cw else 1f, if (ch > 0f) h / ch else 1f)
    val r = Math.toRadians(degrees.toDouble())
    val c = abs(cos(r)).toFloat()
    val sn = abs(sin(r)).toFloat()
    val bw = cw * c + ch * sn
    val bh = cw * sn + ch * c
    return if (bw > 0f && bh > 0f) minOf(1f, w / bw, h / bh) else 1f
}

/**
 * Scales and moves the whole player frame (outside its turn) along the back pose. The
 * frame's coordinates come from its own placement, so the first frame in a new layout (the
 * inline slot right after the commit) already lands from the right place.
 */
internal fun Modifier.fullscreenBackFrame(st: FullscreenBackState, spec: BackFrameSpec): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) {
        val coords = coordinates
        if (coords != null) st.frame = coords
        p.placeWithLayer(0, 0) {
            // Read first so the layer follows both clocks even while it is idle.
            val g = st.progress.value
            val s = st.settleNow
            val c = coords?.takeIf { it.isAttached }
            val solved = if (c != null && (g != 0f || s != 0f)) solve(st, spec, c.size.toSize(), c.positionInWindow()) else null
            if (solved == null || !solved.active) {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                return@placeWithLayer
            }
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = solved.scale
            scaleY = solved.scale
            translationX = solved.translation.x
            translationY = solved.translation.y
        }
    }
}

/** Rounds the picture (inside the frame's turn, so the corners turn with it) along the back pose. */
internal fun Modifier.fullscreenBackClip(st: FullscreenBackState, spec: BackFrameSpec): Modifier = graphicsLayer {
    val coords = st.frame?.takeIf { it.isAttached }
    val g = st.progress.value
    val s = st.settleNow
    val solved = if (coords != null && (g != 0f || s != 0f)) solve(st, spec, coords.size.toSize(), coords.positionInWindow()) else null
    if (solved == null || !solved.active) {
        clip = false
        shape = RectangleShape
        return@graphicsLayer
    }
    shape = BackClipShape(solved.clip, solved.clipRadius)
    clip = true
}

private class BackClipShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius, radius)))
}

/**
 * The fullscreen back handler (replaces the plain BackHandler, at the same place in the
 * player's composition, so the side panel's handler still outranks it and it still
 * outranks the page's and the scaffold's).
 *
 * [animate]: the picture follows the finger (the full chrome, whose fullscreen picture is
 * a TextureView). Otherwise back waits for the release and exits as before. [onExit] gets
 * whether the exit was animated.
 */
@Composable
internal fun FullscreenBackHandler(
    state: FullscreenBackState,
    enabled: Boolean,
    animate: Boolean,
    turnAngle: Animatable<Float, AnimationVector1D>,
    turnTarget: Float,
    windowSize: () -> IntSize,
    onExit: (animated: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestAnimate by rememberUpdatedState(animate)
    val latestTurnTarget by rememberUpdatedState(turnTarget)
    val latestExit by rememberUpdatedState(onExit)
    val latestWindow by rememberUpdatedState(windowSize)
    val landSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Standard)
    val cancelSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Standard)
    // One ride for both drivers: the system back gesture and the swipe down from the middle of
    // the picture (playerGestures), which feeds its own progress through [startSwipe].
    suspend fun ride(events: kotlinx.coroutines.flow.Flow<Float>) {
        // The inline slot is a valid target only in the window it was measured in: when the
        // window is going to turn back (a phone or tablet that really rotated) the picture
        // heads for a centred card and the system's rotation takes it from there.
        val window = latestWindow()
        val inline = state.inlineBox?.takeIf { state.inlineWindow == window && it.width > 1f && it.height > 1f }
        state.target = inline
        state.targetRadius = if (inline != null) state.inlineRadius else with(density) { CardRadius.toPx() }
        // A picture turned on its side turns upright with the finger (and back on cancel).
        val from = turnAngle.value
        state.turnFrom = from
        var moved = false
        try {
            events.collect { raw ->
                val g = YoinMotion.backGestureEasing.transform(raw.coerceIn(0f, 1f))
                if (g > 0f) moved = true
                state.progress.snapTo(g)
                if (from != 0f) turnAngle.snapTo(from * (1f - g))
            }
        } catch (c: CancellationException) {
            // The handler coroutine is dying; spring back on the player's scope.
            scope.launch {
                coroutineScope {
                    launch { state.progress.animateTo(0f, cancelSpec) }
                    if (from != 0f) launch { turnAngle.animateTo(latestTurnTarget, cancelSpec) }
                }
            }
            throw c
        }
        if (!moved && inline == null) {
            // A back with no gesture (3-button, keyboard, before Android 14) while the window
            // is going to turn: the plain exit, the system's rotation carries it.
            state.progress.snapTo(0f)
            latestExit(false)
            return
        }
        // No gesture but the same window: the full picture lands in its slot all the same.
        // Commit: the inline picture lands from exactly where it was let go. The chrome is
        // held invisible first, so no frame shows it back at full strength (the fade above
        // only follows the progress, which drops to 0 here).
        val pose = if (inline != null) state.poseNow() else null
        state.snapChrome = true
        if (pose != null) {
            state.release = pose
            state.restRadius = state.inlineRadius
            state.settle.snapTo(1f)
        }
        state.progress.snapTo(0f)
        latestExit(true)
        if (pose != null) {
            // Every landing frame re-solves against the live inline bounds, so a slot that is
            // still settling (the status bar coming back) is followed.
            scope.launch {
                state.settle.animateTo(0f, landSpec)
                state.release = null
            }
        }
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        if (!latestAnimate) {
            events.collect { }
            latestExit(false)
            return@PredictiveBackHandler
        }
        ride(events.map { it.progress })
    }
    // The swipe: a closed channel commits, one closed with a CancellationException springs back.
    val latestEnabled by rememberUpdatedState(enabled && animate)
    androidx.compose.runtime.DisposableEffect(state) {
        state.startSwipe = { channel ->
            if (latestEnabled && !state.busy) {
                scope.launch { ride(channel.consumeAsFlow()) }
                true
            } else {
                false
            }
        }
        onDispose { state.startSwipe = null }
    }
}
