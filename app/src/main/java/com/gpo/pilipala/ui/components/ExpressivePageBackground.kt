package com.gpo.pilipala.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.pilipala.ui.theme.LocalPageBackdrop
import com.gpo.pilipala.ui.theme.LocalPiliDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How much of the accent the top of the wash takes (Yoin: lerp(surfaceContainer, accent, 0.18)). */
private const val WashAmount = 0.18f

/** One full drift cycle — slow enough to feel alive without competing with the content. */
private const val DriftCycleMillis = 75_000f

/** The drift's step: at this pace a bloom moves under a dp per step, so it never reads as stepping. */
private const val DriftTickMillis = 500L

/** Blooms are sized off the width, capped so a 1280dp landscape tablet doesn't get one giant glow. */
private val BloomSpan = 600.dp

/**
 * Yoin's expressive page wash, behind [content]: a vertical wash from
 * `lerp(surfaceContainer, accent, 0.18)` at the top through `surface` to
 * [bottomColor], plus three soft radial blooms of the page's base / accent
 * ([LocalPageBackdrop], from [com.gpo.pilipala.ui.theme.CoverTheme]; the
 * theme's primary / tertiary elsewhere) that drift very slowly.
 *
 * Cost: the wash lives in its own graphics layer and is drawn in the draw
 * phase only — the colour hand-off and the drift never recompose the page.
 * The drift steps every [DriftTickMillis] while the page is resumed and is
 * off when the system's animator scale is 0.
 *
 * [belowPlayer]: the page pins a 16:9 player under the status bar (stacked
 * video / live layouts); the wash then starts under it, so its tinted top is
 * the part you see, not the part the player covers. [animated] false holds
 * the drift (e.g. while a fullscreen player covers the page).
 */
@Composable
fun ExpressivePageBackground(
    modifier: Modifier = Modifier,
    belowPlayer: Boolean = false,
    animated: Boolean = true,
    bottomColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val backdrop = LocalPageBackdrop.current
    val dark = LocalPiliDark.current
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val drift = rememberBackdropDrift(animated)
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
                    val amp = span * 0.06f
                    val wash = Brush.verticalGradient(
                        0f to lerp(washTop, accent, WashAmount),
                        0.5f to surface,
                        1f to bottomColor,
                        startY = top,
                        endY = size.height,
                    )
                    val b1 = Bloom(accent, if (dark) 0.24f else 0.20f, Offset(w * 0.10f, top + h * 0.04f), span * 0.95f)
                    val b2 = Bloom(base, if (dark) 0.20f else 0.14f, Offset(w * 0.92f, top + h * 0.20f), span * 0.80f)
                    val b3 = Bloom(accent, if (dark) 0.10f else 0.07f, Offset(w * 0.40f, top + h * 0.58f), span * 0.65f)
                    onDrawBehind {
                        drawRect(wash)
                        val t = drift.value * 2f * PI.toFloat()
                        b1.draw(this, amp * sin(t), amp * 0.6f * cos(t))
                        b2.draw(this, -amp * sin(t + 2.1f), amp * 0.8f * sin(t + 0.6f))
                        b3.draw(this, amp * 1.2f * cos(t), -amp * 0.5f * sin(2f * t))
                    }
                },
        )
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}

/**
 * A soft radial glow: [alpha] at the centre, gone at [radius]. Fades to the
 * colour itself at zero alpha (not transparent black), so the edge never greys.
 */
private class Bloom(color: Color, alpha: Float, private val center: Offset, private val radius: Float) {
    private val brush = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.45f to color.copy(alpha = alpha * 0.45f),
        1f to color.copy(alpha = 0f),
        center = center,
        radius = radius,
    )

    fun draw(scope: DrawScope, dx: Float, dy: Float) {
        scope.translate(dx, dy) { drawCircle(brush, radius, center) }
    }
}

/** The drift phase (0..1). Steps on the frame clock, so it rests with the window; resumed pages only. */
@Composable
private fun rememberBackdropDrift(enabled: Boolean): State<Float> {
    val drift = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val animate = enabled && remember(context) { animationsEnabled(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate, lifecycle) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameMillis { it }
            while (isActive) {
                delay(DriftTickMillis)
                val now = withFrameMillis { it }
                val elapsed = (now - last).coerceIn(0L, DriftTickMillis * 2)
                last = now
                drift.floatValue = (drift.floatValue + elapsed / DriftCycleMillis) % 1f
            }
        }
    }
    return drift
}

private fun animationsEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)
