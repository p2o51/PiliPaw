package com.gpo.pilipala.feature.player.sheets

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// M3 Expressive "medium" slider: a 40dp track with 12dp outer corners, a 4 x 52dp handle
// standing in a 6dp gap, 2dp corners where the track meets the gap.
private val TrackHeight = 40.dp
private val TrackOuterCorner = 12.dp
private val TrackInnerCorner = 2.dp
private val HandleWidth = 4.dp
private val HandleHeight = 52.dp
private val HandleGap = 6.dp
private val DetentDot = 4.dp
private val LockGlyph = 14.dp

/**
 * Where detent [index] of [count] sits on a track [width] px wide. The handle travels the
 * full width; the end detents tuck into the rounded end caps so they stay inside the track.
 */
private fun detentX(index: Int, count: Int, width: Float, cap: Float): Float {
    if (count < 2) return width / 2f
    val spacing = width / (count - 1)
    val inset = min(cap, spacing / 2f)
    return (index * spacing).coerceIn(inset, width - inset)
}

/**
 * A stepped M3 Expressive slider over [count] detents (value 0 until count). [locked]
 * detents are drawn as a lock glyph instead of a dot: the caller keeps them unreachable.
 */
@Composable
internal fun StepSlider(
    value: Float,
    count: Int,
    onValueChange: (Float) -> Unit,
    description: String,
    state: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    locked: (Int) -> Boolean = { false },
    handle: Color = MaterialTheme.colorScheme.primary,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val handleColor by animateColorAsState(handle, YoinMotion.defaultEffectsSpec(), label = "stepHandle")
    val colors = SliderDefaults.colors(
        thumbColor = handleColor,
        activeTrackColor = cs.primary,
        activeTickColor = cs.onPrimary,
        inactiveTrackColor = cs.secondaryContainer,
        inactiveTickColor = cs.onSecondaryContainer,
    )
    val lock = rememberVectorPainter(PiliSymbols.Lock)
    val isLocked by rememberUpdatedState(locked)
    val last = (count - 1).coerceAtLeast(1).toFloat()
    Slider(
        value = value.coerceIn(0f, last),
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        steps = (count - 2).coerceAtLeast(0),
        thumb = { s ->
            SliderDefaults.Thumb(
                interactionSource = interaction,
                sliderState = s,
                colors = colors,
                thumbSize = DpSize(HandleWidth, HandleHeight),
            )
        },
        track = { s -> DetentTrack(s, count, { isLocked(it) }, colors, lock) },
        valueRange = 0f..last,
    )
}

@Composable
private fun DetentTrack(state: SliderState, count: Int, locked: (Int) -> Boolean, colors: SliderColors, lock: Painter) {
    Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
        val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
        scale(mirror, 1f) {
            drawDetentTrack(state.coercedValueAsFraction, count, locked, colors, lock)
        }
    }
}

private fun DrawScope.drawDetentTrack(
    fraction: Float,
    count: Int,
    locked: (Int) -> Boolean,
    colors: SliderColors,
    lock: Painter,
) {
    val w = size.width
    val outer = TrackOuterCorner.toPx()
    val inner = TrackInnerCorner.toPx()
    val handleX = fraction * w
    val gap = HandleGap.toPx() + HandleWidth.toPx() / 2f
    val activeEnd = handleX - gap
    if (activeEnd > 0.5f) drawSegment(0f, activeEnd, outer, inner, colors.activeTrackColor)
    val inactiveStart = handleX + gap
    if (w - inactiveStart > 0.5f) drawSegment(inactiveStart, w, inner, outer, colors.inactiveTrackColor)
    if (count < 2) return
    val dot = DetentDot.toPx()
    val glyph = LockGlyph.toPx()
    val cy = size.height / 2f
    for (i in 0 until count) {
        val x = detentX(i, count, w, outer)
        val closed = locked(i)
        val half = (if (closed) glyph else dot) / 2f
        // The handle stands on (or right next to) this detent.
        if (abs(x - handleX) < gap + half) continue
        val color = if (x < handleX) colors.activeTickColor else colors.inactiveTickColor
        if (closed) {
            translate(x - glyph / 2f, cy - glyph / 2f) {
                with(lock) { draw(Size(glyph, glyph), colorFilter = ColorFilter.tint(color)) }
            }
        } else {
            drawCircle(color, radius = dot / 2f, center = Offset(x, cy))
        }
    }
}

private fun DrawScope.drawSegment(left: Float, right: Float, startCorner: Float, endCorner: Float, color: Color) {
    val start = CornerRadius(startCorner)
    val end = CornerRadius(endCorner)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = 0f,
                right = right,
                bottom = size.height,
                topLeftCornerRadius = start,
                topRightCornerRadius = end,
                bottomRightCornerRadius = end,
                bottomLeftCornerRadius = start,
            ),
        )
    }
    drawPath(path, color)
}

/**
 * Labels under a [StepSlider]'s detents, each centred on its detent. When they would
 * collide, the ends stay and the rest thin out evenly. [current] is in the accent,
 * [dim] ones are quiet.
 */
@Composable
internal fun StepLabels(
    count: Int,
    current: Int,
    text: (Int) -> String,
    modifier: Modifier = Modifier,
    dim: (Int) -> Boolean = { false },
) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium.withTabularFigures()
    val order = remember(count) { labelPriority(count) }
    Layout(
        content = {
            for (i in 0 until count) {
                val target = when {
                    i == current -> cs.primary
                    dim(i) -> cs.onSurfaceVariant.copy(alpha = 0.38f)
                    else -> cs.onSurfaceVariant
                }
                val color by animateColorAsState(target, YoinMotion.defaultEffectsSpec(), label = "stepLabel")
                Text(text(i), style = style, color = color, maxLines = 1)
            }
        },
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val handle = HandleWidth.toPx()
        val track = (width - handle).coerceAtLeast(1f)
        val cap = TrackOuterCorner.toPx()
        val minGap = 8.dp.roundToPx()
        val lefts = IntArray(placeables.size) { Int.MIN_VALUE }
        val taken = ArrayList<IntArray>()
        for (i in order) {
            if (i >= placeables.size) continue
            val p = placeables[i]
            val centre = handle / 2f + detentX(i, count, track, cap)
            val left = (centre - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
            val right = left + p.width
            if (taken.none { left < it[1] + minGap && right + minGap > it[0] }) {
                taken += intArrayOf(left, right)
                lefts[i] = left
            }
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, p -> if (lefts[i] != Int.MIN_VALUE) p.placeRelative(lefts[i], 0) }
        }
    }
}

/** First, last, then midpoints by halving: crowded rows thin out evenly. */
private fun labelPriority(count: Int): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val out = mutableListOf(0, count - 1)
    var spans = listOf(0 to count - 1)
    while (spans.isNotEmpty()) {
        val next = mutableListOf<Pair<Int, Int>>()
        for ((a, b) in spans) {
            if (b - a < 2) continue
            val m = (a + b) / 2
            out += m
            next += a to m
            next += m to b
        }
        spans = next
    }
    return out
}

internal data class Headline(val order: Float, val primary: String, val secondary: String?)

/** The value as a bold figure with a quiet unit or suffix; rolls up or down as it changes. */
@Composable
internal fun ValueHeadline(
    value: Headline,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    style: TextStyle = MaterialTheme.typography.displaySmall,
) {
    val motion = MaterialTheme.motionScheme
    val cs = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = targetState.order >= initialState.order
            (
                slideInVertically(motion.defaultSpatialSpec()) { h -> if (up) h / 2 else -h / 2 } +
                    fadeIn(motion.defaultEffectsSpec())
                ) togetherWith (
                slideOutVertically(motion.fastSpatialSpec()) { h -> if (up) -h / 2 else h / 2 } +
                    fadeOut(motion.fastEffectsSpec())
                ) using SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() }
        },
        contentKey = { it.primary + "\u0000" + it.secondary },
        contentAlignment = Alignment.BottomStart,
        label = "stepHeadline",
        modifier = modifier,
    ) { h ->
        Row {
            Text(
                h.primary,
                style = style.withTabularFigures(),
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (!h.secondary.isNullOrEmpty()) {
                Spacer(Modifier.width(gap))
                Text(
                    h.secondary,
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** 开发者选项 › 动画时长缩放 set to off (or 移除动画 in accessibility). */
@Composable
internal fun rememberAnimationsRemoved(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
