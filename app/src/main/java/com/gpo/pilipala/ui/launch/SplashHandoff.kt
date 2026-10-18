package com.gpo.pilipala.ui.launch

import com.gpo.pilipala.ui.theme.SystemBars
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import android.os.SystemClock
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.graphics.shapes.Morph
import com.gpo.pilipala.R
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The splash → home handoff (PiliPaw storyboard, part B).
 *
 * The system splash ends on the static mark (drawable/splash_icon's last frame is
 * ic_launcher_foreground). [MainActivity] hands its geometry over in [begin];
 * [SplashHandoffOverlay] repaints that exact frame in Compose, removes the platform splash
 * under it, and then either
 * - **flies**: the forest iris carries the mark along an arc into the home header's avatar
 *   slot, shrinking as it goes and morphing Circle → Cookie9Sided; the feed rises out from
 *   under its receding edge ([splashReveal]) and the account avatar fades in over the mark
 *   ([splashAvatarSlot]), or
 * - **fades**: off a link, shortcut or widget, or when the slot does not report in time.
 *
 * Everything is driven by springs and read in the draw / layer phase only.
 */
@Stable
class SplashHandoff(window: Window) {
    enum class Phase { Waiting, Flying, Fading, Done }

    var phase by mutableStateOf(Phase.Waiting)
        private set

    /** The flight has its target and is moving (Flying only). */
    internal var flightStarted by mutableStateOf(false)
        private set
    private var flightStartMs = 0L

    // Window coordinates, px.
    internal var iconCenter = Offset.Zero
        private set

    /** px per unit of the 108 viewport the mark is drawn in; 0 when the splash showed no icon. */
    internal var iconUnit = 0f
        private set
    private var windowSize = Size.Zero
    private var r0 = 0f

    /** The home header's 40dp avatar cookie in window coordinates (reported by [splashAvatarSlot]). */
    internal var avatarBounds by mutableStateOf<Rect?>(null)
    internal var target: Rect? = null
        private set

    internal val flight = Animatable(0f)
    internal val cookie = Animatable(0f)
    internal val fade = Animatable(1f)
    internal val avatarIn = Animatable(0f)
    internal val avatarScale = Animatable(AvatarStartScale)

    private val bars = BarTint(window)
    private var removeSplash: (() -> Unit)? = null

    /**
     * Below Android 12 the compat splash is drawn inside the app's own window, under the app's
     * bar appearance: light bar icons over the forest field until the handoff lets go.
     */
    fun coverBars() = bars.cover()

    /**
     * The splash is done (its animation has finished). [fly] = launched from the launcher and the
     * home header is the destination; otherwise it fades. [iconCenter] and [iconUnit] describe the
     * splash's mark in window px; [windowSize] is the window's size; [remove] drops the splash.
     */
    fun begin(fly: Boolean, iconCenter: Offset, iconUnit: Float, windowSize: Size, density: Float, remove: () -> Unit) {
        if (phase != Phase.Waiting) {
            remove()
            bars.releaseAll()
            return
        }
        this.iconCenter = iconCenter
        this.iconUnit = iconUnit
        this.windowSize = windowSize
        r0 = maxOf(
            hypot(iconCenter.x, iconCenter.y),
            hypot(windowSize.width - iconCenter.x, iconCenter.y),
            hypot(iconCenter.x, windowSize.height - iconCenter.y),
            hypot(windowSize.width - iconCenter.x, windowSize.height - iconCenter.y),
        ) + IrisMargin.value * density
        var removed = false
        removeSplash = {
            if (!removed) {
                removed = true
                remove()
            }
        }
        phase = if (fly && !diverted && iconUnit > 0f) Phase.Flying else Phase.Fading
    }

    private var diverted = false

    /** A link is opening a page over the home: a handoff that has not started yet only fades. */
    fun divert() {
        diverted = true
    }

    /**
     * The window changed size (rotation, resize) while the overlay is up: the iris starts from
     * whatever covers the new window, so no corner of the home shows.
     */
    internal fun fitTo(size: Size, density: Float) {
        if (size == windowSize || size.width <= 0f) return
        windowSize = size
        r0 = maxOf(
            hypot(iconCenter.x, iconCenter.y),
            hypot(size.width - iconCenter.x, iconCenter.y),
            hypot(iconCenter.x, size.height - iconCenter.y),
            hypot(size.width - iconCenter.x, size.height - iconCenter.y),
        ) + IrisMargin.value * density
    }

    /** No handoff (animations are off, or the activity came back): straight to the end state. */
    fun skip(remove: () -> Unit) {
        remove()
        removeSplash = null
        finish()
    }

    /** The splash never handed over (the system showed none): stand the reveal hooks down. */
    fun abandonIfWaiting() {
        if (phase != Phase.Waiting) return
        bars.releaseAll()
        phase = Phase.Done
    }

    private fun finish() {
        removeSplash?.invoke()
        removeSplash = null
        bars.releaseAll()
        phase = Phase.Done
    }

    /** The overlay's script; runs once its first frame (the splash's last) is composed. */
    internal suspend fun run(fastEffects: FiniteAnimationSpec<Float>, defaultEffects: FiniteAnimationSpec<Float>): Unit = coroutineScope {
        // That frame is on screen now: drop the platform splash under it, no seam.
        withFrameNanos { }
        removeSplash?.invoke()
        bars.cover()
        if (phase == Phase.Flying) {
            val slot = withTimeoutOrNull(SlotWaitMs) { snapshotFlow { avatarBounds }.filterNotNull().first() }
            if (slot == null) phase = Phase.Fading else target = slot
        }
        if (phase == Phase.Fading) {
            launch {
                snapshotFlow { fade.value < 0.5f }.first { it }
                bars.releaseAll()
            }
            fade.animateTo(0f, fastEffects)
            finish()
            coroutineContext.cancelChildren()
            return@coroutineScope
        }

        flightStartMs = SystemClock.uptimeMillis()
        flightStarted = true
        // Bar icons go back to the app's once the iris has let go of that edge.
        launch {
            snapshotFlow { irisEdgeClear(flight.value, top = true) }.first { it }
            bars.releaseStatus()
        }
        launch {
            snapshotFlow { irisEdgeClear(flight.value, top = false) }.first { it }
            bars.releaseNavigation()
        }
        launch { flight.animateTo(1f, FlightSpring) }
        launch {
            snapshotFlow { flight.value >= CookieStartAt }.first { it }
            cookie.animateTo(1f, CookieSpring)
        }
        snapshotFlow { flight.value >= SeatedAt }.first { it }
        delay(AvatarLeadMs)
        val scale = launch { avatarScale.animateTo(1f, AvatarScaleSpring) }
        avatarIn.animateTo(1f, defaultEffects)
        scale.join()
        finish()
        coroutineContext.cancelChildren()
    }

    private fun slot(): Rect? = avatarBounds ?: target

    /** Iris centre at flight progress [p]: a quadratic arc that rises first, then turns into the slot. */
    internal fun irisCenter(p: Float, slot: Rect): Offset {
        val c0 = iconCenter
        val c1 = slot.center
        val q = Offset(lerp(c0.x, c1.x, 0.07f), lerp(c1.y, c0.y, 0.11f))
        val u = 1f - p
        return c0 * (u * u) + q * (2f * u * p) + c1 * (p * p)
    }

    /** Iris radius, log-interpolated so the shrink reads even: whole window → the 40dp cookie. */
    internal fun irisRadius(p: Float, slot: Rect): Float = r0 * (slot.width / 2f / r0).pow(p)

    /** The mark's unit, log-interpolated: splash size → 72 units across the cookie. */
    internal fun markUnit(p: Float, slot: Rect): Float = iconUnit * (slot.width / 72f / iconUnit).pow(p)

    private fun irisEdgeClear(p: Float, top: Boolean): Boolean {
        val slot = slot() ?: return false
        val c = irisCenter(p, slot)
        val r = irisRadius(p, slot)
        return if (top) c.y - r > 0f else c.y + r < windowSize.height
    }

    /**
     * When the iris's receding edge passes [point] (window px), less a short lead, counted from
     * now. Samples the same closed-form spring the flight runs on, so the feed rises in the order
     * the green uncovers it — no hand-written stagger table.
     */
    internal fun revealDelayMs(point: Offset): Long {
        val slot = target ?: return 0L
        var at = HorizonMs
        var t = 0L
        while (t <= HorizonMs) {
            val p = flightAt(t / 1000f)
            if ((point - irisCenter(p, slot)).getDistance() > RevealEdge * irisRadius(p, slot)) {
                at = t
                break
            }
            t += 4
        }
        val elapsed = SystemClock.uptimeMillis() - flightStartMs
        return (at - RevealLeadMs - elapsed).coerceAtLeast(0L)
    }

    /** Closed form of [FlightSpring] from rest at 0 (mass 1, underdamped). */
    private fun flightAt(t: Float): Float {
        val w0 = sqrt(FlightStiffness)
        val zw = FlightDamping * w0
        val wd = w0 * sqrt(1f - FlightDamping * FlightDamping)
        return 1f - exp(-zw * t) * (cos(wd * t) + zw / wd * sin(wd * t))
    }

    /**
     * Light bar icons while the forest field covers that bar; the app's own appearance after.
     * On release the bar goes back to [SystemBars]; on Android 12/12L core-splashscreen rewrites
     * the appearance from the window theme when the splash goes, which ApplySystemBars corrects
     * because the held flag flips after that.
     */
    private class BarTint(window: Window) {
        private val controller = WindowCompat.getInsetsController(window, window.decorView)

        fun cover() {
            SystemBars.heldStatus = true
            SystemBars.heldNavigation = true
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }

        // Letting go hands the bar back to ApplySystemBars, which applies whatever the pages on
        // screen want now (a video page's light icons included), not a value saved at launch.
        fun releaseStatus() {
            SystemBars.heldStatus = false
        }

        fun releaseNavigation() {
            SystemBars.heldNavigation = false
        }

        fun releaseAll() {
            releaseStatus()
            releaseNavigation()
        }
    }

    private companion object {
        const val FlightStiffness = 180f
        const val FlightDamping = 0.85f
        val FlightSpring = spring<Float>(dampingRatio = FlightDamping, stiffness = FlightStiffness)
        /** Circle → Cookie9Sided with a twist; peaks ~1.15 (deeper lobes) before it settles. */
        val CookieSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 700f)
        val AvatarScaleSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 800f)
        const val AvatarStartScale = 0.85f
        const val CookieStartAt = 0.55f
        const val SeatedAt = 0.985f
        const val AvatarLeadMs = 40L
        const val SlotWaitMs = 300L
        const val HorizonMs = 700L
        const val RevealLeadMs = 40L
        const val RevealEdge = 0.94f
        val IrisMargin = 8.dp
    }
}

val LocalSplashHandoff = staticCompositionLocalOf<SplashHandoff?> { null }

/**
 * Draws the handoff over the whole app while it runs: the forest field and the mark, exactly
 * where the splash left them, then the flight (or the fade). Place it last in the root Box.
 */
@Composable
fun SplashHandoffOverlay(handoff: SplashHandoff) {
    val phase = handoff.phase
    if (phase != SplashHandoff.Phase.Flying && phase != SplashHandoff.Phase.Fading) return
    val field = colorResource(R.color.splash_background)
    // Same source as the splash's last frame.
    val mark = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_launcher_foreground))
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val fastEffects = YoinMotion.fastEffectsSpec<Float>()
    val defaultEffects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(handoff) { handoff.run(fastEffects, defaultEffects) }
    val density = LocalDensity.current.density
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin.value = it.positionInWindow() }
            .onSizeChanged { handoff.fitTo(Size(it.width.toFloat(), it.height.toFloat()), density) }
            // Under the green the home is hidden but live: taps must not reach it (under a second).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) {
        val o = origin.value
        val slot = if (handoff.flightStarted) handoff.avatarBounds ?: handoff.target else null
        if (slot == null) {
            // The splash's last frame, or its fade.
            val alpha = if (handoff.phase == SplashHandoff.Phase.Fading) handoff.fade.value.coerceIn(0f, 1f) else 1f
            drawRect(field, alpha = alpha)
            if (handoff.iconUnit > 0f) drawMark(mark, handoff.iconCenter - o, handoff.iconUnit, alpha)
            return@Canvas
        }
        val p = handoff.flight.value
        val c = handoff.irisCenter(p, slot) - o
        val r = handoff.irisRadius(p, slot)
        val m = handoff.cookie.value
        path.rewind()
        morph.toPath(m, path)
        matrix.reset()
        matrix.translate(c.x, c.y)
        matrix.rotateZ(-45f * (1f - m))
        matrix.scale(2f * r, 2f * r)
        matrix.translate(-0.5f, -0.5f)
        path.transform(matrix)
        // The account avatar fades in under it as the mark lets go.
        val alpha = (1f - handoff.avatarIn.value).coerceIn(0f, 1f)
        clipPath(path) {
            drawRect(field, alpha = alpha)
            drawMark(mark, c, handoff.markUnit(p, slot), alpha)
        }
    }
}

private fun DrawScope.drawMark(mark: VectorPainter, center: Offset, unit: Float, alpha: Float) {
    val side = 108f * unit
    translate(center.x - side / 2f, center.y - side / 2f) {
        with(mark) { draw(Size(side, side), alpha = alpha) }
    }
}

/**
 * A home element that rises out from under the handoff's receding iris: hidden while the
 * flight runs, then (once the green has passed its centre) [lift] up into place, scaling
 * 0.96 → 1 on the spatial spring and fading in on the fast effects spring. Inert in every
 * other phase, so it costs nothing after launch.
 */
fun Modifier.splashReveal(lift: Dp = 16.dp): Modifier = composed {
    val handoff = LocalSplashHandoff.current
    if (handoff == null || handoff.phase == SplashHandoff.Phase.Done || handoff.phase == SplashHandoff.Phase.Fading) {
        return@composed this
    }
    val rise = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    val center = remember { mutableStateOf<Offset?>(null) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.fastEffectsSpec<Float>()
    LaunchedEffect(handoff) {
        snapshotFlow { handoff.flightStarted }.first { it }
        val c = snapshotFlow { center.value }.filterNotNull().first()
        delay(handoff.revealDelayMs(c))
        launch { shown.animateTo(1f, effects) }
        rise.animateTo(1f, spatial)
    }
    this
        .onGloballyPositioned { center.value = it.boundsInWindow().center }
        .graphicsLayer {
            if (handoff.phase == SplashHandoff.Phase.Flying) {
                val k = rise.value
                translationY = (1f - k) * lift.toPx()
                val s = 0.96f + 0.04f * k
                scaleX = s
                scaleY = s
                alpha = shown.value
            }
        }
}

/**
 * The home header's avatar: reports its bounds as the flight's landing slot and stays hidden
 * under the arriving mark, then fades and scales in over it.
 */
fun Modifier.splashAvatarSlot(): Modifier = composed {
    val handoff = LocalSplashHandoff.current
    if (handoff == null || handoff.phase == SplashHandoff.Phase.Done) return@composed this
    this
        .onGloballyPositioned { handoff.avatarBounds = it.boundsInWindow() }
        .graphicsLayer {
            if (handoff.phase == SplashHandoff.Phase.Flying) {
                alpha = handoff.avatarIn.value.coerceIn(0f, 1f)
                val s = handoff.avatarScale.value
                scaleX = s
                scaleY = s
            }
        }
}
