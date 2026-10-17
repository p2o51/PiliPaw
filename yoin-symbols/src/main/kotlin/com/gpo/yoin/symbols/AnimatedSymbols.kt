package com.gpo.yoin.symbols

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.Group
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorComposable
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Animated symbols return a Painter, so they drop into any Icon and inherit its tint:
 *
 *     Icon(rememberTranslateSymbolPainter(translating = busy), contentDescription = "Translate")
 *
 * Every loop is a spring beat followed by a short hold, and only checks whether it
 * should stop while resting, so it always lands on the symbol's rest pose.
 *
 * When the system animator scale is 0 ("Remove animations"), every animation would
 * finish on the next frame, so the loops stop instead of flickering and the symbols
 * stay still.
 */

private val WenNodes: List<List<PathNode>> by lazy { SymbolGeometry.WenPaths.map(::addPathNodes) }
private val LatinNodes: List<List<PathNode>> by lazy { SymbolGeometry.LatinPaths.map(::addPathNodes) }
private val HeartNodes: List<PathNode> by lazy { addPathNodes(SymbolGeometry.Heart) }
private val SparkleMainNodes: List<PathNode> by lazy { addPathNodes(SymbolGeometry.SparkleMain) }
private val SparkleMiniNodes: List<PathNode> by lazy { addPathNodes(SymbolGeometry.SparkleMini) }
private val Sqrt2 = sqrt(2f)
private val EqualizerStagger = longArrayOf(0L, 80L, 160L, 80L, 0L)

/** True when the system has turned animations off (animator duration scale 0). */
private suspend fun animationsOff(): Boolean =
    (currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) == 0f

/** Suspends until [condition] (a snapshot-state read) becomes true. */
private suspend fun awaitUntil(condition: () -> Boolean) {
    snapshotFlow(condition).first { it }
}

@Composable
private fun symbolPainter(
    name: String,
    content: @Composable @VectorComposable () -> Unit,
): Painter = rememberVectorPainter(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    name = name,
    autoMirror = false,
) { _, _ -> content() }

// ---------------------------------------------------------------- translate

/**
 * The translate symbol. While [translating] is true, 文 and A travel half a turn around
 * the same circle and swap places, rest for [SymbolMotion.holdMillis], then swap back.
 * When translation finishes the current half turn completes, and if the glyphs are left
 * swapped they take one more half turn, so the symbol always comes to rest as 文A.
 * With [SymbolMotion.reduced] the glyphs stay put and breathe instead.
 */
@Composable
public fun rememberTranslateSymbolPainter(
    translating: Boolean,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val theta = remember { Animatable(0f) }
    val breath = remember { Animatable(1f) }
    val translatingNow by rememberUpdatedState(translating)

    LaunchedEffect(motion) {
        while (isActive) {
            val off = animationsOff()
            val swapped = theta.targetValue % 360f != 0f
            when {
                !translatingNow -> {
                    // Come to rest as 文A, then wait for the next translation.
                    when {
                        off -> {
                            theta.snapTo(0f)
                            breath.snapTo(1f)
                        }
                        motion.reduced -> {
                            if (swapped) theta.snapTo(0f)
                            breath.animateTo(1f, motion.slowEffects)
                        }
                        else -> {
                            if (breath.value != 1f) breath.snapTo(1f)
                            if (swapped) theta.animateTo(theta.targetValue + 180f, motion.spatial)
                        }
                    }
                    awaitUntil { translatingNow }
                }
                off -> {
                    theta.snapTo(0f)
                    breath.snapTo(1f)
                    awaitUntil { !translatingNow }
                }
                motion.reduced -> {
                    if (swapped) theta.snapTo(0f)
                    breath.animateTo(0.42f, motion.slowEffects)
                    breath.animateTo(1f, motion.slowEffects)
                }
                else -> {
                    if (breath.value != 1f) breath.snapTo(1f)
                    theta.animateTo(theta.targetValue + 180f, motion.spatial)
                    if (translatingNow) delay(motion.holdMillis)
                }
            }
        }
    }

    return symbolPainter("TranslateSymbol") {
        OrbitGlyph(theta.value, SymbolGeometry.WenX, SymbolGeometry.WenY, WenNodes, breath.value)
        OrbitGlyph(theta.value, SymbolGeometry.LatinX, SymbolGeometry.LatinY, LatinNodes, breath.value)
    }
}

@Composable
@VectorComposable
private fun OrbitGlyph(
    thetaDegrees: Float,
    homeX: Float,
    homeY: Float,
    paths: List<List<PathNode>>,
    alpha: Float,
) {
    val vx = homeX - 12f
    val vy = homeY - 12f
    val a = thetaDegrees / 180f * PI.toFloat()
    val px = vx * cos(a) - vy * sin(a)
    val py = vx * sin(a) + vy * cos(a)
    val radius = hypot(vx, vy)
    // +1 while passing the upper right (in front), -1 while passing the lower left (behind)
    val depth = if (radius == 0f) 0f else (px - py) / (radius * Sqrt2)
    val scale = 1f + (if (depth > 0f) 0.08f else 0.14f) * depth
    val glyphAlpha = (alpha * (1f - 0.42f * max(0f, -depth))).coerceIn(0f, 1f)
    Group(
        name = "orbit",
        pivotX = homeX,
        pivotY = homeY,
        scaleX = scale,
        scaleY = scale,
        translationX = px - vx,
        translationY = py - vy,
    ) {
        paths.forEach { nodes ->
            Path(
                pathData = nodes,
                stroke = Ink,
                strokeAlpha = glyphAlpha,
                strokeLineWidth = YoinSymbolStrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
}

// ---------------------------------------------------------------- play / pause

/**
 * Play while [playing] is false, pause while it is true (the symbol shows the action).
 * The triangle is cut into two halves that each become one pause bar; no rotation, no fade.
 */
@Composable
public fun rememberPlayPauseSymbolPainter(
    playing: Boolean,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = motion.control,
        label = "playPause",
    )
    return symbolPainter("PlayPauseSymbol") {
        MorphPolygon(SymbolGeometry.PlayLeft, SymbolGeometry.PauseLeft, progress)
        MorphPolygon(SymbolGeometry.PlayRight, SymbolGeometry.PauseRight, progress)
    }
}

@Composable
@VectorComposable
private fun MorphPolygon(from: FloatArray, to: FloatArray, t: Float) {
    val data = PathData {
        val count = from.size / 2
        for (i in 0 until count) {
            val x = lerp(from[2 * i], to[2 * i], t)
            val y = lerp(from[2 * i + 1], to[2 * i + 1], t)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    Path(
        pathData = data,
        fill = Ink,
        stroke = Ink,
        strokeLineWidth = YoinSymbolStrokeWidth,
        strokeLineJoin = StrokeJoin.Round,
    )
}

// ---------------------------------------------------------------- play mode

/**
 * The play modes a play-mode button shows: shuffle (off / on) × repeat (all / one), in the order
 * a tap steps them — each step changes one of the two:
 * repeat all → shuffle → shuffle + repeat one → repeat one → repeat all.
 *
 * With Media3 they map to `REPEAT_MODE_ALL` / `REPEAT_MODE_ONE` and `shuffleModeEnabled`.
 */
public enum class SymbolPlayMode {
    RepeatAll,
    Shuffle,
    ShuffleRepeatOne,
    RepeatOne,
    ;

    /** Whether the strands cross (shuffle on). */
    public val shuffle: Boolean get() = this == Shuffle || this == ShuffleRepeatOne

    /** Whether the 1 shows (repeat one). */
    public val repeatOne: Boolean get() = this == RepeatOne || this == ShuffleRepeatOne

    /** The mode the next tap moves to. */
    public fun next(): SymbolPlayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * One symbol for a play-mode button that cycles [SymbolPlayMode]s.
 *
 * Repeat all → shuffle: the loop is cut open, its upper half swings into the rising strand and
 * its lower half turns over like a card and passes underneath. Shuffle → shuffle + repeat one: the
 * 1 pops in, smaller, in the open wedge left of the crossing. Shuffle + repeat one → repeat one:
 * the strands turn back into the loop and the 1 slides with them to the loop's middle. Repeat
 * one → repeat all: the 1 shrinks away and both arrows nudge along their direction. Any other
 * change runs the same parts (the strands cross or uncross, the 1 pops in or out). With
 * [SymbolMotion.reduced] the symbol fades out and comes back in the new mode instead.
 *
 * ```
 * var mode by rememberSaveable { mutableStateOf(SymbolPlayMode.RepeatAll) }
 * IconButton(onClick = { mode = mode.next() }) {
 *     Icon(rememberPlayModeSymbolPainter(mode), contentDescription = "Play mode")
 * }
 * ```
 */
@Composable
public fun rememberPlayModeSymbolPainter(
    mode: SymbolPlayMode,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val cross = remember { Animatable(if (mode.shuffle) 1f else 0f) }
    val one = remember { Animatable(if (mode.repeatOne) 1f else 0f) }
    val nudge = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val shown = remember { arrayOf(mode) }

    LaunchedEffect(mode) {
        val from = shown[0]
        shown[0] = mode
        if (from == mode) return@LaunchedEffect
        val crossTarget = if (mode.shuffle) 1f else 0f
        val oneTarget = if (mode.repeatOne) 1f else 0f
        when {
            animationsOff() -> {
                alpha.snapTo(1f)
                cross.snapTo(crossTarget)
                one.snapTo(oneTarget)
            }
            motion.reduced -> {
                alpha.animateTo(0f, motion.effects)
                cross.snapTo(crossTarget)
                one.snapTo(oneTarget)
                alpha.animateTo(1f, motion.effects)
            }
            else -> coroutineScope {
                val turning = cross.targetValue != crossTarget
                if (alpha.value != 1f) launch { alpha.animateTo(1f, motion.effects) }
                launch { cross.animateTo(crossTarget, motion.spatial) }
                launch {
                    if (oneTarget > 0f) {
                        // A 1 already showing just rides along with the strands.
                        if (turning && one.value < 1f) delay(140L)
                        one.animateTo(oneTarget, motion.fastSpatial)
                    } else {
                        one.animateTo(0f, motion.control)
                    }
                }
                if (!turning && from == SymbolPlayMode.RepeatOne) {
                    launch { nudge.animateTo(0f, motion.fastSpatial, initialVelocity = 30f) }
                }
            }
        }
    }

    return symbolPainter("PlayModeSymbol") {
        val t = cross.value
        val a = alpha.value.coerceIn(0f, 1f)
        val n = nudge.value
        StrandPath(chainPath(SymbolGeometry.PlayModeLoopA, SymbolGeometry.PlayModeCrossA, t), a)
        HeadPath(SymbolGeometry.PlayModeHeadLoopA, SymbolGeometry.PlayModeHeadCrossA, t, n, a)
        // The under strand is stored mirrored and turns over (scaleX 1 → -1) on its way to shuffle,
        // opening its gap under the other strand as it lands.
        Group(name = "under", pivotX = 12f, pivotY = 12f, scaleX = cos(PI.toFloat() * t)) {
            val under = chainPath(SymbolGeometry.PlayModeLoopB, SymbolGeometry.PlayModeCrossB, t)
            val gap = SymbolGeometry.PlayModeGap * ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (gap > 0.001f) {
                StrandPath(under, a, trimEnd = 0.5f - gap)
                StrandPath(under, a, trimStart = 0.5f + gap)
            } else {
                StrandPath(under, a)
            }
            HeadPath(SymbolGeometry.PlayModeHeadLoopB, SymbolGeometry.PlayModeHeadCrossB, t, -n, a)
        }
        val s = one.value.coerceAtLeast(0f)
        if (s > 0.001f) {
            // Over the loop the 1 sits in its middle; as the strands cross it slides, smaller, into
            // the open wedge left of the crossing (shuffle + repeat one).
            val at = t.coerceIn(0f, 1f)
            val scale = s * lerp(1f, SymbolGeometry.PlayModeOneCrossScale, at)
            Group(
                name = "one",
                pivotX = SymbolGeometry.PlayModeOneX,
                pivotY = SymbolGeometry.PlayModeOneY,
                scaleX = scale,
                scaleY = scale,
                translationX = (SymbolGeometry.PlayModeOneCrossX - SymbolGeometry.PlayModeOneX) * at,
                translationY = (SymbolGeometry.PlayModeOneCrossY - SymbolGeometry.PlayModeOneY) * at,
            ) {
                HeadPath(SymbolGeometry.PlayModeOne, SymbolGeometry.PlayModeOne, 0f, 0f, a)
            }
        }
    }
}

@Composable
@VectorComposable
private fun StrandPath(nodes: List<PathNode>, alpha: Float, trimStart: Float = 0f, trimEnd: Float = 1f) {
    Path(
        pathData = nodes,
        stroke = Ink,
        strokeAlpha = alpha,
        strokeLineWidth = YoinSymbolStrokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        trimPathStart = trimStart,
        trimPathEnd = trimEnd,
    )
}

/** A three-point polyline (arrowhead or the 1) between two poses, shifted by [dx]. */
@Composable
@VectorComposable
private fun HeadPath(from: FloatArray, to: FloatArray, t: Float, dx: Float, alpha: Float) {
    Path(
        pathData = PathData {
            for (i in 0 until 3) {
                val x = lerp(from[2 * i], to[2 * i], t) + dx
                val y = lerp(from[2 * i + 1], to[2 * i + 1], t)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        },
        stroke = Ink,
        strokeAlpha = alpha,
        strokeLineWidth = YoinSymbolStrokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

/** A chain of cubic segments (start point, then c1, c2, end per segment) between two poses. */
private fun chainPath(from: FloatArray, to: FloatArray, t: Float): List<PathNode> = PathData {
    moveTo(lerp(from[0], to[0], t), lerp(from[1], to[1], t))
    var i = 2
    while (i < from.size) {
        curveTo(
            lerp(from[i], to[i], t),
            lerp(from[i + 1], to[i + 1], t),
            lerp(from[i + 2], to[i + 2], t),
            lerp(from[i + 3], to[i + 3], t),
            lerp(from[i + 4], to[i + 4], t),
            lerp(from[i + 5], to[i + 5], t),
        )
        i += 6
    }
}

// ---------------------------------------------------------------- favorite

/**
 * Favorite: the outline beats once (0.8 → 1) and the fill grows from the centre.
 * Removing a favorite only shrinks the fill, without the beat.
 */
@Composable
public fun rememberFavoriteSymbolPainter(
    favorite: Boolean,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val fill = remember { Animatable(if (favorite) 1f else 0f) }
    val beat = remember { Animatable(1f) }
    val started = remember { BooleanArray(1) }

    LaunchedEffect(favorite) {
        if (!started[0]) {
            started[0] = true
            return@LaunchedEffect
        }
        if (favorite) {
            if (!motion.reduced) {
                launch {
                    beat.snapTo(0.8f)
                    beat.animateTo(1f, motion.fastSpatial)
                }
            }
            fill.animateTo(1f, if (motion.reduced) motion.effects else motion.fastSpatial)
        } else {
            fill.animateTo(0f, if (motion.reduced) motion.effects else motion.control)
        }
    }

    return symbolPainter("FavoriteSymbol") {
        val b = beat.value
        val f = fill.value
        Group(name = "beat", pivotX = 12f, pivotY = 12.2f, scaleX = b, scaleY = b) {
            Path(
                pathData = HeartNodes,
                stroke = Ink,
                strokeLineWidth = YoinSymbolStrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            if (motion.reduced) {
                val alpha = f.coerceIn(0f, 1f)
                Path(
                    pathData = HeartNodes,
                    fill = Ink,
                    fillAlpha = alpha,
                    stroke = Ink,
                    strokeAlpha = alpha,
                    strokeLineWidth = YoinSymbolStrokeWidth,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else if (f > 0.001f) {
                Group(name = "fill", pivotX = 12f, pivotY = 12.2f, scaleX = f, scaleY = f) {
                    Path(
                        pathData = HeartNodes,
                        fill = Ink,
                        stroke = Ink,
                        strokeLineWidth = YoinSymbolStrokeWidth,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- expand / collapse

/**
 * A chevron that flattens and folds over, like a hinge, instead of spinning:
 * points down while collapsed and up while [expanded].
 */
@Composable
public fun rememberExpandSymbolPainter(
    expanded: Boolean,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val t by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (motion.reduced) motion.control else motion.spatial,
        label = "expand",
    )
    return symbolPainter("ExpandSymbol") {
        val down = SymbolGeometry.ChevronDown
        val up = SymbolGeometry.ChevronUp
        val data = PathData {
            moveTo(lerp(down[0], up[0], t), lerp(down[1], up[1], t))
            lineTo(lerp(down[2], up[2], t), lerp(down[3], up[3], t))
            lineTo(lerp(down[4], up[4], t), lerp(down[5], up[5], t))
        }
        Path(
            pathData = data,
            stroke = Ink,
            strokeLineWidth = YoinSymbolStrokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}

// ---------------------------------------------------------------- equalizer

/**
 * A now-playing equalizer. While [playing] each bar chases its own random level; when
 * playback stops the bars sink into dots from the edges inward on a slow, critically
 * damped spring, so the sound fades out instead of cutting off. A symbol that first
 * appears while not playing starts as dots.
 *
 * With [SymbolMotion.reduced] the bars hold their resting heights and the whole symbol
 * dims to 45 % while stopped.
 */
@Composable
public fun rememberEqualizerSymbolPainter(
    playing: Boolean,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val rest = SymbolGeometry.EqualizerHeights
    val bars = remember {
        List(rest.size) { Animatable(if (playing || motion.reduced) rest[it] else 0f) }
    }
    val dim by animateFloatAsState(
        targetValue = if (motion.reduced && !playing) 0.45f else 1f,
        animationSpec = motion.effects,
        label = "equalizerDim",
    )

    LaunchedEffect(playing, motion.reduced) {
        val off = animationsOff()
        bars.forEachIndexed { i, bar ->
            launch {
                when {
                    motion.reduced -> bar.animateTo(rest[i], motion.effects)
                    off -> bar.snapTo(if (playing) rest[i] else 0f)
                    playing -> {
                        val peak = max(rest[i], 7f) * 1.12f
                        // The timeout below is wall-clock time, so without the frame wait the
                        // loop would keep spinning while the app is in the background and no
                        // frames arrive. It also ends if animations are turned off mid-playback.
                        while (isActive && !animationsOff()) {
                            withFrameNanos { }
                            val target = peak * (0.28f + 0.72f * Random.nextFloat())
                            withTimeoutOrNull(Random.nextLong(120L, 260L)) {
                                bar.animateTo(target, motion.fastSpatial)
                            }
                        }
                        if (isActive) bar.snapTo(rest[i])
                    }
                    else -> {
                        delay(EqualizerStagger[i])
                        bar.animateTo(0f, motion.resonance)
                    }
                }
            }
        }
    }

    return symbolPainter("EqualizerSymbol") {
        bars.forEachIndexed { i, bar ->
            val x = SymbolGeometry.EqualizerX[i]
            val half = max(0f, bar.value) / 2f
            Path(
                pathData = PathData {
                    moveTo(x, 12f - half)
                    lineTo(x, 12f + half)
                },
                stroke = Ink,
                strokeAlpha = dim.coerceIn(0f, 1f),
                strokeLineWidth = YoinSymbolStrokeWidth,
                strokeLineCap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------- sparkle (AI)

/**
 * The AI sparkle. While [generating], the large star turns a quarter turn and rests,
 * and the small star blinks off-beat. [filled] cross-fades to the solid version,
 * for example once a result has arrived.
 */
@Composable
public fun rememberSparkleSymbolPainter(
    generating: Boolean,
    filled: Boolean = false,
    motion: SymbolMotion = LocalSymbolMotion.current,
): Painter {
    val rotation = remember { Animatable(0f) }
    val mini = remember { Animatable(1f) }
    val breath = remember { Animatable(1f) }
    val fill by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = motion.effects,
        label = "sparkleFill",
    )
    val generatingNow by rememberUpdatedState(generating)

    LaunchedEffect(motion) {
        while (isActive) {
            if (!generatingNow) {
                breath.animateTo(1f, motion.slowEffects)
                awaitUntil { generatingNow }
            } else if (animationsOff()) {
                breath.snapTo(1f)
                mini.snapTo(1f)
                awaitUntil { !generatingNow }
            } else if (motion.reduced) {
                breath.animateTo(0.45f, motion.slowEffects)
                breath.animateTo(1f, motion.slowEffects)
            } else {
                coroutineScope {
                    launch { rotation.animateTo(rotation.targetValue + 90f, motion.spatial) }
                    launch {
                        mini.animateTo(0.35f, motion.fastSpatial)
                        mini.animateTo(1f, motion.fastSpatial)
                    }
                }
                delay(300L)
            }
        }
    }

    return symbolPainter("SparkleSymbol") {
        val alpha = breath.value.coerceIn(0f, 1f)
        val f = fill.coerceIn(0f, 1f)
        Group(
            name = "main",
            rotation = rotation.value,
            pivotX = SymbolGeometry.SparkleMainX,
            pivotY = SymbolGeometry.SparkleMainY,
        ) {
            SparklePath(SparkleMainNodes, alpha, f)
        }
        val s = max(0f, mini.value)
        Group(
            name = "mini",
            pivotX = SymbolGeometry.SparkleMiniX,
            pivotY = SymbolGeometry.SparkleMiniY,
            scaleX = s,
            scaleY = s,
        ) {
            SparklePath(SparkleMiniNodes, alpha, f)
        }
    }
}

@Composable
@VectorComposable
private fun SparklePath(nodes: List<PathNode>, alpha: Float, fill: Float) {
    Path(
        pathData = nodes,
        fill = Ink,
        fillAlpha = alpha * fill,
        stroke = Ink,
        strokeAlpha = alpha,
        strokeLineWidth = YoinSymbolStrokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
