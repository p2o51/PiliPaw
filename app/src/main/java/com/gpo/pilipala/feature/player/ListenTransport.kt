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
import androidx.compose.runtime.Stable
import com.gpo.pilipala.feature.listen.ListenMode
import com.gpo.pilipala.feature.listen.ListenPlayback
import com.gpo.pilipala.feature.listen.rememberListenPosition
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
    horizontalPadding: Dp = 16.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
) = ListenTransport(
    playback = remember(ctrl) { BiliListenPlayback(ctrl) },
    controlSize = controlSize,
    modifier = modifier,
    horizontalPadding = horizontalPadding,
    lyricsExpanded = lyricsExpanded,
    onExpandLyrics = onExpandLyrics,
)

/** The transport over either part's player ([ListenPlayback]). */
@Composable
internal fun ListenTransport(
    playback: ListenPlayback,
    controlSize: Dp,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val playing = playback.playing
    val iconSize = controlSize * 0.5f
    val playPadding by animateDpAsState(
        targetValue = if (playing) 22.dp else 14.dp,
        animationSpec = YoinMotion.fastSpatialSpec(),
        label = "listenPlayPadding",
    )
    val nextAlpha by animateFloatAsState(
        targetValue = if (playback.hasNext) 1f else 0.38f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "listenNext",
    )
    val d = playback.durationMs
    val live = rememberListenPosition(playback)
    val timeStyle = MaterialTheme.typography.labelLarge.withTabularFigures()
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(ListenRowGap),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    playback.togglePlay()
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
                    playback.skipNext()
                },
                enabled = playback.hasNext,
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
            // Yoin's tabletop pair: 展开歌词 beside the play-mode key, one small group (a cast
            // button would join them here).
            if (onExpandLyrics != null) {
                val expandContainer by animateColorAsState(
                    if (lyricsExpanded) scheme.primary else scheme.tertiaryContainer,
                    YoinMotion.defaultEffectsSpec(),
                    label = "listenExpand",
                )
                val expandContent by animateColorAsState(
                    if (lyricsExpanded) scheme.onPrimary else scheme.onTertiaryContainer,
                    YoinMotion.defaultEffectsSpec(),
                    label = "listenExpandContent",
                )
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onExpandLyrics()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = expandContainer, contentColor = expandContent),
                    modifier = Modifier.size(controlSize),
                ) {
                    Icon(
                        if (lyricsExpanded) YoinSymbols.UnfoldLess else YoinSymbols.UnfoldMore,
                        contentDescription = if (lyricsExpanded) "收起歌词" else "展开歌词",
                        modifier = Modifier.size(iconSize),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            ListenPlayModeButton(playback, controlSize, iconSize)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    playback.skipPrevious()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.secondary,
                    contentColor = scheme.onSecondary,
                ),
                modifier = Modifier.size(controlSize),
            ) {
                Icon(YoinSymbols.SkipPreviousFilled, contentDescription = playback.previousLabel, modifier = Modifier.size(iconSize))
            }
            Text(
                clock(live.value),
                style = timeStyle,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.defaultMinSize(minWidth = 40.dp),
            )
            ListenWaveBar(
                progress = { if (d > 0) live.value.toFloat() / d else 0f },
                buffered = if (d > 0) playback.bufferedMs.toFloat() / d else 0f,
                durationMs = d,
                playing = playing,
                onSeek = { f -> playback.seekTo((f * d).toLong()) },
                modifier = Modifier.weight(1f).height(controlSize),
            )
            Text(
                "-" + clock((d - live.value).coerceAtLeast(0L)),
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

/** Bilibili's controller behind the listen transport. */
@Stable
internal class BiliListenPlayback(private val ctrl: PiliPlayerController) : ListenPlayback {
    override val playing: Boolean get() = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    /** A Mix playing through (no 收藏夹 queue on the page): 下一个 and the mode are the Mix's. */
    private val mix: Boolean get() = ctrl.queue == null && com.gpo.pilipala.feature.sources.MixPlayback.runs(ctrl.target?.bvid?.let { "bili:$it" })
    override val hasNext: Boolean get() = mix || ctrl.hasNext
    override val durationMs: Long get() = ctrl.durationMs
    override val bufferedMs: Long get() = ctrl.bufferedMs
    override fun positionMs(): Long = ctrl.exactPositionMs()
    override fun togglePlay() = ctrl.togglePlay()
    override fun skipNext() = if (mix) com.gpo.pilipala.feature.sources.MixPlayback.next() else ctrl.skipNext()
    override fun skipPrevious() = ctrl.skipPrevious()
    override fun seekTo(ms: Long) = ctrl.seekTo(ms)

    @Composable
    override fun mode(): ListenMode {
        val queue = ctrl.queue
        if (queue != null) {
            val mode by queue.mode.collectAsState()
            return ListenMode(mode.symbol, mode.label, lit = mode != PlayMode.ListLoop)
        }
        if (mix) {
            val mode = com.gpo.pilipala.feature.sources.MixPlayback.mode
            return ListenMode(mode.symbol, mode.label, lit = mode != PlayMode.ListLoop)
        }
        val loop = ctrl.loop
        return ListenMode(if (loop) SymbolPlayMode.RepeatOne else SymbolPlayMode.RepeatAll, if (loop) "单曲循环" else "不循环", lit = loop)
    }

    override fun stepMode() {
        val queue = ctrl.queue
        val mixes = com.gpo.pilipala.feature.sources.MixPlayback
        when {
            queue != null -> queue.cycleMode()
            mix -> mixes.changeMode(mixes.mode.next())
            else -> ctrl.changeLoop(!ctrl.loop)
        }
    }
}

/**
 * Yoin's play-mode key: a circle showing the mode's symbol, resting on tertiaryContainer in
 * 列表循环 and lit (primary) otherwise; a tap steps the mode.
 */
@Composable
private fun ListenPlayModeButton(playback: ListenPlayback, size: Dp, iconSize: Dp) {
    val haptics = LocalHapticFeedback.current
    val mode = playback.mode()
    val symbol = mode.symbol
    val label = mode.label
    val lit = mode.lit
    val onStep: () -> Unit = playback::stepMode
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
