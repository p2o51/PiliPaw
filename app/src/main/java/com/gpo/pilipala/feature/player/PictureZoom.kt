package com.gpo.pilipala.feature.player

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** 画面: how the picture sits in its box. */
internal enum class PictureFit(val label: String, val scale: ContentScale) {
    Fit("适应", ContentScale.Fit),
    Fill("填充", ContentScale.Crop),
    Stretch("拉伸", ContentScale.FillBounds),
}

internal val PictureFitSetting = AppSettings.register("player_picture_fit", PictureFit.Fit.ordinal)

internal fun pictureFitOf(v: Int): PictureFit = PictureFit.entries.getOrElse(v) { PictureFit.Fit }

/**
 * Two-finger zoom on the fullscreen picture: [scale] and [offset] (px, from the centre) are
 * drawn on the video view only, so danmaku, subtitles and the card around it stay put.
 * Released, it springs to the nearest rest: 适应 (1x), 填充 (the scale that covers the frame)
 * or wherever it was let go, pulled back inside the frame.
 */
@Stable
internal class PictureZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var job: Job? = null

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Back to 适应 at once (leaving fullscreen, into PiP). */
    fun snapHome() {
        stop()
        scale = 1f
        offset = Offset.Zero
    }

    fun springHome(scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) = springTo(scope, spec, 1f, Offset.Zero)

    fun springTo(scope: CoroutineScope, spec: FiniteAnimationSpec<Float>, s: Float, o: Offset) {
        stop()
        val s0 = scale
        val o0 = offset
        if (s0 == s && o0 == o) return
        job = scope.launch {
            animate(0f, 1f, animationSpec = spec) { v, _ ->
                scale = lerp(s0, s, v)
                offset = lerp(o0, o, v)
            }
        }
    }

    /** One pinch step: [zoom] about [centroid] (local px of a [size] box) plus [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, size: Size) {
        val s0 = scale
        val s = (s0 * zoom).coerceIn(MinPinchScale, MaxScale * 1.15f)
        val z = s / s0
        val centre = Offset(size.width / 2f, size.height / 2f)
        // Keep the content under the fingers under the fingers.
        offset = centroid + pan - centre - (centroid - centre - offset) * z
        scale = s
    }
}

private const val MinPinchScale = 0.7f
private const val MaxScale = 5f

/** How close (relative) a release must be to a rest to land on it. */
private const val SnapReach = 0.12f

/**
 * The rest a release lands on: 1x and, for a fitted picture, the scale that covers [box];
 * otherwise the scale it was let go at, kept inside [1, MaxScale], with the picture pulled
 * back so it never leaves a gap it does not need.
 */
internal fun pinchRest(scale: Float, offset: Offset, box: Size, aspect: Float, fit: PictureFit): Pair<Float, Offset> {
    val w = box.width
    val h = box.height
    if (w <= 0f || h <= 0f) return 1f to Offset.Zero
    val a = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    // The picture's own size at 1x.
    val pw = if (fit == PictureFit.Fit) minOf(w, h * a) else w
    val ph = if (fit == PictureFit.Fit) pw / a else h
    val cover = max(w / pw, h / ph)
    val s = when {
        scale < 1f + SnapReach -> 1f
        fit == PictureFit.Fit && cover > 1.01f && abs(scale - cover) / cover < SnapReach -> cover
        else -> scale.coerceAtMost(MaxScale)
    }
    if (s == 1f) return 1f to Offset.Zero
    val mx = ((pw * s - w) / 2f).coerceAtLeast(0f)
    val my = ((ph * s - h) / 2f).coerceAtLeast(0f)
    return s to Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
}

/**
 * The pinch, on the picture's gesture layer. It watches the Initial pass and steps in only
 * once a second finger is down (and [enabled] says so), consuming everything from then until
 * every finger lifts: the press detector then sees its press taken (a hold or a seek never
 * starts, a seek drag already running is dropped without seeking) and the tap detector sees
 * no tap. A single finger is never touched.
 */
internal suspend fun PointerInputScope.detectPicturePinch(
    zoom: PictureZoom,
    enabled: () -> Boolean,
    onStart: () -> Unit,
    onRelease: (box: Size) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pinching = false
    var event = currentEvent
    try {
        while (true) {
            var down = 0
            event.changes.fastForEach { if (it.pressed) down++ }
            if (down == 0) break
            if (!pinching && down >= 2 && enabled()) {
                pinching = true
                zoom.stop()
                onStart()
            }
            if (pinching && enabled()) {
                val box = Size(size.width.toFloat(), size.height.toFloat())
                val centroid = event.calculateCentroid(useCurrent = true)
                zoom.transform(
                    centroid = if (centroid == Offset.Unspecified) box.center() else centroid,
                    pan = event.calculatePan(),
                    zoom = if (down >= 2) event.calculateZoom() else 1f,
                    size = box,
                )
            }
            if (pinching) event.changes.fastForEach { it.consume() }
            event = awaitPointerEvent(PointerEventPass.Initial)
        }
    } finally {
        if (pinching) {
            onRelease(Size(size.width.toFloat(), size.height.toFloat()))
        }
    }
}

private fun Size.center() = Offset(width / 2f, height / 2f)

/** 画面 in the settings sheet: a connected 适应 / 填充 / 拉伸 group. */
@Composable
internal fun PictureFitChoice() {
    val haptics = LocalHapticFeedback.current
    val current by PictureFitSetting.state.collectAsState()
    Text(
        "画面",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
    )
    val fits = PictureFit.entries
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        fits.forEachIndexed { i, f ->
            ToggleButton(
                checked = current == f.ordinal,
                onCheckedChange = {
                    if (current != f.ordinal) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        AppSettings.set(PictureFitSetting, f.ordinal)
                    }
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    fits.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(f.label)
            }
        }
    }
}
