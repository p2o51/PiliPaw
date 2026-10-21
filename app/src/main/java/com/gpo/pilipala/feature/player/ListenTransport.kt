package com.gpo.pilipala.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.SymbolPlayMode
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import kotlin.math.roundToInt

/**
 * 仅听声音's transport, laid out like Yoin's Now Playing, a size down: the play / pause pill
 * and 下一个 in primary with the play-mode key at the far end, then 上一个 (secondary), the
 * playhead, Yoin's wave scrubber and the time left. The play-mode key steps the queue's
 * 列表循环 → 随机 → 单曲循环; a single video has no list, so there it is 单曲循环 on / off
 * (洗脑循环).
 */
@Composable
internal fun ListenTransport(
    ctrl: PiliPlayerController,
    controlSize: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    val iconSize = controlSize * 0.5f
    val playPadding by animateDpAsState(
        targetValue = if (playing) 22.dp else 14.dp,
        animationSpec = YoinMotion.fastSpatialSpec(),
        label = "listenPlayPadding",
    )
    val nextAlpha by animateFloatAsState(
        targetValue = if (ctrl.hasNext) 1f else 0.38f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "listenNext",
    )
    val d = ctrl.durationMs
    val live = rememberLivePosition(ctrl, running = true)
    val timeStyle = MaterialTheme.typography.labelLarge.withTabularFigures()
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(ListenRowGap),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    ctrl.togglePlay()
                },
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = playPadding),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                modifier = Modifier.height(controlSize),
            ) {
                Icon(
                    rememberPlayPauseSymbolPainter(playing),
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(iconSize),
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    ctrl.skipNext()
                },
                enabled = ctrl.hasNext,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.primary,
                    disabledContentColor = scheme.onPrimary,
                ),
                modifier = Modifier.size(controlSize).graphicsLayer { alpha = nextAlpha },
            ) {
                Icon(YoinSymbols.SkipNextFilled, contentDescription = "下一个", modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.weight(1f))
            ListenPlayModeButton(ctrl, controlSize, iconSize)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    ctrl.skipPrevious()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.secondary,
                    contentColor = scheme.onSecondary,
                ),
                modifier = Modifier.size(controlSize),
            ) {
                Icon(YoinSymbols.SkipPreviousFilled, contentDescription = "上一个", modifier = Modifier.size(iconSize))
            }
            Text(
                clock(ctrl.positionMs),
                style = timeStyle,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.defaultMinSize(minWidth = 40.dp),
            )
            ListenWaveBar(
                progress = { if (d > 0) live.value.toFloat() / d else 0f },
                buffered = if (d > 0) ctrl.bufferedMs.toFloat() / d else 0f,
                durationMs = d,
                playing = playing,
                onSeek = { f -> ctrl.seekTo((f * d).toLong()) },
                modifier = Modifier.weight(1f).height(controlSize),
            )
            Text(
                "-" + clock((d - ctrl.positionMs).coerceAtLeast(0L)),
                style = timeStyle,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.defaultMinSize(minWidth = 46.dp),
            )
        }
    }
}

/** The transport's height for [controlSize]: two rows, the gap and the vertical padding. */
internal fun listenTransportHeight(controlSize: Dp): Dp = controlSize * 2 + ListenRowGap + 12.dp

private val ListenRowGap = 4.dp

/**
 * Yoin's play-mode key: a circle showing the mode's symbol, resting on tertiaryContainer in
 * 列表循环 and lit (primary) otherwise; a tap steps the mode.
 */
@Composable
private fun ListenPlayModeButton(ctrl: PiliPlayerController, size: Dp, iconSize: Dp) {
    val haptics = LocalHapticFeedback.current
    val queue = ctrl.queue
    val symbol: SymbolPlayMode
    val label: String
    val lit: Boolean
    val onStep: () -> Unit
    if (queue != null) {
        val mode by queue.mode.collectAsState()
        symbol = mode.symbol
        label = mode.label
        lit = mode != PlayMode.ListLoop
        onStep = { queue.cycleMode() }
    } else {
        val loop = ctrl.loop
        symbol = if (loop) SymbolPlayMode.RepeatOne else SymbolPlayMode.RepeatAll
        label = if (loop) "单曲循环" else "不循环"
        lit = loop
        onStep = { ctrl.changeLoop(!loop) }
    }
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (lit) scheme.primary else scheme.tertiaryContainer,
        YoinMotion.defaultEffectsSpec(),
        label = "listenModeContainer",
    )
    val content by animateColorAsState(
        if (lit) scheme.onPrimary else scheme.onTertiaryContainer,
        YoinMotion.defaultEffectsSpec(),
        label = "listenModeContent",
    )
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onStep()
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .size(size)
            .semantics {
                contentDescription = "播放模式"
                stateDescription = label
            },
    ) {
        Icon(rememberPlayModeSymbolPainter(symbol), contentDescription = null, modifier = Modifier.size(iconSize))
    }
}

/**
 * Yoin's wave scrubber (WaveProgressBar), trimmed: M3E's wavy indicator flowing while it
 * plays and flat while paused; a tap seeks, a drag scrubs with a time bubble above the head.
 */
@Composable
private fun ListenWaveBar(
    progress: () -> Float,
    buffered: Float,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var trackWidthPx by remember { mutableIntStateOf(1) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestDrag = rememberUpdatedState(dragFraction)
    val latestProgress = rememberUpdatedState(progress)
    val indicatorProgress: () -> Float = remember {
        { (latestDrag.value ?: latestProgress.value()).coerceIn(0f, 1f) }
    }
    val base = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val stroke = remember(base) { Stroke(width = base.width * 0.92f, cap = base.cap) }
    val trackStroke = remember(stroke) { Stroke(width = stroke.width, cap = StrokeCap.Butt) }
    val rest = MaterialTheme.colorScheme.onSurface
    val trackColor = rest.copy(alpha = if (buffered > (progress() + 0.02f)) 0.2f else 0.14f)
    val amplitude = if (playing) 0.28f else 0f
    Box(
        modifier
            .pointerInput(durationMs) {
                detectTapGestures(
                    onPress = { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        if (tryAwaitRelease()) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            latestOnSeek(f)
                        }
                    },
                )
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            latestOnSeek(it)
                        }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { _, dx ->
                        dragFraction = ((dragFraction ?: 0f) + dx / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = indicatorProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .onSizeChanged { trackWidthPx = it.width },
            color = MaterialTheme.colorScheme.primary,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
            gapSize = 1.dp,
            stopSize = 0.dp,
            amplitude = { amplitude },
            wavelength = 24.dp,
            waveSpeed = if (playing) 24.dp else 0.dp,
        )
        dragFraction?.let { f ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged {
                        bubbleWidthPx = it.width
                        bubbleHeightPx = it.height
                    }
                    .offset {
                        val x = (trackWidthPx * f - bubbleWidthPx / 2f).roundToInt()
                            .coerceIn(0, (trackWidthPx - bubbleWidthPx).coerceAtLeast(0))
                        IntOffset(x, -bubbleHeightPx)
                    },
            ) {
                Text(
                    clock((durationMs * f).toLong()),
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
