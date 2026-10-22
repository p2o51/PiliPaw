package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import org.schabi.newpipe.extractor.stream.Frameset
import kotlin.math.roundToInt

/** The seek bar's three strengths: rest, buffered, played (and the thumb). */
internal class TrackInk(val rest: Color, val buffered: Color, val played: Color)

/** On the black fullscreen frame: plain white at three strengths (a scrim colour). */
internal val VideoTrackInk = TrackInk(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.35f), Color.White)

/** Under the picture, on the page: the page's own colours. */
@Composable
internal fun pageTrackInk(): TrackInk {
    val scheme = MaterialTheme.colorScheme
    return TrackInk(scheme.onSurface.copy(alpha = 0.1f), scheme.onSurface.copy(alpha = 0.2f), scheme.primary)
}

/**
 * The seek bar (PiliPaw's, without chapters and the heat hill): a thin rounded track, played /
 * buffered / rest. Pressing anywhere scrubs from there: the track thickens, a pill thumb grows in
 * on a spring with a tick, and [SeekBubbleLayer] shows the frame and the time above the thumb.
 * Letting go seeks.
 */
@Composable
internal fun PawSeekBar(seek: SeekState, ink: TrackInk, onPoke: () -> Unit = {}, modifier: Modifier = Modifier) {
    val chapters = Player.chapters
    val skips = if (Segments.enabled.state.collectAsState().value) Player.skips else emptyList()
    val skipColor = MaterialTheme.colorScheme.tertiary
    // A segment just skipped closes up on the bar (§4 B): it shrinks to nothing about its middle.
    val closing = remember { Animatable(1f) }
    val lastSkip = Player.lastSkip
    val closeSpec = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    LaunchedEffect(lastSkip) {
        closing.snapTo(1f)
        if (lastSkip != null) closing.animateTo(0f, closeSpec)
    }
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(
        if (pressed) 1f else 0f,
        YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive),
        label = "seekGrow",
    )
    val latestPoke by rememberUpdatedState(onPoke)
    val duration = seek.duration
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .onGloballyPositioned { seek.track = it.boundsInWindow() }
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (duration <= 0L) return@awaitEachGesture
                    down.consume()
                    fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    pressed = true
                    seek.onScrub(at(down.position.x))
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    latestPoke()
                    var committed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                committed = true
                                break
                            }
                            seek.onScrub(at(change.position.x))
                            latestPoke()
                        }
                    } finally {
                        pressed = false
                        if (committed) {
                            seek.onScrubEnd()
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        } else {
                            seek.onScrubCancel()
                        }
                    }
                }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen, so the thumb's gaps can be cut out of the track.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val cy = size.height / 2f
            val th = (3.dp + 5.dp * grow).toPx()
            val top = cy - th / 2f
            fun bar(color: Color, from: Float, to: Float) {
                val len = to - from
                if (len <= 0f) return
                val r = minOf(th / 2f, len / 2f)
                drawRoundRect(color, Offset(from, top), Size(len, th), CornerRadius(r, r))
            }
            val played = seek.fraction
            val d = seek.duration.toFloat()
            bar(ink.rest, 0f, w)
            bar(ink.buffered, 0f, w * seek.bufferedFraction)
            bar(ink.played, 0f, w * played)
            if (d > 0f) {
                // SponsorBlock: each segment a block in the tertiary colour over the track.
                for (sk in skips) {
                    var a = w * (sk.startMs / d)
                    var b = w * (sk.endMs / d).coerceAtMost(1f)
                    if (sk.id == lastSkip?.id) {
                        val mid = (a + b) / 2f
                        val half = (b - a) / 2f * closing.value
                        a = mid - half
                        b = mid + half
                    }
                    if (b - a > 0.5f) drawRect(skipColor, Offset(a, top), Size(b - a, th))
                }
                // Chapters: a 2dp seam through the track at each start (§4 B: 章节是缝).
                val gap = 2.dp.toPx()
                for (c in chapters) {
                    val f = c.startMs / d
                    if (f <= 0.002f || f >= 0.998f) continue
                    val x = w * f
                    drawRect(Color.Black, Offset(x - gap / 2f, top - 1f), Size(gap, th + 2f), blendMode = BlendMode.Clear)
                }
            }
            if (grow > 0.01f) {
                val x = w * played
                val thumbW = 4.dp.toPx()
                val g = grow.coerceAtMost(1f)
                val thumbH = (th + 10.dp.toPx()) * g
                val side = 3.dp.toPx() * g
                drawRect(Color.Black, Offset(x - thumbW / 2f - side, top - 1f), Size(thumbW + side * 2f, th + 2f), blendMode = BlendMode.Clear)
                drawRoundRect(ink.played, Offset(x - thumbW / 2f, cy - thumbH / 2f), Size(thumbW, thumbH), CornerRadius(thumbW / 2f, thumbW / 2f))
            }
        }
    }
}

/**
 * The bubble above the seek thumb while scrubbing: the storyboard frame (when YouTube has one
 * for the video) over the time. It lives in a full-size layer drawn above the picture, and
 * rides the thumb from [SeekState.track] (window coordinates), so the inline bar under the
 * picture and the fullscreen bar share it.
 */
@Composable
internal fun SeekBubbleLayer(seek: SeekState, frames: Frameset?, big: Boolean, modifier: Modifier = Modifier) {
    val scrubbing = seek.scrubbing && seek.duration > 0
    // The last value stays drawn while the bubble scales out.
    val last = remember { floatArrayOf(0f) }
    if (scrubbing) last[0] = seek.scrub
    val f = if (scrubbing) seek.scrub else last[0]
    val timeMs = (f * seek.duration).toLong()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val width: Dp = if (big) 192.dp else 152.dp
    Layout(
        content = {
            AnimatedVisibility(
                visible = scrubbing,
                enter = fadeIn(YoinMotion.fastEffectsSpec()) +
                    scaleIn(YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) +
                    scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (frames != null) {
                        StoryboardFrame(frames, timeMs, width)
                        Spacer(Modifier.height(6.dp))
                    }
                    // One dark block with the chapter over the time (PiliPaw's bubble).
                    val chapter = Player.chapters.at(timeMs)
                    Column(
                        Modifier
                            .background(Color.Black.copy(alpha = 0.62f), YoinShapeTokens.Large)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (chapter != null && chapter.title.isNotEmpty()) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = width),
                            )
                        }
                        Text(
                            Fmt.duration(timeMs / 1000).ifEmpty { "0:00" },
                            style = (if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge).withTabularFigures(),
                            color = Color.White,
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft },
    ) { measurables, constraints ->
        val p = measurables.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (p != null) {
                val track = seek.track
                val cx = track.left - origin.x + track.width * f
                val x = (cx - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                val y = (track.top - origin.y - 18.dp.toPx() - p.height).roundToInt().coerceAtLeast(0)
                p.place(IntOffset(x, y))
            }
        }
    }
}

/**
 * The YouTube storyboard: the frameset's sheets are grids of [Frameset.getFramesPerPageX] ×
 * [Frameset.getFramesPerPageY] tiles, one every [Frameset.getDurationPerFrame] ms. Each sheet is
 * loaded once through the app's image loader (and its neighbours, so a scrub across a sheet
 * edge never waits); the tile at [timeMs] is drawn [width] wide. A dark plate until it lands.
 */
@Composable
private fun StoryboardFrame(frames: Frameset, timeMs: Long, width: Dp) {
    val cols = frames.framesPerPageX.coerceAtLeast(1)
    val rows = frames.framesPerPageY.coerceAtLeast(1)
    val perSheet = cols * rows
    val frame = (timeMs / frames.durationPerFrame.coerceAtLeast(1)).toInt().coerceIn(0, (frames.totalCount - 1).coerceAtLeast(0))
    val sheetIndex = frame / perSheet
    val context = LocalContext.current
    val sheets = remember(frames) { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(frames, sheetIndex) {
        suspend fun load(i: Int) {
            val url = frames.urls.getOrNull(i) ?: return
            if (sheets.containsKey(i)) return
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return
            sheets[i] = result.image.toBitmap().asImageBitmap()
        }
        sheets.keys.filter { it !in sheetIndex - 1..sheetIndex + 1 }.forEach { sheets.remove(it) }
        load(sheetIndex)
        load(sheetIndex + 1)
        load(sheetIndex - 1)
    }
    val tile = frame % perSheet
    val col = tile % cols
    val row = tile / cols
    val height = width * (frames.frameHeight.toFloat() / frames.frameWidth.coerceAtLeast(1))
    // The last tile drawn bridges the moment a sheet is still loading.
    val last = remember(frames) { arrayOfNulls<Any>(2) }
    val density = LocalDensity.current
    Canvas(
        Modifier
            .size(width, height)
            .clip(YoinShapeTokens.Medium)
            .background(Color.Black.copy(alpha = 0.6f)),
    ) {
        val sheet = sheets[sheetIndex]
        val src = if (sheet != null) {
            // The sheet as decoded (its own pixel size may differ from the frameset's numbers).
            val tw = sheet.width / cols
            val th = sheet.height / rows
            IntOffset(col * tw, row * th) to IntSize(tw, th)
        } else {
            null
        }
        val image = if (sheet != null && src != null) {
            last[0] = sheet
            last[1] = src
            sheet
        } else {
            last[0] as? ImageBitmap
        }
        @Suppress("UNCHECKED_CAST")
        val rect = src ?: (last[1] as? Pair<IntOffset, IntSize>)
        if (image == null || rect == null) return@Canvas
        drawImage(
            image = image,
            srcOffset = rect.first,
            srcSize = rect.second,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The storyboard to preview with: the smallest frameset at least [minWidth] px wide (else the largest). */
internal fun pickFrameset(frames: List<Frameset>, minWidth: Int): Frameset? =
    frames.filter { it.urls.isNotEmpty() && it.frameWidth > 0 }
        .sortedBy { it.frameWidth }
        .let { sorted -> sorted.firstOrNull { it.frameWidth >= minWidth } ?: sorted.lastOrNull() }
