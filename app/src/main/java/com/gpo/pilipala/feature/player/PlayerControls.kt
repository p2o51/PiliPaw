package com.gpo.pilipala.feature.player

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transient gesture feedback shown in the middle / top of the player. */
@Stable
internal class GestureFeedback {
    var seekTargetMs by mutableStateOf<Long?>(null)
    var seekDeltaMs by mutableStateOf(0L)
    var level by mutableStateOf<Pair<ImageVector, Float>?>(null)
}

private fun clock(ms: Long): String = Fmt.durationMs(ms.coerceAtLeast(0L))

/**
 * Everything drawn over the picture: gestures, auto-hiding chrome (top bar,
 * centre play/pause, bottom bar), gesture feedback and the settings sheet.
 */
@Composable
internal fun PlayerOverlay(
    ctrl: PiliPlayerController,
    title: String,
    fullscreen: Boolean,
    topInset: Dp,
    canPip: Boolean,
    activity: Activity?,
    sheetScheme: ColorScheme,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onPip: () -> Unit,
    onControlsVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<SheetPage?>(null) }
    var composing by remember { mutableStateOf(false) }
    val canSend = rememberCanSendDanmaku(ctrl.target)
    val feedback = remember { GestureFeedback() }
    val volume = remember { VolumeControl(context) }
    val danmakuOn by AppSettings.danmakuEnabled.state.collectAsState()
    val latestFullscreen by rememberUpdatedState(fullscreen)

    val chromeVisible = visible && !locked
    LaunchedEffect(chromeVisible) { onControlsVisibleChange(chromeVisible) }
    LaunchedEffect(visible, locked, ctrl.isPlaying, poke, sheet, composing, feedback.seekTargetMs) {
        if (visible && ctrl.isPlaying && sheet == null && !composing && feedback.seekTargetMs == null) {
            delay(if (locked) 1800 else 3800)
            visible = false
        }
    }
    LaunchedEffect(fullscreen) { if (!fullscreen) locked = false }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = {
                        visible = !visible
                        poke++
                    },
                    onDoubleTap = {
                        if (!locked) {
                            haptics.performHapticFeedback(
                                if (ctrl.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            ctrl.togglePlay()
                        }
                    },
                    onLongPress = {
                        if (!locked && ctrl.isPlaying) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ctrl.beginBoost()
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (ctrl.boosting) {
                            ctrl.endBoost()
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var mode = 0 // 1 seek, 2 volume, 3 brightness
                var startX = 0f
                var seekBase = 0L
                var seekTarget = 0L
                var level = 0f
                var lastStep = 0
                val edge = 40.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = if (latestFullscreen && (offset.y < edge || offset.y > size.height - edge)) -1 else 0
                        startX = offset.x
                        seekBase = ctrl.exactPositionMs()
                        seekTarget = seekBase
                    },
                    onDragEnd = {
                        if (mode == 1 && ctrl.durationMs > 0) {
                            ctrl.seekTo(seekTarget)
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                        mode = 0
                        feedback.seekTargetMs = null
                        feedback.level = null
                    },
                    onDragCancel = {
                        mode = 0
                        feedback.seekTargetMs = null
                        feedback.level = null
                    },
                    onDrag = { change, drag ->
                        if (mode == -1) return@detectDragGestures
                        change.consume()
                        if (mode == 0) {
                            mode = when {
                                abs(drag.x) > abs(drag.y) -> if (ctrl.durationMs > 0) 1 else -1
                                startX > size.width / 2f -> 2
                                else -> 3
                            }
                            when (mode) {
                                2 -> {
                                    level = volume.fraction()
                                    lastStep = volume.index
                                }
                                3 -> {
                                    level = BrightnessControl.current(activity)
                                    lastStep = (level * 10).roundToInt()
                                }
                            }
                        }
                        when (mode) {
                            1 -> {
                                val scale = 90_000f / size.width
                                seekTarget = (seekTarget + (drag.x * scale).toLong()).coerceIn(0L, ctrl.durationMs)
                                feedback.seekTargetMs = seekTarget
                                feedback.seekDeltaMs = seekTarget - seekBase
                            }
                            2 -> {
                                level = (level - drag.y / (size.height * 0.85f)).coerceIn(0f, 1f)
                                val step = volume.set(level)
                                if (step != lastStep) {
                                    lastStep = step
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                feedback.level = (if (level <= 0.001f) YoinSymbols.VolumeMute else YoinSymbols.VolumeUp) to level
                            }
                            3 -> {
                                level = (level - drag.y / (size.height * 0.85f)).coerceIn(0.01f, 1f)
                                BrightnessControl.set(activity, level)
                                val step = (level * 10).roundToInt()
                                if (step != lastStep) {
                                    lastStep = step
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                feedback.level = PiliSymbols.Brightness to level
                            }
                        }
                    },
                )
            },
    ) {
        // Scrims + bars.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = fadeOut(YoinMotion.defaultEffectsSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(topInset + if (fullscreen) 112.dp else 80.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(if (fullscreen) 140.dp else 84.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
                )
                TopBar(
                    title = title,
                    fullscreen = fullscreen,
                    danmakuOn = danmakuOn,
                    canPip = canPip,
                    onBack = { if (fullscreen) onFullscreenChange(false) else onBack() },
                    onDanmaku = {
                        haptics.performHapticFeedback(if (danmakuOn) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        AppSettings.set(AppSettings.danmakuEnabled, !danmakuOn)
                        poke++
                    },
                    onPip = onPip,
                    onSend = if (canSend && !fullscreen) {
                        { composing = true }
                    } else {
                        null
                    },
                    onMore = { sheet = SheetPage.Main },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topInset)
                        .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
                )
                BottomBar(
                    ctrl = ctrl,
                    fullscreen = fullscreen,
                    feedback = feedback,
                    onPoke = { poke++ },
                    onQuality = { sheet = SheetPage.Quality },
                    onSpeed = { sheet = SheetPage.Speed },
                    onSend = if (canSend) {
                        { composing = true }
                    } else {
                        null
                    },
                    onFullscreen = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onFullscreenChange(!fullscreen)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
                )
            }
        }

        // Lock (fullscreen only).
        AnimatedVisibility(
            visible = fullscreen && visible,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.7f),
            exit = fadeOut(YoinMotion.defaultEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(start = 16.dp),
        ) {
            FilledIconButton(
                onClick = {
                    locked = !locked
                    haptics.performHapticFeedback(if (locked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    poke++
                },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    contentColor = if (locked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(PiliSymbols.Lock, contentDescription = if (locked) "解锁" else "锁定")
            }
        }

        // Centre: buffering indicator or the big play/pause.
        val showBuffering = ctrl.isBuffering && ctrl.firstFrameRendered
        val centreState = when {
            showBuffering -> 1
            chromeVisible && ctrl.phase is PlayerPhase.Ready -> 2
            else -> 0
        }
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = centreState,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f)) togetherWith
                    (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f))
            },
            modifier = Modifier.align(Alignment.Center),
            label = "playerCentre",
        ) { s ->
            when (s) {
                1 -> ContainedLoadingIndicator(Modifier.size(if (fullscreen) 72.dp else 56.dp))
                2 -> {
                    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            ctrl.togglePlay()
                            poke++
                        },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(
                            if (fullscreen) IconButtonDefaults.largeContainerSize() else IconButtonDefaults.mediumContainerSize(),
                        ),
                    ) {
                        Icon(
                            painter = rememberPlayPauseSymbolPainter(playing),
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(if (fullscreen) 40.dp else 28.dp),
                        )
                    }
                }
                else -> Spacer(Modifier.size(1.dp))
            }
        }

        // Gesture feedback.
        GestureFeedbackLayer(ctrl, feedback)
    }

    sheet?.let { start ->
        MaterialTheme(colorScheme = sheetScheme) {
            PlayerSheet(ctrl = ctrl, start = start, onDismiss = { sheet = null })
        }
    }
    if (composing) {
        MaterialTheme(colorScheme = sheetScheme) {
            DanmakuComposerSheet(ctrl = ctrl, onDismiss = { composing = false })
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    fullscreen: Boolean,
    danmakuOn: Boolean,
    canPip: Boolean,
    onBack: () -> Unit,
    onDanmaku: () -> Unit,
    onPip: () -> Unit,
    onSend: (() -> Unit)?,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(if (fullscreen) 64.dp else 52.dp)
            .padding(horizontal = if (fullscreen) 12.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Back, contentDescription = "返回")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = if (fullscreen) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(
            visible = onSend != null,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.6f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.6f),
        ) {
            IconButton(onClick = { onSend?.invoke() }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Edit, contentDescription = "发弹幕")
            }
        }
        IconButton(onClick = onDanmaku, shapes = IconButtonDefaults.shapes()) {
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = danmakuOn,
                transitionSpec = {
                    (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                        fadeOut(motion.fastEffectsSpec())
                },
                label = "danmakuToggle",
            ) { on ->
                Icon(
                    if (on) PiliSymbols.Danmaku else PiliSymbols.DanmakuOff,
                    contentDescription = if (on) "关闭弹幕" else "开启弹幕",
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (canPip) {
            IconButton(onClick = onPip, shapes = IconButtonDefaults.shapes()) {
                Icon(PiliSymbols.Pip, contentDescription = "画中画")
            }
        }
        IconButton(onClick = onMore, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
        }
    }
}

@Composable
private fun BottomBar(
    ctrl: PiliPlayerController,
    fullscreen: Boolean,
    feedback: GestureFeedback,
    onPoke: () -> Unit,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
    onSend: (() -> Unit)?,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    val timeStyle = MaterialTheme.typography.labelMedium.withTabularFigures()
    val shown = feedback.seekTargetMs ?: ctrl.positionMs
    val playButton: @Composable () -> Unit = {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                ctrl.togglePlay()
                onPoke()
            },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(rememberPlayPauseSymbolPainter(playing), contentDescription = if (playing) "暂停" else "播放")
        }
    }
    val qualityButton: @Composable () -> Unit = {
        if (ctrl.info != null) {
            PillButton(text = Qualities.videoShort(ctrl.currentQn), onClick = onQuality)
        }
    }
    val fullscreenButton: @Composable () -> Unit = {
        IconButton(onClick = onFullscreen, shapes = IconButtonDefaults.shapes()) {
            Icon(
                if (fullscreen) PiliSymbols.FullscreenExit else PiliSymbols.Fullscreen,
                contentDescription = if (fullscreen) "退出全屏" else "全屏",
            )
        }
    }
    if (!fullscreen) {
        Row(
            modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(clock(shown), style = timeStyle, color = MaterialTheme.colorScheme.onSurface)
            SeekBar(ctrl, feedback, onPoke, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(clock(ctrl.durationMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            qualityButton()
            fullscreenButton()
        }
    } else {
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(clock(shown), style = timeStyle, color = MaterialTheme.colorScheme.onSurface)
                SeekBar(ctrl, feedback, onPoke, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(clock(ctrl.durationMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                playButton()
                if (onSend != null) {
                    Spacer(Modifier.width(4.dp))
                    DanmakuSendPill(onClick = onSend)
                }
                Spacer(Modifier.weight(1f))
                PillButton(text = if (ctrl.speed == 1f) "倍速" else speedLabel(ctrl.speed), onClick = onSpeed)
                qualityButton()
                fullscreenButton()
            }
        }
    }
}

/** Compact text pill (quality / speed) on the control scrim. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "pillText",
        ) { t ->
            Text(
                t,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Seek slider: M3 Expressive [Slider] whose track is a wavy progress line
 * (flowing while playing, flat when paused) over a buffered band.
 */
@Composable
private fun SeekBar(
    ctrl: PiliPlayerController,
    feedback: GestureFeedback,
    onPoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = ctrl.durationMs
    val fraction = scrub ?: if (duration > 0) (ctrl.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (ctrl.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val flowing = ctrl.isPlaying && scrub == null
    val amplitude by animateFloatAsState(if (flowing) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "waveAmp")
    val strokePx = with(LocalDensity.current) { 4.dp.toPx() }
    val stroke = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round) }
    Slider(
        value = fraction,
        onValueChange = {
            scrub = it
            if (duration > 0) {
                val target = (it * duration).toLong()
                feedback.seekTargetMs = target
                feedback.seekDeltaMs = target - ctrl.positionMs
            }
            onPoke()
        },
        onValueChangeFinished = {
            scrub?.let { if (duration > 0) ctrl.seekTo((it * duration).toLong()) }
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            scrub = null
            feedback.seekTargetMs = null
        },
        enabled = duration > 0,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = SliderDefaults.colors(thumbColor = accent),
                thumbSize = DpSize(4.dp, 22.dp),
            )
        },
        track = { state ->
            Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val y = size.height / 2f
                    drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                    if (buffered > 0f) {
                        drawLine(bufferedColor, Offset(0f, y), Offset(size.width * buffered, y), size.height, StrokeCap.Round)
                    }
                }
                LinearWavyProgressIndicator(
                    progress = { state.value },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    color = accent,
                    trackColor = Color.Transparent,
                    stroke = stroke,
                    trackStroke = stroke,
                    gapSize = 0.dp,
                    stopSize = 0.dp,
                    amplitude = { amplitude * 0.45f },
                    wavelength = 22.dp,
                    waveSpeed = if (flowing) 16.dp else 0.dp,
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun GestureFeedbackLayer(ctrl: PiliPlayerController, feedback: GestureFeedback) {
    Box(Modifier.fillMaxSize()) {
        // Seek preview bubble.
        AnimatedVisibility(
            visible = feedback.seekTargetMs != null,
            enter = fadeIn(YoinMotion.fastEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.85f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val target = feedback.seekTargetMs ?: 0L
            val delta = feedback.seekDeltaMs
            Surface(
                shape = YoinShapeTokens.ExtraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(clock(target), style = MaterialTheme.typography.headlineSmall.withTabularFigures())
                        Spacer(Modifier.width(8.dp))
                        Text(
                            clock(ctrl.durationMs),
                            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    Text(
                        (if (delta >= 0) "+" else "-") + "${abs(delta) / 1000} 秒",
                        style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Volume / brightness level.
        AnimatedVisibility(
            visible = feedback.level != null,
            enter = fadeIn(YoinMotion.fastEffectsSpec()) + slideInVertically(YoinMotion.fastSpatialSpec()) { -it / 2 },
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + slideOutVertically(YoinMotion.fastSpatialSpec()) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            val (icon, value) = feedback.level ?: (YoinSymbols.VolumeUp to 0f)
            Surface(
                shape = YoinShapeTokens.Full,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    LinearWavyProgressIndicator(
                        progress = { value },
                        modifier = Modifier.width(120.dp),
                        amplitude = { 0f },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(value * 100).roundToInt()}",
                        style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                }
            }
        }

        // 3x boost chip.
        AnimatedVisibility(
            visible = ctrl.boosting,
            enter = fadeIn(YoinMotion.fastEffectsSpec()) + slideInVertically(YoinMotion.fastSpatialSpec()) { -it / 2 },
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + slideOutVertically(YoinMotion.fastSpatialSpec()) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Surface(
                shape = YoinShapeTokens.Full,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(YoinSymbols.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("3 倍速", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Compact retry inside the player area. */
@Composable
internal fun PlayerErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                YoinSymbols.CloudOffline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(onClick = onRetry) {
                Icon(PiliSymbols.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重试")
            }
        }
    }
}
