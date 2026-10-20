package com.gpo.pilipala.feature.media

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pinch / double-tap zoom of one viewer page. The picture is laid out at its
 * rest rect; [scale] grows it about that rect's centre and [offset] moves it.
 * The picture always covers the screen on an axis where it is larger than the
 * screen and stays centred on one where it is not: a long picture, taller
 * than the screen at rest, is read by panning at 1× (offset 0 = its top).
 */
@Stable
internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var job: Job? = null

    val zoomed: Boolean get() = scale > 1.01f

    fun stop() {
        job?.cancel()
        job = null
    }

    fun snapHome() {
        stop()
        scale = 1f
        offset = Offset.Zero
    }

    /** What a double tap zooms to: 2.5×, or enough to fill the screen's width (long pictures). */
    fun doubleTapScale(fit: Rect, viewport: Size): Float {
        if (fit.width <= 0f) return 2.5f
        val fillWidth = viewport.width / fit.width
        return maxOf(2.5f, if (fillWidth > 1.2f) fillWidth else 0f).coerceAtMost(maxScale(fit, viewport))
    }

    fun maxScale(fit: Rect, viewport: Size): Float {
        if (fit.width <= 0f || fit.height <= 0f) return 5f
        return maxOf(5f, viewport.width / fit.width * 2f, viewport.height / fit.height * 2f)
    }

    fun clamp(o: Offset, s: Float, fit: Rect, viewport: Size): Offset {
        fun axis(v: Float, center: Float, extent: Float, port: Float): Float =
            if (extent <= port) port / 2f - center else v.coerceIn(port - center - extent / 2f, extent / 2f - center)
        return Offset(
            axis(o.x, fit.center.x, fit.width * s, viewport.width),
            axis(o.y, fit.center.y, fit.height * s, viewport.height),
        )
    }

    /**
     * Whether a pan towards [direction] would move the picture: zoomed, or a
     * long picture at 1×. At an edge it would not, and the pager (sideways)
     * or the dismiss (down, at a long picture's top) takes over.
     */
    fun canPan(direction: Offset, fit: Rect, viewport: Size): Boolean {
        val horizontal = abs(direction.x) > abs(direction.y)
        val step = if (horizontal) Offset(if (direction.x > 0) 1f else -1f, 0f) else Offset(0f, if (direction.y > 0) 1f else -1f)
        val moved = clamp(offset + step, scale, fit, viewport) - offset
        return if (horizontal) abs(moved.x) > 0.25f else abs(moved.y) > 0.25f
    }

    fun panBy(delta: Offset, fit: Rect, viewport: Size) {
        offset = clamp(offset + delta, scale, fit, viewport)
    }

    /** A pinch step around [centroid] (overlay px). Below 1× the picture floats free; it settles on release. */
    fun transform(centroid: Offset, zoom: Float, pan: Offset, fit: Rect, viewport: Size) {
        if (zoom.isNaN() || zoom <= 0f) return
        val s0 = scale
        val s1 = (s0 * zoom).coerceIn(0.75f, maxScale(fit, viewport) * 1.15f)
        val rel = centroid - fit.center
        val o1 = rel - (rel - offset) * (s1 / s0) + pan
        scale = s1
        offset = if (s1 >= 1f) clamp(o1, s1, fit, viewport) else o1
    }

    fun toggle(at: Offset, fit: Rect, viewport: Size, scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) {
        if (zoomed) {
            // Back to 1× about the tap: a long picture stays where it was being read.
            val rel = at - fit.center
            val o = rel - (rel - offset) * (1f / scale)
            animateTo(1f, clamp(o, 1f, fit, viewport), scope, spec)
        } else {
            // About the tap, from where the picture is now: a long picture read halfway down zooms there.
            val target = doubleTapScale(fit, viewport)
            val rel = at - fit.center
            val o = rel - (rel - offset) * (target / scale.coerceAtLeast(0.01f))
            animateTo(target, clamp(o, target, fit, viewport), scope, spec)
        }
    }

    /**
     * The page's rest rect changed under the picture (a rotation, the window
     * resized, the aspect learned late): the same spot stays in view, inside
     * the new bounds. A running zoom or fling keeps going.
     */
    fun refit(old: Rect, fit: Rect, viewport: Size) {
        if (job?.isActive == true) return
        if (old == fit || old.width <= 0f || old.height <= 0f) {
            offset = clamp(offset, scale, fit, viewport)
            return
        }
        val moved = Offset(offset.x * fit.width / old.width, offset.y * fit.height / old.height)
        offset = clamp(moved, scale, fit, viewport)
    }

    /** After a pinch: back inside [1, max] and the bounds. */
    fun settle(fit: Rect, viewport: Size, scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) {
        val s = scale.coerceIn(1f, maxScale(fit, viewport))
        // Back up to 1× about the screen's centre: a long picture stays where it was being read.
        val rel = Offset(viewport.width / 2f, viewport.height / 2f) - fit.center
        val about = if (s != scale) rel - (rel - offset) * (s / scale.coerceAtLeast(0.01f)) else offset
        val o = clamp(about, s, fit, viewport)
        if (s == scale && o == offset) return
        animateTo(s, o, scope, spec)
    }

    fun fling(velocity: Velocity, fit: Rect, viewport: Size, scope: CoroutineScope) {
        stop()
        job = scope.launch {
            launch {
                animateDecay(offset.x, velocity.x, FloatExponentialDecaySpec()) { x, _ ->
                    offset = clamp(Offset(x, offset.y), scale, fit, viewport)
                }
            }
            launch {
                animateDecay(offset.y, velocity.y, FloatExponentialDecaySpec()) { y, _ ->
                    offset = clamp(Offset(offset.x, y), scale, fit, viewport)
                }
            }
        }
    }

    fun animateTo(s: Float, o: Offset, scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) {
        stop()
        val s0 = scale
        val o0 = offset
        job = scope.launch {
            animate(0f, 1f, animationSpec = spec) { t, _ ->
                scale = lerp(s0, s, t)
                offset = lerp(o0, o, t)
            }
        }
    }
}
