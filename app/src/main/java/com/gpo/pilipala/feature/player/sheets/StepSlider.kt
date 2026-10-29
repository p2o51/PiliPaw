package com.gpo.pilipala.feature.player.sheets

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.abs
import kotlin.math.max
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

// The end detents' marks sit this far inside the track's ends.
private val EndInset = 4.dp

/**
 * Where the handle stands on detent [index] of [count], on a track [width] px wide. M3 spreads
 * the detents edge to edge (fractions i / (count - 1)); the marks, the labels and the handle
 * all go by this.
 */
private fun detentX(index: Int, count: Int, width: Float): Float =
    if (count < 2) width / 2f else index * width / (count - 1)

/**
 * A stepped M3 Expressive slider over [count] detents (value 0 until count). M3 handles touch,
 * keys and accessibility on whole detents; the track and handle are drawn here, and the handle
 * glides between detents on a spring instead of hopping. [locked] detents show a lock instead
 * of a dot, and key and accessibility steps pass over them. A drag can still rest on one; the
 * caller keeps it from applying.
 */
@Composable
internal fun StepSlider(
    value: Int,
    count: Int,
    onValueChange: (Int) -> Unit,
    description: String,
    state: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    locked: (Int) -> Boolean = { false },
    handle: Color = MaterialTheme.colorScheme.primary,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val handleColor = animateColorAsState(handle, YoinMotion.defaultEffectsSpec(), label = "stepHandle")
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    // Narrower while held, like M3's own handle.
    val handleWidth = animateDpAsState(
        if (pressed || dragged) HandleWidth / 2 else HandleWidth,
        YoinMotion.fastSpatialSpec(),
        label = "stepHandleWidth",
    )
    val colors = TrackColors(
        active = cs.primary,
        inactive = cs.secondaryContainer,
        activeMark = cs.onPrimary,
        inactiveMark = cs.onSecondaryContainer,
    )
    val lock = rememberVectorPainter(YoinSymbols.Lock)
    val isLocked by rememberUpdatedState(locked)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val lastIndex = (count - 1).coerceAtLeast(0)
    val last = lastIndex.coerceAtLeast(1)
    val at = value.coerceIn(0, lastIndex)
    val current by rememberUpdatedState(at)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // The drawn handle. One detent glides on the fast spring, a longer way (back off a lock)
    // on the default one.
    val glide = remember { Animatable(at.toFloat()) }
    val hop = YoinMotion.fastSpatialSpec<Float>()
    val travel = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(at) {
        glide.animateTo(at.toFloat(), if (abs(at - glide.targetValue) > 1f) travel else hop)
    }

    // The next open detent from [from] in [dir], or null past the end.
    fun stepFrom(from: Int, dir: Int): Int? {
        var i = from + dir
        while (i in 0..lastIndex) {
            if (!isLocked(i)) return i
            i += dir
        }
        return null
    }

    Slider(
        value = at.toFloat(),
        onValueChange = { v -> change(v.roundToInt().coerceIn(0, lastIndex)) },
        modifier = modifier
            .fillMaxWidth()
            // Arrow keys step whole detents and pass locked ones (with two detents M3 would
            // crawl 1% per press, and it stops at every lock).
            .onPreviewKeyEvent { e ->
                val dir = when (e.key) {
                    Key.DirectionRight -> if (rtl) -1 else 1
                    Key.DirectionLeft -> if (rtl) 1 else -1
                    Key.DirectionUp -> 1
                    Key.DirectionDown -> -1
                    else -> return@onPreviewKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyDown -> stepFrom(current, dir)?.let { change(it) }
                    KeyEventType.KeyUp -> finished?.invoke()
                }
                true
            }
            // These outer semantics override the Slider's own: the label as the state, and
            // whole detents for accessibility services (also with two detents, and past locks).
            .semantics {
                contentDescription = description
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(
                    at.toFloat(),
                    0f..last.toFloat(),
                    (count - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    val from = current
                    val t = target.coerceIn(0f, lastIndex.toFloat())
                    val dir = if (t > from) 1 else if (t < from) -1 else 0
                    if (dir == 0) return@setProgress false
                    // A nudge under half a detent still moves one.
                    val wanted = t.roundToInt().let { if (it == from) from + dir else it }
                    val next = if (isLocked(wanted)) stepFrom(wanted, dir) else wanted
                    if (next == null || next == from) return@setProgress false
                    change(next)
                    finished?.invoke()
                    true
                }
            },
        onValueChangeFinished = { finished?.invoke() },
        interactionSource = interaction,
        steps = (count - 2).coerceAtLeast(0),
        // M3 lays out and measures this slot; the handle itself is drawn on the track.
        thumb = { Spacer(Modifier.size(HandleWidth, HandleHeight)) },
        track = {
            DetentTrack(
                fraction = { glide.value / last },
                count = count,
                locked = { isLocked(it) },
                colors = colors,
                handle = { handleColor.value },
                handleWidth = { handleWidth.value },
                lock = lock,
            )
        },
        valueRange = 0f..last.toFloat(),
    )
}

private class TrackColors(val active: Color, val inactive: Color, val activeMark: Color, val inactiveMark: Color)

@Composable
private fun DetentTrack(
    fraction: () -> Float,
    count: Int,
    locked: (Int) -> Boolean,
    colors: TrackColors,
    handle: () -> Color,
    handleWidth: () -> Dp,
    lock: Painter,
) {
    // As tall as the handle, so the handle drawn here stays inside the canvas.
    Canvas(Modifier.fillMaxWidth().height(HandleHeight)) {
        val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
        scale(mirror, 1f) {
            drawDetentTrack(fraction().coerceIn(0f, 1f), count, locked, colors, handle(), handleWidth().toPx(), lock)
        }
    }
}

private fun DrawScope.drawDetentTrack(
    fraction: Float,
    count: Int,
    locked: (Int) -> Boolean,
    colors: TrackColors,
    handle: Color,
    handleWidth: Float,
    lock: Painter,
) {
    val w = size.width
    val top = (size.height - TrackHeight.toPx()) / 2f
    val bottom = top + TrackHeight.toPx()
    val outer = TrackOuterCorner.toPx()
    val inner = TrackInnerCorner.toPx()
    val handleX = fraction * w
    val gap = HandleGap.toPx() + HandleWidth.toPx() / 2f
    val activeEnd = handleX - gap
    if (activeEnd > 0.5f) drawSegment(0f, activeEnd, top, bottom, outer, inner, colors.active)
    val inactiveStart = handleX + gap
    if (w - inactiveStart > 0.5f) drawSegment(inactiveStart, w, top, bottom, inner, outer, colors.inactive)
    if (count >= 2) {
        val dot = DetentDot.toPx()
        val glyph = LockGlyph.toPx()
        val endInset = EndInset.toPx()
        val cy = size.height / 2f
        for (i in 0 until count) {
            val x = detentX(i, count, w)
            val closed = locked(i)
            val half = (if (closed) glyph else dot) / 2f
            // The end detents sit on the track's very ends; their marks step inside it.
            val mark = x.coerceIn(half + endInset, max(half + endInset, w - half - endInset))
            // Skip the detent the handle stands on, and any mark its gap would cut.
            if (abs(x - handleX) < gap + half || abs(mark - handleX) < gap + half) continue
            val color = if (mark < handleX) colors.activeMark else colors.inactiveMark
            if (closed) {
                translate(mark - glyph / 2f, cy - glyph / 2f) {
                    with(lock) { draw(Size(glyph, glyph), colorFilter = ColorFilter.tint(color)) }
                }
            } else {
                drawCircle(color, radius = dot / 2f, center = Offset(mark, cy))
            }
        }
    }
    drawRoundRect(
        color = handle,
        topLeft = Offset(handleX - handleWidth / 2f, 0f),
        size = Size(handleWidth, size.height),
        cornerRadius = CornerRadius(handleWidth / 2f),
    )
}

private fun DrawScope.drawSegment(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    startCorner: Float,
    endCorner: Float,
    color: Color,
) {
    val start = CornerRadius(startCorner)
    val end = CornerRadius(endCorner)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
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
        val minGap = 8.dp.roundToPx()
        val lefts = IntArray(placeables.size) { Int.MIN_VALUE }
        val taken = ArrayList<IntArray>()
        for (i in order) {
            if (i >= placeables.size) continue
            val p = placeables[i]
            val centre = handle / 2f + detentX(i, count, track)
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
