package com.gpo.pilipala.feature.player.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayerPhase
import com.gpo.pilipala.feature.player.Qualities
import com.gpo.pilipala.feature.player.QualityOption
import com.gpo.pilipala.feature.player.StreamPicker
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How finely a quality draws the legend: [lines] sets the block size, [tones] how many
 * colour steps there are, [vivid] widens the range (HDR / 杜比视界).
 */
private data class Fidelity(val lines: Int, val tones: Int, val vivid: Boolean)

private fun fidelity(qn: Int, index: Int, count: Int): Fidelity = when (qn) {
    6 -> Fidelity(240, 3, false)
    16 -> Fidelity(360, 3, false)
    32 -> Fidelity(480, 4, false)
    64 -> Fidelity(720, 5, false)
    74 -> Fidelity(720, 6, false)
    80 -> Fidelity(1080, 7, false)
    100 -> Fidelity(1080, 8, false)
    112 -> Fidelity(1080, 10, false)
    116 -> Fidelity(1080, 9, false)
    120 -> Fidelity(2160, 14, false)
    125 -> Fidelity(2160, 18, true)
    126 -> Fidelity(2160, 20, true)
    127 -> Fidelity(4320, 24, false)
    else -> {
        // An unknown code: place it by its rank among this video's qualities.
        val t = if (count > 1) index / (count - 1f) else 1f
        Fidelity((360 * 6f.pow(t)).roundToInt(), (3 + 11 * t).roundToInt(), false)
    }
}

/** Block edge for a picture [lines] tall: coarse at 360P, fine at 4K. */
private fun blockDp(lines: Int): Float = 22f * (360f / lines.coerceAtLeast(120)).pow(0.75f)

/** "1080P 高码率" -> ("1080P", "高码率"); "杜比视界" stays whole. */
private fun splitLabel(label: String): Pair<String, String?> {
    val cut = label.indexOf(' ')
    return if (cut <= 0) label to null else label.substring(0, cut) to label.substring(cut + 1).trim().ifEmpty { null }
}

/** Nearest reachable step to [from]: below first (the best one can have), then above. */
private fun nearestOpen(steps: List<QualityOption>, from: Int): Int? {
    if (steps.getOrNull(from)?.available == true) return from
    for (d in 1..steps.size) {
        if (steps.getOrNull(from - d)?.available == true) return from - d
        if (steps.getOrNull(from + d)?.available == true) return from + d
    }
    return null
}

/**
 * 画质: the quality as a bold figure, a mosaic that grows finer as the quality rises, and a
 * stepped slider over this video's qualities, low to high. Locked qualities (登录 / 大会员)
 * stay on the track behind a lock and spring back to the nearest open one when let go.
 * A quality applies when the finger lifts, not per detent (each switch reloads the stream).
 */
@Composable
internal fun QualitySliderPage(ctrl: PiliPlayerController) {
    val pi = ctrl.info ?: return
    val steps = remember(pi) { pi.qualities.sortedBy { it.qn } }
    if (steps.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val loggedIn = BiliClient.isLoggedIn

    val currentQn = ctrl.currentQn
    val current = steps.indexOfFirst { it.qn == currentQn }.takeIf { it >= 0 }
        ?: steps.indices.minBy { abs(steps[it].qn - currentQn) }
    // The detent under the finger, while it is down.
    var drag by remember { mutableStateOf<Int?>(null) }
    // A non-DASH switch refetches before currentQn moves: the handle waits on the request
    // until the new stream arrives (a new info resets this) or the load fails.
    var asked by remember(pi) { mutableStateOf<Int?>(null) }
    val failed = ctrl.phase is PlayerPhase.Error
    LaunchedEffect(currentQn, failed) { asked = null }
    val resting = asked ?: current
    // The headline, tag, legend and labels show where the handle is going, never the
    // detents it glides past on the way back.
    val hover = (drag ?: resting).coerceIn(0, steps.lastIndex)
    val hovered = steps[hover]
    val hoverLocked = !hovered.available
    val (primary, secondary) = splitLabel(hovered.label)
    val lockTag = if (loggedIn) "大会员" else "登录"

    Surface(
        shape = YoinContainerShapes.Panel,
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ValueHeadline(
                    Headline(hover.toFloat(), primary, secondary),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.size(10.dp))
                AnimatedVisibility(
                    visible = hoverLocked,
                    enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.6f),
                    exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.6f),
                ) {
                    Tag(lockTag)
                }
            }
            MosaicLegend(
                fidelity = fidelity(hovered.qn, hover, steps.size),
                locked = hoverLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 10.dp)
                    .height(72.dp),
            )
            if (steps.size > 1) {
                StepSlider(
                    value = hover,
                    count = steps.size,
                    onValueChange = { i ->
                        val now = i.coerceIn(0, steps.lastIndex)
                        if (now != (drag ?: resting)) {
                            haptics.performHapticFeedback(
                                if (steps[now].available) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
                            )
                        }
                        drag = now
                    },
                    onValueChangeFinished = {
                        val landed = drag ?: resting
                        drag = null
                        // Off a lock, the handle glides back to the nearest open quality.
                        val target = nearestOpen(steps, landed) ?: current
                        if (target != landed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        val qn = steps[target].qn
                        if (qn != ctrl.currentQn && target != asked) {
                            // DASH switches at once; a refetch keeps the handle on the request.
                            // (Already in error, a further failure would go unseen: no hold.)
                            if (!pi.isDash && ctrl.phase !is PlayerPhase.Error) asked = target
                            ctrl.selectQuality(qn)
                        }
                    },
                    description = "画质",
                    state = hovered.label,
                    locked = { !steps[it].available },
                    handle = if (hoverLocked) cs.onSurfaceVariant else cs.primary,
                )
                StepLabels(
                    count = steps.size,
                    current = hover,
                    text = { Qualities.videoShort(steps[it].qn) },
                    dim = { !steps[it].available },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    // Codecs this quality comes in (the same choice as 播放设置 › 解码格式).
    val offered = if (pi.isDash) StreamPicker.codecsFor(pi, currentQn) else emptyList()
    // The last real choice stays drawn while the row folds away.
    val held = remember { arrayOf(offered) }
    if (offered.size > 1) held[0] = offered
    val codecs = held[0]
    AnimatedVisibility(
        visible = offered.size > 1,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
    ) {
        Column {
            Text(
                "解码格式",
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                codecs.forEachIndexed { i, c ->
                    ToggleButton(
                        checked = c == ctrl.currentCodec,
                        onCheckedChange = {
                            if (c != ctrl.currentCodec) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                ctrl.selectCodec(c)
                            }
                        },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            codecs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(Qualities.codecName(c))
                    }
                }
            }
        }
    }

    val default by AppSettings.defaultVideoQuality.state.collectAsState()
    AnimatedVisibility(
        visible = currentQn != default && steps.any { it.qn == currentQn },
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
    ) {
        Box(Modifier.padding(top = 16.dp)) {
            FilledTonalButton(onClick = { AppSettings.set(AppSettings.defaultVideoQuality, ctrl.currentQn) }) {
                Text("设为默认画质")
            }
        }
    }
}

/**
 * An abstract picture drawn as a grid of blocks in the scheme's colours: coarse blocks in a
 * few tones at 360P, fine blocks in many tones at 4K, a wider range for HDR. Moving between
 * qualities springs the block size, so the picture resolves or breaks up. A locked quality
 * shows faded, under a lock.
 */
@Composable
private fun MosaicLegend(fidelity: Fidelity, locked: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val block by animateFloatAsState(blockDp(fidelity.lines), YoinMotion.defaultSpatialSpec(), label = "mosaicBlock")
    val tones by animateFloatAsState(fidelity.tones.toFloat(), YoinMotion.defaultEffectsSpec(), label = "mosaicTones")
    val vivid by animateFloatAsState(if (fidelity.vivid) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "mosaicVivid")
    val fade by animateFloatAsState(if (locked) 0.32f else 1f, YoinMotion.defaultEffectsSpec(), label = "mosaicFade")
    // Container tones up to the accents; HDR reaches further at both ends.
    val plain = listOf(cs.surfaceContainerHighest, cs.secondaryContainer, cs.primaryContainer, cs.secondary, cs.tertiary, cs.primary)
    val wide = listOf(
        cs.surfaceContainerLowest, cs.tertiaryContainer, cs.primaryContainer, cs.secondary, cs.tertiary, cs.primary, cs.onPrimaryContainer,
    )
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .fillMaxSize()
                .clip(ContinuousRoundedCornerShape(14.dp))
                .drawWithCache {
                    // The picture is sampled again only when the block size moves; tone and
                    // fade changes just repaint.
                    val grid = sampleMosaic(size, block.dp.toPx())
                    onDrawBehind { if (grid != null) drawMosaic(grid, tones, vivid, fade, plain, wide) }
                },
        )
        AnimatedVisibility(
            visible = locked,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.defaultSpatialSpec(), initialScale = 0.5f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.5f),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(cs.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PiliSymbols.Lock, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun ramp(stops: List<Color>, t: Float): Color {
    val p = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = floor(p).toInt().coerceIn(0, stops.size - 2)
    return lerp(stops[i], stops[i + 1], p - i)
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The picture, in 0..1 (x and y in units of the height, [aspect] wide): a sky that lightens
 * toward the horizon, a sun, a far ridge and near hills. Its edges are what the blocks resolve.
 */
private fun picture(x: Float, y: Float, aspect: Float): Float {
    var v = 0.06f + 0.30f * y
    val sun = hypot(x - 0.70f * aspect, y - 0.36f)
    v += (1f - v) * smoothstep(0.30f, 0.20f, sun)
    val far = 0.60f + 0.10f * sin(1.25f * x + 0.5f) + 0.04f * sin(4.1f * x)
    v += (0.56f + 0.06f * sin(3.3f * x + 1f) - v) * smoothstep(far - 0.02f, far + 0.02f, y)
    val near = 0.80f + 0.08f * sin(1.9f * x + 2.2f)
    v += (0.76f + 0.12f * (y - near) - v) * smoothstep(near - 0.02f, near + 0.02f, y)
    return v.coerceIn(0f, 1f)
}

/** The picture sampled at the centre of each block, row by row. */
private class MosaicGrid(
    val cell: Float,
    val cols: Int,
    val rows: Int,
    val ox: Float,
    val oy: Float,
    val samples: FloatArray,
)

private fun sampleMosaic(size: Size, blockPx: Float): MosaicGrid? {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return null
    val cell = blockPx.coerceAtLeast(3f)
    val cols = ceil(w / cell).toInt()
    val rows = ceil(h / cell).toInt()
    val ox = (w - cols * cell) / 2f
    val oy = (h - rows * cell) / 2f
    val aspect = w / h
    val samples = FloatArray(cols * rows)
    for (r in 0 until rows) {
        val cy = oy + (r + 0.5f) * cell
        for (c in 0 until cols) {
            samples[r * cols + c] = picture((ox + (c + 0.5f) * cell) / h, cy / h, aspect)
        }
    }
    return MosaicGrid(cell, cols, rows, ox, oy, samples)
}

private fun DrawScope.drawMosaic(
    grid: MosaicGrid,
    tones: Float,
    vivid: Float,
    alpha: Float,
    plain: List<Color>,
    wide: List<Color>,
) {
    val cell = grid.cell
    val inset = max(0.75f, cell * 0.09f)
    val side = Size(cell - inset * 2f, cell - inset * 2f)
    val corner = CornerRadius(cell * 0.22f)
    val levels = tones.coerceAtLeast(2f)
    // HDR stretches the contrast before the tones are cut.
    val stretch = 1f + 0.3f * vivid
    // The tones are cut into whole levels: mix each level's colour once, not once per block.
    val palette = Array(floor(levels).toInt() + 1) { k ->
        val tone = (k / (levels - 1f)).coerceIn(0f, 1f)
        if (vivid <= 0.001f) ramp(plain, tone) else lerp(ramp(plain, tone), ramp(wide, tone), vivid)
    }
    for (r in 0 until grid.rows) {
        val top = grid.oy + r * cell + inset
        for (c in 0 until grid.cols) {
            val v = grid.samples[r * grid.cols + c]
            val stretched = (0.5f + (v - 0.5f) * stretch).coerceIn(0f, 1f)
            val level = floor(stretched * levels).toInt().coerceIn(0, palette.lastIndex)
            drawRoundRect(
                color = palette[level],
                topLeft = Offset(grid.ox + c * cell + inset, top),
                size = side,
                cornerRadius = corner,
                alpha = alpha,
            )
        }
    }
}
