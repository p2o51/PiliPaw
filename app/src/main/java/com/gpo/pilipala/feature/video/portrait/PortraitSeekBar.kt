package com.gpo.pilipala.feature.video.portrait

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.abs

/** Touch zone of the seek bar (the bar itself is a 2dp line at its bottom). */
internal val SeekZone = 24.dp

/**
 * The thin progress line at the bottom of the picture. 2dp at rest; a touch swells it on a
 * spring, a horizontal drag scrubs (the big time readout rises above it) and the release
 * seeks; a tap seeks straight there. A vertical move hands the gesture back to the pager.
 */
@Composable
internal fun ImmersiveSeekBar(
    ctrl: PiliPlayerController?,
    state: BiliPlayerState,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val thickness by animateDpAsState(if (pressed) 10.dp else 2.dp, YoinMotion.fastSpatialSpec(), label = "seekThickness")
    val latestDuration by rememberUpdatedState(state.durationMs)
    val latestScrubbingChange by rememberUpdatedState(onScrubbingChange)

    // While playing, the line follows the player's exact clock every frame (drawn only, no recomposition).
    val clock = remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(ctrl, state.isPlaying) {
        if (ctrl == null || !state.isPlaying) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            clock.longValue = ctrl.exactPositionMs()
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = scrubbing,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.85f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.9f),
        ) {
            val d = state.durationMs
            Row(
                Modifier.padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    Fmt.durationMs((scrubFraction * d).toLong()),
                    style = MaterialTheme.typography.headlineMedium.onPicture(),
                    fontWeight = FontWeight.SemiBold,
                    color = OnPicture,
                )
                Text(
                    Fmt.durationMs(d),
                    style = MaterialTheme.typography.titleMedium.onPicture(),
                    color = OnPicture.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(SeekZone)
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (latestDuration <= 0) return@awaitEachGesture
                        val pad = horizontalPadding.toPx()
                        val w = (size.width - pad * 2).coerceAtLeast(1f)
                        fun fractionAt(x: Float) = ((x - pad) / w).coerceIn(0f, 1f)
                        val slop = viewConfiguration.touchSlop
                        pressed = true
                        var fraction = fractionAt(down.position.x)
                        var dragging = false
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!dragging) {
                                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                                    cancelled = true
                                    break
                                }
                                if (abs(dx) > slop) {
                                    dragging = true
                                    scrubbing = true
                                    latestScrubbingChange(true)
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                            if (dragging) {
                                fraction = fractionAt(change.position.x)
                                scrubFraction = fraction
                                change.consume()
                            }
                        }
                        if (!cancelled) {
                            val target = (fraction * latestDuration).toLong()
                            state.seekTo(target)
                            clock.longValue = target
                            if (dragging) haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                        pressed = false
                        if (dragging) {
                            scrubbing = false
                            latestScrubbingChange(false)
                        }
                    }
                }
                .drawBehind {
                    val d = state.durationMs
                    val t = thickness.toPx()
                    val pad = horizontalPadding.toPx()
                    val w = size.width - pad * 2
                    val cy = size.height - 7.dp.toPx()
                    val top = cy - t / 2f
                    val radius = CornerRadius(t / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(pad, top), Size(w, t), radius)
                    if (d > 0) {
                        val buffered = ((ctrl?.bufferedMs ?: 0L).toFloat() / d).coerceIn(0f, 1f)
                        val played = if (scrubbing) {
                            scrubFraction
                        } else {
                            ((if (state.isPlaying) clock.longValue else state.positionMs).toFloat() / d).coerceIn(0f, 1f)
                        }
                        if (buffered > 0f) {
                            drawRoundRect(Color.White.copy(alpha = 0.34f), Offset(pad, top), Size(w * buffered, t), radius)
                        }
                        if (played > 0f) {
                            drawRoundRect(
                                if (pressed) accent else Color.White.copy(alpha = 0.92f),
                                Offset(pad, top),
                                Size((w * played).coerceAtLeast(t), t),
                                radius,
                            )
                        }
                    }
                },
        )
    }
}
