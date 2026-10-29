package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.ContentFrame
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay

/**
 * Where playback is and what a finger on the seek bar is scrubbing to; one per seek bar.
 * The position is polled at 4 Hz (and not while scrubbing, so the thumb stays under the finger).
 */
@Stable
internal class SeekState {
    var position by mutableLongStateOf(0L)
        internal set
    var scrubbing by mutableStateOf(false)
        private set
    var scrub by mutableFloatStateOf(0f)
        private set

    /** Where the bar's track is, in window coordinates (the bubble rides above it). */
    var track by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
        internal set
    var buffered by mutableLongStateOf(0L)
        internal set

    val duration: Long get() = Player.durationMs
    val bufferedFraction: Float get() = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction: Float get() = if (scrubbing) scrub else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs: Long get() = if (scrubbing) (scrub * duration).toLong() else position

    fun onScrub(value: Float) {
        scrubbing = true
        scrub = value
    }

    fun onScrubCancel() {
        scrubbing = false
    }

    fun onScrubEnd() {
        val target = (scrub * duration).toLong()
        if (duration > 0) Player.exo.seekTo(target)
        position = target
        scrubbing = false
    }
}

@Composable
internal fun rememberSeekState(): SeekState {
    val state = remember { SeekState() }
    LaunchedEffect(state) {
        while (true) {
            if (!state.scrubbing) state.position = Player.exo.currentPosition
            state.buffered = Player.exo.bufferedPosition
            delay(250)
        }
    }
    return state
}

/** Playhead and duration as two labels (no joiner), tabular so they never jitter. */
@Composable
internal fun TimeLabels(seek: SeekState, variant: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge.withTabularFigures()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.duration(seek.shownMs / 1000).ifEmpty { "0:00" }, style = style)
        Spacer(Modifier.width(8.dp))
        Text(Fmt.duration(seek.duration / 1000), style = style, color = variant)
    }
}

/** Ink on the black fullscreen frame: plain white at three strengths, whatever the theme (a scrim colour). */
private val OnVideo = Color.White
private val OnVideoVariant = Color.White.copy(alpha = 0.72f)

/**
 * The fullscreen player (PiliPaw's fullscreen, fitted to PipePaw's overlay). The picture fills
 * the window; a tap brings the controls, and the picture makes room for them instead of being
 * covered: it shrinks (keeping its aspect) into the band between the top bar (back, title)
 * and the bottom bar (play, times, seek bar, exit), lifts to that band's centre and rounds its
 * corners ([pictureCard]). Nothing is ever drawn on the picture. The controls fold away three
 * seconds after the last touch while playing. Back leaves fullscreen.
 *
 * Window: system bars hidden; a phone turns to landscape for a landscape video; a large screen
 * tries a real rotation and, where the platform only letterboxes (the Pixel Tablet), turns
 * the picture itself and follows the accelerometer (PlayerWindow.kt).
 */
@Composable
internal fun FullscreenPlayer(session: Player.Session) {
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    val phone = configuration.smallestScreenWidthDp < 600
    val portraitVideo = Player.videoAspect < 1f
    FullscreenWindowEffect(activity, fullscreen = true, lockLandscape = phone && !portraitVideo)
    val windowPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val multiWindow = remember(configuration) { activity?.isInMultiWindowMode == true }
    val large = rememberLargeScreenFullscreen(activity, want = !phone && !portraitVideo && !multiWindow)
    val turned = large == LargeFullscreen.Turn && windowPortrait
    val turn = rememberLandscapeTurn(turned)
    val turnAngle = remember { Animatable(0f) }
    val turnSpec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(turned, turn) { turnAngle.animateTo(if (turned) turn else 0f, turnSpec) }

    // Predictive back: the whole fullscreen frame shrinks toward the centre and rounds with the
    // finger (the controls fade), a preview of leaving; letting go leaves, cancelling springs back.
    val back = remember { Animatable(0f) }
    val backScope = rememberCoroutineScope()
    PredictiveBackHandler { events ->
        try {
            events.collect { e -> back.snapTo(YoinMotion.backGestureEasing.transform(e.progress)) }
            Player.fullscreen = false
        } catch (e: CancellationException) {
            backScope.launch { back.animateTo(0f, turnSpec) }
            throw e
        }
    }

    var shown by remember { mutableStateOf(true) }
    var poked by remember { mutableLongStateOf(0L) }
    val playing = Player.isPlaying
    LaunchedEffect(shown, playing, poked) {
        if (shown && playing) {
            delay(AutoHideMs)
            shown = false
        }
    }
    val revealState = animateFloatAsState(
        if (shown) 1f else 0f,
        YoinMotion.defaultSpatialSpec(YoinMotionRole.Expressive),
        label = "fullscreenReveal",
    )
    val reveal: () -> Float = remember(revealState) { { revealState.value } }
    val revealing by remember(revealState) { derivedStateOf { revealState.value > 0.004f } }
    val seek = rememberSeekState()
    val feedback = remember { GestureFeedback() }
    val haptics = LocalHapticFeedback.current

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val b = back.value
                if (b > 0f) {
                    val k = 1f - BackShrink * b
                    scaleX = k
                    scaleY = k
                    shape = RoundedCornerShape(BackCorner * b)
                    clip = true
                }
            }
            .background(Color.Black)
            .turnedContent(turned) { turnAngle.value },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val short = maxHeight < 480.dp
            val topBand = if (short) 48.dp else 56.dp
            val bottomBand = if (short) 56.dp else 64.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .pictureCard(reveal, { Player.videoAspect }, topBand, bottomBand, MinCardScale),
            ) {
                if (session.info != null && session.error == null) {
                    ContentFrame(
                        player = Player.exo,
                        modifier = Modifier.fillMaxSize(),
                        // A TextureView: the card scales and clips it; a SurfaceView cannot be.
                        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        shutter = {},
                    )
                }
                SubtitleOverlay(big = true)
            }
            // Over the picture (its video view would take the touches), under the bars: taps
            // anywhere, the picture or the black around it, show and hide the controls.
            Box(
                Modifier
                    .fillMaxSize()
                    .pictureGestures(feedback, haptics) {
                        shown = !shown
                        poked = System.nanoTime()
                    }
                    .swipeDownToExit(back, backScope, turnSpec, haptics),
            )
            DoubleTapSeekLayer(feedback.skip, big = true)
            HoldSpeedPill(feedback, big = true, modifier = Modifier.align(Alignment.Center))
            val frames = remember(session.info) { pickFrameset(session.info?.previewFrames.orEmpty(), PreviewMinWidthPx) }
            if (revealing) {
                CompositionLocalProvider(LocalContentColor provides OnVideo) {
                    TopBar(
                        title = session.title,
                        height = topBand,
                        turned = turned,
                        reveal = reveal,
                        backFade = { (back.value * 3f).coerceAtMost(1f) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    BottomBar(
                        seek = seek,
                        height = bottomBand,
                        turned = turned,
                        reveal = reveal,
                        backFade = { (back.value * 3f).coerceAtMost(1f) },
                        onPoke = { poked = System.nanoTime() },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            SeekBubbleLayer(seek, frames, big = true)
        }
    }
}

@Composable
private fun TopBar(title: String, height: Dp, turned: Boolean, reveal: () -> Float, backFade: () -> Float, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = reveal().coerceIn(0f, 1f)
                alpha = p * (1f - backFade())
                translationY = (1f - p) * -14.dp.toPx()
            }
            .then(if (turned) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout))
            .height(height)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { Player.fullscreen = false }, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Back, contentDescription = "退出全屏")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val activity = LocalActivity.current
        if (activity != null && pipSupported(activity)) {
            IconButton(onClick = { enterPip(activity, Player.videoAspect, title = title) }, shapes = IconButtonDefaults.shapes()) {
                Icon(PiliSymbols.Pip, contentDescription = "画中画")
            }
        }
    }
}

@Composable
private fun BottomBar(seek: SeekState, height: Dp, turned: Boolean, reveal: () -> Float, backFade: () -> Float, onPoke: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = reveal().coerceIn(0f, 1f)
                alpha = p * (1f - backFade())
                translationY = (1f - p) * 14.dp.toPx()
            }
            .then(if (turned) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout))
            .height(height)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                Player.toggle()
                onPoke()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(if (Player.isPlaying) YoinSymbols.PauseFilled else YoinSymbols.PlayFilled, if (Player.isPlaying) "暂停" else "播放")
        }
        TimeLabels(seek, OnVideoVariant, Modifier.padding(start = 6.dp, end = 12.dp))
        PawSeekBar(seek, VideoTrackInk, onPoke, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        var sheet by remember { mutableStateOf(false) }
        val open = {
            sheet = true
            onPoke()
        }
        if (Player.subtitleTracks.isNotEmpty()) {
            IconButton(onClick = open, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(44.dp)) {
                Icon(PiliSymbols.Subtitles, contentDescription = "字幕", tint = if (Player.subtitleIndex >= 0) OnVideo else OnVideoVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (Player.speed == 1f) "倍速" else speedLabel(Player.speed), open)
            Pill(qualityLabel(Player.currentHeight), open)
        }
        if (sheet) PlaybackSheet(onDismiss = { sheet = false })
        IconButton(onClick = { Player.fullscreen = false }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(44.dp)) {
            Icon(PiliSymbols.FullscreenExit, contentDescription = "退出全屏")
        }
    }
}

private const val AutoHideMs = 3_000L

/** A swipe down from the middle third leaves fullscreen once it passes this and lifts, as B站's does. */
private val SwipeExitDistance = 72.dp

/** The swipe's travel, against the frame's height, that rides the back preview all the way. */
private const val SwipeRideFraction = 0.45f

/**
 * B站's fullscreen swipe down (PlayerControls.playerGestures mode 4): a drag that starts down in
 * the middle third (not from the very top or bottom edge, those are the system's) rides the same
 * shrink as predictive back with the finger, ticks as it crosses the line, and leaves fullscreen
 * if lifted past it; short of it, it springs back. The side thirds stay free.
 */
private fun Modifier.swipeDownToExit(
    back: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    settleSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
): Modifier = pointerInput(back) {
    val edge = 40.dp.toPx()
    val line = SwipeExitDistance.toPx()
    awaitEachGesture {
        val down = androidx.compose.foundation.gestures.awaitFirstDown(requireUnconsumed = false)
        if (down.position.y < edge || down.position.y > size.height - edge) return@awaitEachGesture
        if (down.position.x < size.width / 3f || down.position.x > size.width * 2f / 3f) return@awaitEachGesture
        var travel = 0f
        val slop = androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            if (over > 0f) {
                change.consume()
                travel = over
            }
        } ?: return@awaitEachGesture
        if (travel <= 0f) return@awaitEachGesture
        val ride = { scope.launch { back.snapTo(YoinMotion.backGestureEasing.transform((travel / (size.height * SwipeRideFraction)).coerceIn(0f, 1f))) } }
        ride()
        val ended = androidx.compose.foundation.gestures.verticalDrag(slop.id) { c ->
            val was = travel >= line
            travel = (travel + c.position.y - c.previousPosition.y).coerceAtLeast(0f)
            c.consume()
            if (was != (travel >= line)) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
            ride()
        }
        if (ended && travel >= line) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            Player.fullscreen = false
        } else {
            scope.launch { back.animateTo(0f, settleSpec) }
        }
    }
}

/** How far the back gesture shrinks the fullscreen frame, and its corners at full travel. */
private const val BackShrink = 0.12f
private val BackCorner = 28.dp

/** Storyboard frames at least this wide (px) for the scrub preview. */
internal const val PreviewMinWidthPx = 160

/** The card never shrinks below this (a very short window keeps a usable picture). */
private const val MinCardScale = 0.6f

/**
 * Fullscreen picture card (PiliPaw's pictureCard): while the controls show ([reveal] → 1) the
 * picture shrinks (keeping its aspect) into the band between the top and bottom bars, lifts to
 * that band's centre and rounds its corners; hidden, it is the plain full-size picture. The clip
 * morphs from the whole frame to the picture's own rect, so the letterbox (black on black) never
 * shows as a card edge.
 */
internal fun Modifier.pictureCard(
    reveal: () -> Float,
    aspect: () -> Float,
    topBand: Dp,
    bottomBand: Dp,
    minScale: Float,
): Modifier = graphicsLayer {
    val p = reveal()
    if (p <= 0.0005f) {
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        clip = false
        shape = RectangleShape
        return@graphicsLayer
    }
    val w = size.width
    val h = size.height
    val a = aspect().takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val pw = minOf(w, h * a)
    val ph = pw / a
    val fit = ((h - topBand.toPx() - bottomBand.toPx() - CardGap.toPx() * 2f) / ph).coerceIn(minScale, 1f)
    val s = 1f - (1f - fit) * p
    scaleX = s
    scaleY = s
    translationY = (topBand.toPx() - bottomBand.toPx()) / 2f * p
    val pc = p.coerceIn(0f, 1f)
    val insetX = (w - pw) / 2f * pc
    val insetY = (h - ph) / 2f * pc
    shape = PictureCardShape(Rect(insetX, insetY, w - insetX, h - insetY), CardRadius.toPx() * pc / s.coerceAtLeast(0.1f))
    clip = true
}

private val CardRadius = 20.dp
private val CardGap = 6.dp

private class PictureCardShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius, radius)))
}

/** A small label button on a translucent white plate (倍速, 画质). */
@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = com.gpo.pilipala.ui.theme.YoinShapeTokens.Full,
        color = OnVideo.copy(alpha = 0.16f),
        contentColor = OnVideo,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
