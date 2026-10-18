package com.gpo.pilipala.feature.player.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.speedLabel
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The speeds the player offers (the same list 设置 › 默认倍速 picks from). */
internal val PlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

private fun nearestSpeedIndex(speed: Float): Int =
    PlaybackSpeeds.indices.minBy { abs(PlaybackSpeeds[it] - speed) }

/** "1.25x" -> "1.25": the figure without its x. */
private fun speedFigure(speed: Float): String = speedLabel(speed).removeSuffix("x")

/**
 * 播放速度: the speed as a bold figure, a legend of shapes travelling at that speed, and a
 * stepped slider over [PlaybackSpeeds]. Each detent applies at once, with a tick.
 */
@Composable
internal fun SpeedSliderPage(ctrl: PiliPlayerController) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val default by AppSettings.defaultSpeed.state.collectAsState()
    val still = rememberAnimationsRemoved()
    val speed = ctrl.speed
    val index = nearestSpeedIndex(speed)

    Surface(
        shape = YoinContainerShapes.Panel,
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
            ValueHeadline(Headline(speed, speedFigure(speed), "x"), gap = 2.dp)
            SpeedLegend(
                speed = speed,
                still = still,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp)
                    .height(64.dp),
            )
            StepSlider(
                value = index.toFloat(),
                count = PlaybackSpeeds.size,
                onValueChange = { v ->
                    val next = PlaybackSpeeds[v.roundToInt().coerceIn(0, PlaybackSpeeds.lastIndex)]
                    if (next != ctrl.speed) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        ctrl.changeSpeed(next)
                    }
                },
                description = "播放速度",
                state = speedLabel(speed),
            )
            StepLabels(
                count = PlaybackSpeeds.size,
                current = index,
                text = { speedFigure(PlaybackSpeeds[it]) },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    AnimatedVisibility(
        visible = speed != default,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
    ) {
        Box(Modifier.padding(top = 16.dp)) {
            FilledTonalButton(onClick = { AppSettings.set(AppSettings.defaultSpeed, ctrl.speed) }) {
                Text("设为默认速度")
            }
        }
    }
}

private val LegendShapes: List<RoundedPolygon> by lazy {
    listOf(
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Sunny,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Gem,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Puffy,
    )
}

// Mask that lets the conveyor enter and leave softly (alpha only, never painted).
private val EdgeFade = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.12f to Color.Black,
    0.88f to Color.Black,
    1f to Color.Transparent,
)

/**
 * A conveyor of Material shapes that travels, turns and bobs at [speed], each shape trailing
 * the places it just was (a longer trail the faster it goes). With animations removed it
 * stands still, and the trails alone show the speed.
 */
@Composable
private fun SpeedLegend(speed: Float, still: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val rate = animateFloatAsState(speed, YoinMotion.defaultSpatialSpec(), label = "legendRate")
    // Legend time: seconds of travel at 1x, advanced by the (sprung) rate every frame.
    val clock = remember { mutableFloatStateOf(0f) }
    if (!still) {
        LaunchedEffect(Unit) {
            var last = -1L
            while (true) {
                withFrameNanos { now ->
                    if (last >= 0L) clock.floatValue += (now - last) / 1_000_000_000f * rate.value.coerceAtLeast(0f)
                    last = now
                }
            }
        }
    }
    val paths = remember { LegendShapes.map { it.toPath().asComposePath() } }
    val tints = listOf(cs.primary, cs.tertiary, cs.secondary)
    Canvas(
        modifier
            .clearAndSetSemantics { }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
        scale(mirror, 1f) {
            drawConveyor(clock.floatValue, rate.value.coerceAtLeast(0.1f), paths, tints)
        }
        drawRect(EdgeFade, blendMode = BlendMode.DstIn)
    }
}

private const val Ghosts = 4
private const val GhostLagSeconds = 0.075f

private fun DrawScope.drawConveyor(clock: Float, rate: Float, paths: List<Path>, tints: List<Color>) {
    val spacing = 56.dp.toPx()
    val shape = 26.dp.toPx()
    val travel = 36.dp.toPx() // px per legend second
    val bob = 5.dp.toPx()
    val dist = clock * travel
    val shift = dist.mod(spacing)
    val base = floor(dist / spacing).toInt()
    val slots = ceil(size.width / spacing).toInt() + 2
    val cy = size.height / 2f
    for (k in -1 until slots) {
        // Stable identity as the shape travels from slot to slot.
        val id = k - base
        val path = paths[id.mod(paths.size)]
        val tint = tints[id.mod(tints.size)]
        for (g in Ghosts downTo 0) {
            // Where this shape was g lags ago (in real time, so the trail grows with speed).
            val back = g * GhostLagSeconds * rate
            val t = clock - back
            val x = k * spacing + shift - back * travel
            val y = cy + sin(t * 2.4f + id * 1.3f) * bob
            val s = shape * (1f - 0.08f * g)
            val alpha = if (g == 0) 1f else 0.38f * (1f - g / (Ghosts + 1f))
            translate(x - s / 2f, y - s / 2f) {
                rotate(t * 48f + id * 37f, pivot = Offset(s / 2f, s / 2f)) {
                    scale(s, s, pivot = Offset.Zero) {
                        drawPath(path, tint, alpha = alpha)
                    }
                }
            }
        }
    }
}
