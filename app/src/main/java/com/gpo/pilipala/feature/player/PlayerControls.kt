package com.gpo.pilipala.feature.player

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastForEach
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
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

    /** Minimal chrome: the play/pause glyph flashed after a tap (bumped per tap). */
    var flash by mutableIntStateOf(0)
    var flashPlaying by mutableStateOf(false)

    /** Hold to speed up: the detents of the current hold and which one plays (-1: no hold). */
    var holdRates by mutableStateOf<List<Float>>(emptyList())
    var holdIndex by mutableIntStateOf(-1)
}

/**
 * The player's chrome state, hoisted to [BiliVideoPlayer] because the controls now take
 * real space: inline the strip under the picture grows the player, in fullscreen the
 * picture shrinks into a card while the bars show.
 */
@Stable
internal class PlayerChromeState {
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var poke by mutableIntStateOf(0)
    var sheet by mutableStateOf<SheetPage?>(null)
    var composing by mutableStateOf(false)
    val feedback = GestureFeedback()
    val scrub = SeekScrub()

    /** The player frame, for placing the seek bubble over the picture. */
    var frame: LayoutCoordinates? = null

    fun poke() {
        poke++
    }
}

internal fun clock(ms: Long): String = Fmt.durationMs(ms.coerceAtLeast(0L))

/**
 * Icons and text drawn straight over the picture or on the black frame around it. They
 * are plain white whatever the cover tint or the app theme (a scrim colour, like the
 * scrims themselves). Accents (the centre button, toggles) keep the dark chrome scheme's
 * primary, which is a light tone.
 */
internal val OnVideo = Color.White
internal val OnVideoVariant = Color.White.copy(alpha = 0.8f)

/**
 * What the transport controls draw with. Over the picture and on the fullscreen bands it
 * is [VideoInk] (plain white). The inline strip sits on the page itself, under the
 * picture, so it takes the page's own ink ([pageInk]) and reads as part of the page.
 */
@Immutable
internal data class PlayerInk(
    val content: Color,
    val contentVariant: Color,
    val pill: Color,
    val pillContent: Color,
    val trackRest: Color,
    val trackBuffered: Color,
    val trackPlayed: Color,
)

internal val VideoInk = PlayerInk(
    content = OnVideo,
    contentVariant = OnVideo.copy(alpha = 0.56f),
    pill = OnVideo.copy(alpha = 0.16f),
    pillContent = OnVideo,
    trackRest = Color.White.copy(alpha = 0.18f),
    trackBuffered = Color.White.copy(alpha = 0.35f),
    trackPlayed = Color.White,
)

internal fun pageInk(scheme: ColorScheme) = PlayerInk(
    content = scheme.onSurface,
    contentVariant = scheme.onSurfaceVariant,
    pill = scheme.secondaryContainer,
    pillContent = scheme.onSecondaryContainer,
    trackRest = scheme.onSurface.copy(alpha = 0.1f),
    trackBuffered = scheme.onSurface.copy(alpha = 0.2f),
    trackPlayed = scheme.primary,
)

internal val LocalPlayerInk = staticCompositionLocalOf { VideoInk }

/** Translucent dark plate behind the small corner buttons over the picture. */
private val CornerPlate = Color.Black.copy(alpha = 0.5f)

/** A soft shadow under on-video text, for the frames where it sits over the picture. */
private fun TextStyle.onVideo(): TextStyle = copy(shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 8f))

/** Auto-hide: the controls fold away a few seconds after the last touch while playing. */
@Composable
internal fun ChromeAutoHide(ctrl: PiliPlayerController, ui: PlayerChromeState, fullscreen: Boolean) {
    LaunchedEffect(ui.visible, ui.locked, ctrl.isPlaying, ui.poke, ui.sheet, ui.composing, ui.feedback.seekTargetMs, ui.scrub.fraction) {
        if (ui.visible && ctrl.isPlaying && ui.sheet == null && !ui.composing &&
            ui.feedback.seekTargetMs == null && ui.scrub.fraction == null
        ) {
            delay(if (ui.locked) 1800 else 3800)
            ui.visible = false
        }
    }
    // The end, a failure: bring the controls back (replay, retry, next).
    LaunchedEffect(ctrl.ended, ctrl.phase is PlayerPhase.Error) {
        if (ctrl.ended || ctrl.phase is PlayerPhase.Error) ui.visible = true
    }
    LaunchedEffect(fullscreen) {
        if (!fullscreen) ui.locked = false
        // The sheet is a side panel in fullscreen and a bottom sheet inline: never carry one across.
        ui.sheet = null
    }
}

/**
 * Taps and drags on the picture. [minimal]: tap plays / pauses (with a brief centre
 * glyph), double tap goes to [onDoubleTap], only horizontal drags are taken (seek), so a
 * vertical pager around the player keeps its swipes. Otherwise tap toggles the controls,
 * double tap plays / pauses, horizontal drag seeks and vertical drags set volume (right
 * half) or brightness (left half). In both, holding still on a playing picture speeds it
 * up and sliding while held sets the speed ([holdSpeed]).
 *
 * Arbitration. Two detectors on the same node: the tap detector (outer) only does tap and
 * double tap; the press detector (inner, so it sees each event first) decides what every
 * press is, by whichever comes first:
 * - the finger lifts: a tap; the press detector lets it through untouched;
 * - it moves past the touch slop: a drag (seek, or volume / brightness by the first
 *   direction and the side); the slop move is consumed, which cancels the tap;
 * - it stays put for the long-press timeout: on a playing, unlocked picture that is a
 *   hold, which owns the press until release (vertical travel steps the speed, sideways
 *   travel does nothing, every event is consumed so it never ends as a tap or a seek).
 *   Paused, a long press is nothing: a later move still drags, a release is swallowed.
 *   Locked, it is swallowed whole.
 * So a press settles into exactly one of tap, drag or hold, and a drag can never begin
 * mid-hold. A tap followed by a hold toggles the controls once, then holds.
 */
@Composable
internal fun Modifier.playerGestures(
    ctrl: PiliPlayerController,
    ui: PlayerChromeState,
    activity: Activity?,
    fullscreen: Boolean,
    minimal: Boolean,
    onDoubleTap: ((Offset) -> Unit)?,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val volume = remember { VolumeControl(context) }
    val feedback = ui.feedback
    val latestFullscreen by rememberUpdatedState(fullscreen)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val locked = ui.locked
    val hasDoubleTap = onDoubleTap != null
    val togglePlay: () -> Unit = {
        haptics.performHapticFeedback(if (ctrl.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        ctrl.togglePlay()
    }
    return this
        .pointerInput(locked, minimal, hasDoubleTap) {
            detectTapGestures(
                onTap = {
                    if (minimal) {
                        feedback.flashPlaying = !(ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended))
                        togglePlay()
                        feedback.flash++
                    } else {
                        ui.visible = !ui.visible
                        ui.poke()
                    }
                },
                onDoubleTap = when {
                    minimal && hasDoubleTap -> { offset -> latestDoubleTap?.invoke(offset) }
                    minimal -> null
                    else -> { _ -> if (!locked) togglePlay() }
                },
            )
        }
        .pointerInput(locked, minimal) {
            val slop = viewConfiguration.touchSlop
            val holdTimeout = viewConfiguration.longPressTimeoutMillis
            val step = HoldSpeedStep.toPx()
            val edge = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val early = withTimeoutOrNull(holdTimeout) {
                    awaitPress(down.id, slop, drags = !locked, horizontalOnly = minimal)
                }
                val press: Press = when {
                    early != null -> early
                    // Held still: a playing picture speeds up until the finger lifts.
                    !locked && ctrl.isPlaying -> {
                        holdSpeed(down.id, step, ctrl, feedback, haptics)
                        return@awaitEachGesture
                    }
                    locked -> {
                        consumeUntilRelease(down.id)
                        return@awaitEachGesture
                    }
                    else -> awaitPress(down.id, slop, drags = true, horizontalOnly = minimal).also {
                        // A long press that did nothing does not end as a tap.
                        if (it == Press.Released) currentEvent.changes.fastForEach { c -> c.consume() }
                    }
                }
                if (press !is Press.Moved) return@awaitEachGesture

                // ── Drag.
                val seekBase = ctrl.exactPositionMs()
                var seekTarget = seekBase
                // 1 seek, 2 volume, 3 brightness, -1 ignored. The minimal chrome only seeks;
                // in fullscreen a drag from the very top / bottom edge is the system's.
                var mode = when {
                    minimal -> 1
                    latestFullscreen && (down.position.y < edge || down.position.y > size.height - edge) -> -1
                    else -> 0
                }
                val startX = down.position.x
                var level = 0f
                var lastStep = 0
                val onDrag: (PointerInputChange, Offset) -> Unit = onDrag@{ change, drag ->
                    if (mode == -1) return@onDrag
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
                        if (mode == -1) return@onDrag
                    }
                    if (mode == 1 && ctrl.durationMs <= 0) return@onDrag
                    change.consume()
                    when (mode) {
                        1 -> {
                            val scale = 90_000f / size.width
                            seekTarget = (seekTarget + (drag.x * scale).toLong()).coerceIn(0L, ctrl.durationMs)
                            feedback.seekTargetMs = seekTarget
                            feedback.seekDeltaMs = seekTarget - seekBase
                        }
                        2 -> {
                            level = (level - drag.y / (size.height * 0.85f)).coerceIn(0f, 1f)
                            val applied = volume.set(level)
                            if (applied != lastStep) {
                                lastStep = applied
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                            feedback.level = (if (level <= 0.001f) YoinSymbols.VolumeMute else YoinSymbols.VolumeUp) to level
                        }
                        3 -> {
                            level = (level - drag.y / (size.height * 0.85f)).coerceIn(0.01f, 1f)
                            BrightnessControl.set(activity, level)
                            val applied = (level * 10).roundToInt()
                            if (applied != lastStep) {
                                lastStep = applied
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                            feedback.level = PiliSymbols.Brightness to level
                        }
                    }
                }
                var ended = false
                try {
                    onDrag(press.change, press.overSlop)
                    ended = drag(press.change.id) { c -> onDrag(c, if (minimal) Offset(c.positionChange().x, 0f) else c.positionChange()) }
                } finally {
                    if (ended && mode == 1 && ctrl.durationMs > 0 && feedback.seekTargetMs != null) {
                        ctrl.seekTo(seekTarget)
                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    }
                    feedback.seekTargetMs = null
                    feedback.level = null
                }
            }
        }
}

/** What the controls do; one set of callbacks for the inline and the fullscreen layout. */
internal class ChromeActions(
    val onBack: () -> Unit,
    val onFullscreen: () -> Unit,
    val onPip: () -> Unit,
    val onDanmaku: () -> Unit,
    val onSend: (() -> Unit)?,
    val onMore: () -> Unit,
    val onQuality: () -> Unit,
    val onSpeed: () -> Unit,
    val onSubtitles: () -> Unit,
    val onNext: () -> Unit,
    val onPlay: () -> Unit,
)

@Composable
internal fun rememberChromeActions(
    ctrl: PiliPlayerController,
    ui: PlayerChromeState,
    fullscreen: Boolean,
    turned: Boolean,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onPip: () -> Unit,
): ChromeActions {
    val haptics = LocalHapticFeedback.current
    val danmakuOn by AppSettings.danmakuEnabled.state.collectAsState()
    val canSend = rememberCanSendDanmaku(ctrl.target)
    return ChromeActions(
        onBack = { if (fullscreen) onFullscreenChange(false) else onBack() },
        onFullscreen = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onFullscreenChange(!fullscreen)
        },
        onPip = onPip,
        onDanmaku = {
            haptics.performHapticFeedback(if (danmakuOn) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            AppSettings.set(AppSettings.danmakuEnabled, !danmakuOn)
            ui.poke()
        },
        // Typing needs the IME, which does not turn with the picture.
        onSend = if (canSend && !turned) {
            { ui.composing = true }
        } else {
            null
        },
        onMore = { ui.sheet = SheetPage.Main },
        onQuality = { ui.sheet = SheetPage.Quality },
        onSpeed = { ui.sheet = SheetPage.Speed },
        onSubtitles = { ui.sheet = SheetPage.Subtitle },
        onNext = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            ctrl.skipNext()
            ui.poke()
        },
        onPlay = {
            val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
            haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            ctrl.togglePlay()
            ui.poke()
        },
    )
}

/** A small round button on a translucent dark plate, for the picture's corners. */
@Composable
private fun CornerButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = OnVideo) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = CornerPlate, contentColor = tint),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DanmakuToggleIcon(on: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = on,
        transitionSpec = {
            (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                fadeOut(motion.fastEffectsSpec())
        },
        label = "danmakuToggle",
    ) { o ->
        Icon(
            if (o) PiliSymbols.Danmaku else PiliSymbols.DanmakuOff,
            contentDescription = if (o) "关闭弹幕" else "开启弹幕",
            tint = if (o) OnVideo else OnVideoVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Inline, over the picture: only small round buttons in the top corners (back; send /
 * danmaku / PiP / more), fading with the controls. No full-width scrim.
 */
@Composable
internal fun InlineCorners(
    actions: ChromeActions,
    canPip: Boolean,
    topInset: Dp,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
) {
    val danmakuOn by AppSettings.danmakuEnabled.state.collectAsState()
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = topInset)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .graphicsLayer {
                val p = reveal().coerceIn(0f, 1f)
                alpha = p
                translationY = (1f - p) * -8.dp.toPx()
            },
    ) {
        CornerButton(YoinSymbols.Back, "返回", actions.onBack)
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .background(CornerPlate, YoinShapeTokens.Full),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val send = actions.onSend
            if (send != null) {
                InlineGroupButton(onClick = send) { Icon(YoinSymbols.Edit, contentDescription = "发弹幕", modifier = Modifier.size(22.dp)) }
            }
            InlineGroupButton(onClick = actions.onDanmaku) { DanmakuToggleIcon(danmakuOn) }
            if (canPip) {
                InlineGroupButton(onClick = actions.onPip) { Icon(PiliSymbols.Pip, contentDescription = "画中画", modifier = Modifier.size(22.dp)) }
            }
            InlineGroupButton(onClick = actions.onMore) { Icon(YoinSymbols.MoreVertical, contentDescription = "更多", modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun InlineGroupButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = OnVideo),
        modifier = Modifier.size(40.dp),
    ) { content() }
}

/** Playhead and duration as two separate labels (no joiner), tabular so they never jitter. */
@Composable
private fun TimeLabels(ctrl: PiliPlayerController, ui: PlayerChromeState, big: Boolean, modifier: Modifier = Modifier) {
    val style = (if (big) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium).withTabularFigures()
    val d = ctrl.durationMs
    val shown = ui.scrub.fraction?.let { (it * d).toLong() } ?: ui.feedback.seekTargetMs ?: ctrl.positionMs
    val ink = LocalPlayerInk.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(clock(shown), style = style, color = ink.content)
        Spacer(Modifier.width(8.dp))
        Text(clock(d), style = style, color = ink.contentVariant)
    }
}

@Composable
private fun PlayPauseButton(ctrl: PiliPlayerController, onClick: () -> Unit, size: Dp = 44.dp) {
    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalPlayerInk.current.content),
        modifier = Modifier.size(size),
    ) {
        Icon(rememberPlayPauseSymbolPainter(playing), contentDescription = if (playing) "暂停" else "播放")
    }
}

@Composable
private fun NextButton(ctrl: PiliPlayerController, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = ctrl.hasNext,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.6f),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.6f),
    ) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = LocalPlayerInk.current.content),
            modifier = Modifier.size(44.dp),
        ) {
            Icon(YoinSymbols.SkipNextFilled, contentDescription = "下一个")
        }
    }
}

@Composable
private fun FullscreenButton(fullscreen: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalPlayerInk.current.content),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            if (fullscreen) PiliSymbols.FullscreenExit else PiliSymbols.Fullscreen,
            contentDescription = if (fullscreen) "退出全屏" else "全屏",
        )
    }
}

@Composable
private fun SubtitleButton(ctrl: PiliPlayerController, onClick: () -> Unit) {
    if (ctrl.subtitles.isEmpty()) return
    // A subtitle showing: a quiet white block behind the glyph.
    val on = ctrl.subtitleIndex >= 0
    val block by animateColorAsState(
        if (on) OnVideo.copy(alpha = 0.2f) else Color.Transparent,
        YoinMotion.defaultEffectsSpec(),
        label = "subtitleBlock",
    )
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = block, contentColor = OnVideo),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(PiliSymbols.Subtitles, contentDescription = "字幕")
    }
}

/**
 * Inline control strip under the picture: the seek bar along its top edge, then play /
 * next / time on the left and speed / quality / fullscreen on the right. It takes real
 * layout space (the player grows by its height), so nothing covers the picture. It has
 * no plate of its own: it sits on the page in the page's [ink], so the page simply
 * continues up to the picture's edge.
 */
@Composable
internal fun InlineStrip(
    ctrl: PiliPlayerController,
    ui: PlayerChromeState,
    actions: ChromeActions,
    running: Boolean,
    ink: PlayerInk,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides ink.content, LocalPlayerInk provides ink) {
        Column(modifier.fillMaxWidth()) {
            PlayerSeekBar(
                ctrl = ctrl,
                scrub = ui.scrub,
                onPoke = ui::poke,
                frame = { ui.frame },
                running = running,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val roomy = maxWidth >= 400.dp
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayPauseButton(ctrl, actions.onPlay)
                    NextButton(ctrl, actions.onNext)
                    TimeLabels(ctrl, ui, big = false, modifier = Modifier.padding(start = 6.dp))
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (roomy) {
                            PillButton(text = if (ctrl.speed == 1f) "倍速" else speedLabel(ctrl.speed), onClick = actions.onSpeed)
                        }
                        if (ctrl.info != null) {
                            PillButton(text = Qualities.videoShort(ctrl.currentQn), onClick = actions.onQuality)
                        }
                    }
                    FullscreenButton(fullscreen = false, onClick = actions.onFullscreen)
                }
            }
        }
    }
}

/**
 * Fullscreen top band: back, title and the toggles, on the black band the shrunken
 * picture leaves above itself. [reveal] drives it together with the picture.
 */
@Composable
internal fun FullscreenTopBar(
    title: String,
    actions: ChromeActions,
    canPip: Boolean,
    turned: Boolean,
    height: Dp,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
) {
    val danmakuOn by AppSettings.danmakuEnabled.state.collectAsState()
    val cutout = if (turned) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout)
    CompositionLocalProvider(LocalContentColor provides OnVideo) {
        Row(
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = reveal().coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * -14.dp.toPx()
                }
                .then(cutout)
                .height(height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Back, contentDescription = "返回")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.onVideo(),
                color = OnVideo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val send = actions.onSend
            if (send != null) {
                IconButton(onClick = send, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Edit, contentDescription = "发弹幕")
                }
            }
            IconButton(onClick = actions.onDanmaku, shapes = IconButtonDefaults.shapes()) {
                DanmakuToggleIcon(danmakuOn)
            }
            if (canPip) {
                IconButton(onClick = actions.onPip, shapes = IconButtonDefaults.shapes()) {
                    Icon(PiliSymbols.Pip, contentDescription = "画中画")
                }
            }
            IconButton(onClick = actions.onMore, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
            }
        }
    }
}

/** Fullscreen bottom band: one row, the seek bar taking the middle. */
@Composable
internal fun FullscreenBottomBar(
    ctrl: PiliPlayerController,
    ui: PlayerChromeState,
    actions: ChromeActions,
    turned: Boolean,
    height: Dp,
    running: Boolean,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
) {
    val cutout = if (turned) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout)
    CompositionLocalProvider(LocalContentColor provides OnVideo) {
        Row(
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = reveal().coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * 14.dp.toPx()
                }
                .then(cutout)
                .height(height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(ctrl, actions.onPlay, size = 48.dp)
            NextButton(ctrl, actions.onNext)
            TimeLabels(ctrl, ui, big = true, modifier = Modifier.padding(start = 6.dp, end = 12.dp))
            PlayerSeekBar(
                ctrl = ctrl,
                scrub = ui.scrub,
                onPoke = ui::poke,
                frame = { ui.frame },
                running = running,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            SubtitleButton(ctrl, actions.onSubtitles)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(text = if (ctrl.speed == 1f) "倍速" else speedLabel(ctrl.speed), onClick = actions.onSpeed)
                if (ctrl.info != null) {
                    PillButton(text = Qualities.videoShort(ctrl.currentQn), onClick = actions.onQuality)
                }
            }
            FullscreenButton(fullscreen = true, onClick = actions.onFullscreen)
        }
    }
}

/** Fullscreen lock, at the start edge. */
@Composable
internal fun LockButton(ui: PlayerChromeState, visible: Boolean, turned: Boolean, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.7f),
        exit = fadeOut(YoinMotion.defaultEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.7f),
        modifier = modifier
            .then(if (turned) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout))
            .padding(start = 16.dp),
    ) {
        val locked = ui.locked
        val lockContainer by animateColorAsState(
            if (locked) MaterialTheme.colorScheme.primary else CornerPlate,
            YoinMotion.defaultEffectsSpec(),
            label = "lockContainer",
        )
        val lockContent by animateColorAsState(
            if (locked) MaterialTheme.colorScheme.onPrimary else OnVideo,
            YoinMotion.defaultEffectsSpec(),
            label = "lockContent",
        )
        FilledIconButton(
            onClick = {
                ui.locked = !ui.locked
                haptics.performHapticFeedback(if (ui.locked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                ui.poke()
            },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = lockContainer, contentColor = lockContent),
        ) {
            Icon(PiliSymbols.Lock, contentDescription = if (locked) "解锁" else "锁定")
        }
    }
}

/**
 * The centre: the loading indicator while buffering, and a big play button while paused
 * with the controls up. While playing nothing sits on the picture.
 */
@Composable
internal fun CentreButton(ctrl: PiliPlayerController, ui: PlayerChromeState, shown: Boolean, big: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    // While a hold lasts its pill sits here and shows the buffering itself.
    val showBuffering = ctrl.isBuffering && ctrl.firstFrameRendered && ui.feedback.holdIndex < 0
    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    val centreState = when {
        showBuffering -> 1
        shown && !ui.locked && ctrl.phase is PlayerPhase.Ready && !playing -> 2
        else -> 0
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = centreState,
        transitionSpec = {
            // No clipping size transform: the default one clips the button to a box that
            // grows from the top-start corner, which read as a square mask cutting it off.
            ((fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f)) togetherWith
                (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        label = "playerCentre",
    ) { s ->
        when (s) {
            1 -> ContainedLoadingIndicator(Modifier.size(if (big) 72.dp else 56.dp))
            2 -> FilledIconButton(
                onClick = onPlay,
                shapes = IconButtonDefaults.shapes(),
                // Solid light container (the dark chrome scheme's primary) with a dark glyph.
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.size(if (big) IconButtonDefaults.largeContainerSize() else IconButtonDefaults.mediumContainerSize()),
            ) {
                Icon(
                    painter = rememberPlayPauseSymbolPainter(false),
                    contentDescription = "播放",
                    modifier = Modifier.size(if (big) 40.dp else 28.dp),
                )
            }
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

/** Minimal chrome: the play / pause glyph that blinks in the centre after a tap. */
@Composable
internal fun TapFlash(feedback: GestureFeedback, modifier: Modifier = Modifier) {
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(feedback.flash) {
        if (feedback.flash == 0) return@LaunchedEffect
        showing = true
        delay(650)
        showing = false
    }
    AnimatedVisibility(
        visible = showing,
        enter = fadeIn(YoinMotion.fastEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), initialScale = 0.6f),
        exit = fadeOut(YoinMotion.defaultEffectsSpec()) + scaleOut(YoinMotion.defaultSpatialSpec(), targetScale = 1.2f),
        modifier = modifier,
    ) {
        Box(
            Modifier.size(72.dp).background(CornerPlate, YoinShapeTokens.Full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPlayPauseSymbolPainter(feedback.flashPlaying),
                contentDescription = null,
                tint = OnVideo,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** Compact text pill (quality / speed). */
@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = YoinShapeTokens.Full,
        color = LocalPlayerInk.current.pill,
        contentColor = LocalPlayerInk.current.pillContent,
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
 * Gesture feedback: the drag-seek bubble (with the preview frame when the video has
 * sprites), the volume / brightness level and the hold-to-speed pill.
 */
@Composable
internal fun GestureFeedbackLayer(ctrl: PiliPlayerController, feedback: GestureFeedback, big: Boolean) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = feedback.seekTargetMs != null,
            enter = fadeIn(YoinMotion.fastEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(YoinMotionRole.Expressive), initialScale = 0.85f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val last = remember { longArrayOf(0L, 0L) }
            feedback.seekTargetMs?.let {
                last[0] = it
                last[1] = feedback.seekDeltaMs
            }
            val target = last[0]
            val delta = last[1]
            val shots = ctrl.shots
            val chapter = ctrl.chapters.at(target)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (shots != null) {
                    SeekThumbnail(shots = shots, timeMs = target, width = if (big) 220.dp else 160.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    Modifier
                        .background(Color.Black.copy(alpha = 0.62f), YoinShapeTokens.ExtraLarge)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (chapter != null && chapter.title.isNotEmpty()) {
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = OnVideoVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(clock(target), style = MaterialTheme.typography.headlineSmall.withTabularFigures(), color = OnVideo)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            clock(ctrl.durationMs),
                            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                            color = OnVideo.copy(alpha = 0.56f),
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

        // Hold to speed up: the speed pill in the middle while the finger is down.
        HoldSpeedPill(ctrl, feedback, big = big, modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * Settings and the danmaku composer: a bottom sheet inline; in fullscreen a side panel
 * inside the picture frame, so it turns with a turned frame and no dialog window brings
 * the system bars back.
 */
@Composable
internal fun PlayerSheets(ctrl: PiliPlayerController, ui: PlayerChromeState, fullscreen: Boolean, sheetScheme: ColorScheme) {
    if (fullscreen) {
        PlayerSidePanel(page = ui.sheet, onDismiss = { ui.sheet = null }) { start, close ->
            PlayerSheetPages(ctrl = ctrl, start = start, close = close)
        }
    } else {
        ui.sheet?.let { start ->
            MaterialTheme(colorScheme = sheetScheme) {
                PlayerSheet(ctrl = ctrl, start = start, onDismiss = { ui.sheet = null })
            }
        }
    }
    if (ui.composing) {
        MaterialTheme(colorScheme = sheetScheme) {
            DanmakuComposerSheet(ctrl = ctrl, onDismiss = { ui.composing = false })
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
