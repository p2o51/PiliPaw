package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.ui.theme.LocalPageBackdrop
import com.gpo.pilipala.yt.ui.theme.LocalPawDark
import kotlin.math.min

/** How much of the accent the top of the wash takes (Yoin: lerp(surfaceContainer, accent, 0.18)). */
private const val WashAmount = 0.18f

/** Blooms are sized off the width, capped so a 1280dp landscape tablet doesn't get one giant glow. */
private val BloomSpan = 600.dp

/**
 * Yoin's expressive page wash, behind [content]: a vertical wash from
 * `lerp(surfaceContainer, accent, 0.18)` at the top through `surface` to
 * [bottomColor], plus three soft radial blooms of the page's base / accent
 * ([LocalPageBackdrop], from [com.gpo.pilipala.yt.ui.theme.CoverTheme]; the
 * theme's primary / tertiary elsewhere). It is static: a drifting glow
 * read as a moving edge behind text (owner, 2026-10-18).
 *
 * Cost: the wash lives in its own graphics layer and is drawn in the draw
 * phase only — the colour hand-off never recomposes the page.
 *
 * [belowPlayer]: the page pins a 16:9 player under the status bar (stacked
 * video / live layouts); the wash then starts under it, so its tinted top is
 * the part you see, not the part the player covers. [animated] is kept for
 * callers and has no effect now that the wash is static.
 */
@Composable
fun ExpressivePageBackground(
    modifier: Modifier = Modifier,
    belowPlayer: Boolean = false,
    @Suppress("UNUSED_PARAMETER") animated: Boolean = true,
    bottomColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val backdrop = LocalPageBackdrop.current
    val dark = LocalPawDark.current
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val fallbackBase = scheme.primary
    val fallbackAccent = scheme.tertiary
    val washTop = scheme.surfaceContainer
    val surface = scheme.surface
    Box(modifier) {
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer { clip = true }
                .drawWithCache {
                    val base = backdrop?.base ?: fallbackBase
                    val accent = backdrop?.accent ?: fallbackAccent
                    val top = if (belowPlayer) statusTop + size.width * 9f / 16f else 0f
                    val h = (size.height - top).coerceAtLeast(1f)
                    val w = size.width
                    val span = min(w, BloomSpan.toPx())
                    val washFrom = lerp(washTop, accent, WashAmount)
                    val wash = Brush.verticalGradient(
                        colorStops = Array(WashStops + 1) { i ->
                            val t = i / WashStops.toFloat()
                            // Smoothstep down to the surface by the middle, then to bottomColor: no kink.
                            val c = if (t <= 0.5f) lerp(washFrom, surface, smooth(t / 0.5f)) else lerp(surface, bottomColor, smooth((t - 0.5f) / 0.5f))
                            t to c
                        },
                        startY = top,
                        endY = size.height,
                    )
                    val b1 = Bloom(accent, if (dark) 0.24f else 0.20f, Offset(w * 0.10f, top + h * 0.04f), span * 0.95f)
                    val b2 = Bloom(base, if (dark) 0.20f else 0.14f, Offset(w * 0.92f, top + h * 0.20f), span * 0.80f)
                    val b3 = Bloom(accent, if (dark) 0.10f else 0.07f, Offset(w * 0.40f, top + h * 0.58f), span * 0.65f)
                    onDrawBehind {
                        drawRect(wash)
                        b1.draw(this)
                        b2.draw(this)
                        b3.draw(this)
                    }
                },
        )
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}

/**
 * A soft radial glow: [alpha] at the centre, gone at [radius]. The falloff is a
 * smoothstep (flat at the centre and at the rim), so there is no ring where the
 * glow ends, and it is drawn with a dithered paint so the long, faint ramp does
 * not band on 8-bit panels. Fades to the colour itself at zero alpha, never grey.
 */
private class Bloom(color: Color, alpha: Float, private val center: Offset, private val radius: Float) {
    private val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        isDither = true
        val argb = IntArray(BloomStops + 1) { i ->
            val t = i / BloomStops.toFloat()
            color.copy(alpha = alpha * (1f - smooth(t))).toArgb()
        }
        val positions = FloatArray(BloomStops + 1) { i -> i / BloomStops.toFloat() }
        shader = android.graphics.RadialGradient(
            center.x, center.y, radius, argb, positions, android.graphics.Shader.TileMode.CLAMP,
        )
    }

    fun draw(scope: DrawScope) {
        scope.drawIntoCanvas { it.nativeCanvas.drawCircle(center.x, center.y, radius, paint) }
    }
}

private const val BloomStops = 16
private const val WashStops = 12

/** Smoothstep: zero slope at both ends. */
private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

