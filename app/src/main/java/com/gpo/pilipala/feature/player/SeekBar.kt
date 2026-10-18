package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val ShortVideoMs = 6 * 60_000L

/** Track colours over the picture: plain white at three strengths (scrim-like colours). */
private val TrackRest = Color.White.copy(alpha = 0.18f)
private val TrackBuffered = Color.White.copy(alpha = 0.35f)
private val TrackPlayed = Color.White

/**
 * Where the seek bar sits inside the player frame and what it is scrubbing to, so the
 * floating time bubble (drawn in the frame's top layer, over the picture) can ride above
 * the thumb whichever strip the bar lives in.
 */
@Stable
internal class SeekScrub {
    /** Scrub position 0..1 while the bar is pressed, else null. */
    var fraction by mutableStateOf<Float?>(null)
    var trackLeft by mutableFloatStateOf(0f)
    var trackWidth by mutableFloatStateOf(0f)
    var trackTop by mutableFloatStateOf(0f)
}

/**
 * The playhead, read every frame while playing (ExoPlayer interpolates between the
 * controller's 4 Hz ticks), so the played edge glides instead of stepping. Read it only
 * in draw lambdas: it changes every frame.
 */
@Composable
internal fun rememberLivePosition(ctrl: PiliPlayerController, running: Boolean): State<Long> {
    val live: MutableLongState = remember { mutableLongStateOf(ctrl.positionMs) }
    val playing = ctrl.isPlaying
    LaunchedEffect(playing, running) {
        if (playing && running) {
            while (isActive) withFrameMillis { live.longValue = ctrl.exactPositionMs() }
        } else {
            snapshotFlow { ctrl.positionMs }.collect { live.longValue = it }
        }
    }
    return live
}

/**
 * The seek bar: a thin rounded track over the picture (played white, buffered and rest
 * white at lower strengths). Pressing it thickens the track, grows a pill thumb in on a
 * spring with a tick, and opens the bubble above the thumb (time, chapter, preview
 * frame). Chapters cut small gaps into the track. No wave: the picture is the show.
 */
@Composable
internal fun PlayerSeekBar(
    ctrl: PiliPlayerController,
    scrub: SeekScrub,
    onPoke: () -> Unit,
    frame: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val grow = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive),
        label = "seekGrow",
    )
    val live = rememberLivePosition(ctrl, running)
    val duration = ctrl.durationMs
    val chapters = ctrl.chapters
    val latestChapters by rememberUpdatedState(chapters)
    val latestPoke by rememberUpdatedState(onPoke)
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .onGloballyPositioned { c ->
                val f = frame()
                val origin = if (f != null && f.isAttached && c.isAttached) f.localPositionOf(c, Offset.Zero) else Offset.Zero
                scrub.trackLeft = origin.x
                scrub.trackWidth = c.size.width.toFloat()
                scrub.trackTop = origin.y + c.size.height / 2f
            }
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (duration <= 0L) return@awaitEachGesture
                    down.consume()
                    fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    var f = at(down.position.x)
                    pressed = true
                    scrub.fraction = f
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    latestPoke()
                    var lastChapter = latestChapters.at((f * duration).toLong())
                    var committed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                committed = true
                                break
                            }
                            f = at(change.position.x)
                            change.consume()
                            scrub.fraction = f
                            val chapter = latestChapters.at((f * duration).toLong())
                            if (chapter != lastChapter) {
                                lastChapter = chapter
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                            latestPoke()
                        }
                    } finally {
                        if (committed) {
                            ctrl.seekTo((f * duration).toLong())
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                        pressed = false
                        scrub.fraction = null
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen, so the chapter and thumb gaps can be cut out of the track.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val d = duration
            val g = grow.value
            val pos = scrub.fraction?.let { (it * d).toLong() } ?: live.value
            val played = if (d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
            val buffered = if (d > 0) (ctrl.bufferedMs.toFloat() / d).coerceIn(0f, 1f) else 0f
            drawSeekTrack(
                played = played,
                buffered = buffered,
                thickness = (3.dp + 5.dp * g).toPx(),
                grow = g,
                chapterStarts = if (d > 0) chapters.mapNotNull { c -> (c.fromMs.toFloat() / d).takeIf { it > 0.002f && it < 0.998f } } else emptyList(),
            )
        }
    }
}

/** Track + chapter gaps + (while pressed) the pill thumb with a gap on each side. */
private fun DrawScope.drawSeekTrack(
    played: Float,
    buffered: Float,
    thickness: Float,
    grow: Float,
    chapterStarts: List<Float>,
) {
    val w = size.width
    val cy = size.height / 2f
    val th = thickness.coerceAtLeast(1f)
    val top = cy - th / 2f
    fun bar(color: Color, from: Float, to: Float) {
        val len = to - from
        if (len <= 0f) return
        val r = minOf(th / 2f, len / 2f)
        drawRoundRect(color, Offset(from, top), Size(len, th), CornerRadius(r, r))
    }
    bar(TrackRest, 0f, w)
    bar(TrackBuffered, 0f, w * buffered)
    bar(TrackPlayed, 0f, w * played)
    // Chapter starts: 2dp gaps through the whole track.
    val gap = 2.dp.toPx()
    for (c in chapterStarts) {
        val x = w * c
        drawRect(Color.Black, Offset(x - gap / 2f, top - 1f), Size(gap, th + 2f), blendMode = BlendMode.Clear)
    }
    val gl = grow.coerceIn(0f, 1.2f)
    if (gl > 0.01f) {
        val x = w * played
        val thumbW = 4.dp.toPx()
        val thumbH = (th + 10.dp.toPx()) * gl.coerceAtMost(1f) + (gl - gl.coerceAtMost(1f)) * 6.dp.toPx()
        val side = 3.dp.toPx() * gl.coerceAtMost(1f)
        drawRect(
            Color.Black,
            Offset(x - thumbW / 2f - side, top - 1f),
            Size(thumbW + side * 2f, th + 2f),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            TrackPlayed,
            Offset(x - thumbW / 2f, cy - thumbH / 2f),
            Size(thumbW, thumbH),
            CornerRadius(thumbW / 2f, thumbW / 2f),
        )
    }
}

/**
 * The 2dp progress line along the bottom edge of the picture while the controls are
 * hidden. [alpha] follows the controls (shown → 0).
 */
@Composable
internal fun ProgressHairline(ctrl: PiliPlayerController, alpha: () -> Float, modifier: Modifier = Modifier) {
    val shownNow by remember { androidx.compose.runtime.derivedStateOf { alpha() > 0.01f } }
    // Per-frame only where the 4 Hz tick would visibly step (short videos); on a long one a
    // tick moves the line by under a pixel.
    val live = rememberLivePosition(ctrl, shownNow && ctrl.durationMs in 1..ShortVideoMs)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) },
    ) {
        val d = ctrl.durationMs
        if (d <= 0L) return@Canvas
        val played = (live.value.toFloat() / d).coerceIn(0f, 1f)
        val buffered = (ctrl.bufferedMs.toFloat() / d).coerceIn(0f, 1f)
        // A faint dark base keeps the line readable on bright frames.
        drawRect(Color.Black.copy(alpha = 0.28f))
        drawRect(TrackRest, size = Size(size.width, size.height))
        drawRect(TrackBuffered.copy(alpha = 0.22f), size = Size(size.width * buffered, size.height))
        drawRect(TrackPlayed, size = Size(size.width * played, size.height))
    }
}

/**
 * The bubble above the seek thumb while scrubbing: preview frame (when the video has
 * sprites), chapter title, time. Lives in the frame's top layer, positioned from
 * [SeekScrub]; [bottomLimit] keeps it above the strip inline.
 */
@Composable
internal fun SeekBubbleLayer(
    ctrl: PiliPlayerController,
    scrub: SeekScrub,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = scrub.fraction
    val shots = ctrl.shots
    val duration = ctrl.durationMs
    // The last value stays drawn while the bubble scales out.
    val last = remember { floatArrayOf(0f) }
    if (fraction != null) last[0] = fraction
    val f = fraction ?: last[0]
    val timeMs = (f * duration).toLong()
    val chapter = ctrl.chapters.at(timeMs)
    val thumbWidth: Dp = if (big) 192.dp else 152.dp
    Layout(
        content = {
            AnimatedVisibility(
                visible = fraction != null && duration > 0,
                enter = fadeIn(YoinMotion.fastEffectsSpec()) +
                    scaleIn(YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), initialScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) +
                    scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (shots != null) {
                        SeekThumbnail(shots = shots, timeMs = timeMs, width = thumbWidth)
                        Spacer(Modifier.height(6.dp))
                    }
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
                                color = OnVideoVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = thumbWidth),
                            )
                        }
                        Text(
                            clock(timeMs),
                            style = (if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge).withTabularFigures(),
                            color = OnVideo,
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val p = measurables.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (p != null) {
                val cx = scrub.trackLeft + scrub.trackWidth * f
                val x = (cx - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                val y = (scrub.trackTop - 18.dp.toPx() - p.height).roundToInt().coerceAtLeast(0)
                p.place(x, y)
            }
        }
    }
}
