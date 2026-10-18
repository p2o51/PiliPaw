package com.gpo.pilipala.feature.player

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/**
 * How much UI the player draws over the picture. CONTRACT:
 * - [Full]: the standard controls (top bar, seek bar, buttons, settings, gestures).
 * - [Minimal]: picture + danmaku + subtitles + gestures only (tap = play/pause, long-press =
 *   fast speed, horizontal drag = seek, double-tap reported to the host). The host draws its
 *   own overlay (the immersive portrait mode) and reads progress from the [BiliPlayerState].
 */
enum class PlayerChrome { Full, Minimal }

/**
 * What to play. UGC: bvid/aid + cid. PGC (bangumi): epId + cid with isPgc = true.
 */
data class PlayTarget(
    val bvid: String = "",
    val aid: Long = 0,
    val cid: Long,
    val epId: Long = 0,
    val seasonId: Long = 0,
    val isPgc: Boolean = false,
    val title: String = "",
    val cover: String = "",
    val startPositionMs: Long = 0,
)

/**
 * Hoisted player handle shared with the detail page (seek from a reply
 * timestamp, pause when leaving, read progress). CONTRACT — the player agent
 * may add members but must keep these.
 */
@Stable
open class BiliPlayerState {
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    /** UP / series name for the media notification (title and cover come from [PlayTarget]). */
    var artist by mutableStateOf("")
    open fun play() {}
    open fun pause() {}
    open fun seekTo(ms: Long) {}
    open fun release() {}
}

/**
 * Returns the real ExoPlayer-backed state ([PiliPlayerController]). It owns the
 * player for as long as the caller's composition remembers it, so moving the
 * [BiliVideoPlayer] between layouts (inline ↔ fullscreen, compact ↔ two-pane)
 * keeps playback going; it is released when forgotten.
 */
@Composable
fun rememberBiliPlayerState(): BiliPlayerState {
    val context = LocalContext.current.applicationContext
    return remember { PiliPlayerController(context) }
}

/**
 * The video surface + controls + danmaku. CONTRACT — signature is fixed:
 * - [target] null while the page is still resolving cid → show cover/loader.
 * - [fullscreen] is owned by the caller; the player calls [onFullscreenChange]
 *   from its button/gesture and applies orientation + system-bar changes itself.
 * - [onCompleted] fires at the end (auto-play-next lives in the detail page).
 *
 * Entering Picture-in-Picture also asks the caller for fullscreen (so the page
 * shows only the picture inside the PiP window) and hands it back on exit.
 *
 * Sizing: in fullscreen (and with [PlayerChrome.Minimal]) the player fills [modifier].
 * Inline with [PlayerChrome.Full] it owns its height: a 16:9 picture plus the control
 * strip that springs out under it while the controls show, so give it a width
 * (`fillMaxWidth()`), not a fixed 16:9 box.
 *
 * [onDoubleTap]: [PlayerChrome.Minimal] only, the double tap's position (the host's
 * like burst); null keeps single taps immediate.
 */
@Composable
fun BiliVideoPlayer(
    target: PlayTarget?,
    state: BiliPlayerState,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCompleted: () -> Unit = {},
    chrome: PlayerChrome = PlayerChrome.Full,
    onDoubleTap: ((Offset) -> Unit)? = null,
) {
    val context = LocalContext.current
    val ctrl: PiliPlayerController = (state as? PiliPlayerController) ?: run {
        val appContext = context.applicationContext
        remember(state) { PiliPlayerController(appContext).also { it.mirror = state } }
    }
    val latestCompleted by rememberUpdatedState(onCompleted)
    val latestFullscreenChange by rememberUpdatedState(onFullscreenChange)
    val latestFullscreen by rememberUpdatedState(fullscreen)
    SideEffect { ctrl.onCompleted = { latestCompleted() } }
    LaunchedEffect(target) { target?.let { ctrl.load(it) } }

    val activity = LocalActivity.current as? ComponentActivity
    val inPip = rememberIsInPip(activity)
    val latestInPip by rememberUpdatedState(inPip)
    val canPip = remember(context) { pipSupported(context) }

    // ── Lifecycle: pause on leave (unless background play), drop video in background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ctrl) {
        var resumeOnReturn = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val inPipNow = activity?.isInPictureInPictureMode == true
                    if (!AppSettings.backgroundPlay.value && !inPipNow && ctrl.isPlaying) {
                        ctrl.pause()
                        resumeOnReturn = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> if (resumeOnReturn) {
                    resumeOnReturn = false
                    ctrl.play()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (latestInPip) {
                        // The PiP window was dismissed.
                        ctrl.pause()
                    } else if (AppSettings.backgroundPlay.value) {
                        ctrl.setBackground(true)
                    }
                }
                Lifecycle.Event.ON_START -> ctrl.setBackground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KeepScreenOnEffect(ctrl.isPlaying)
    DisposableEffect(activity) { onDispose { BrightnessControl.reset(activity) } }

    // ── PiP: force fullscreen layout while in the PiP window; auto-enter if enabled.
    var wasInPip by remember { mutableStateOf(inPip) }
    LaunchedEffect(inPip) {
        if (inPip == wasInPip) return@LaunchedEffect
        wasInPip = inPip
        if (inPip) {
            if (!latestFullscreen) {
                ctrl.pipForcedFullscreen = true
                latestFullscreenChange(true)
            }
        } else {
            if (ctrl.pipForcedFullscreen) {
                ctrl.pipForcedFullscreen = false
                latestFullscreenChange(false)
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) ctrl.pause()
        }
    }
    val autoPip by AppSettings.autoPip.state.collectAsState()
    if (canPip && activity != null) {
        val aspect = ctrl.videoAspect
        val playing = ctrl.isPlaying
        LaunchedEffect(autoPip, playing, aspect) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity.setPictureInPictureParams(pipParams(aspect, autoPip && playing)) }
            }
        }
        DisposableEffect(activity) {
            val leaveHint = Runnable {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && AppSettings.autoPip.value && ctrl.isPlaying) {
                    enterPip(activity, ctrl.videoAspect)
                }
            }
            activity.addOnUserLeaveHintListener(leaveHint)
            onDispose {
                activity.removeOnUserLeaveHintListener(leaveHint)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { activity.setPictureInPictureParams(pipParams(16f / 9f, false)) }
                }
            }
        }
    }

    // ── Fullscreen: system bars + orientation; back exits; rotate-to-fullscreen on phones.
    val portraitVideo = ctrl.videoAspect < 1f
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val phone = configuration.smallestScreenWidthDp < 600
    // Phones turn the display. Large screens try a real rotation first and turn the picture
    // itself when the platform ignores or letterboxes the request (rememberLargeScreenFullscreen).
    FullscreenWindowEffect(
        activity = activity,
        fullscreen = fullscreen,
        lockLandscape = phone && fullscreen && !portraitVideo && !inPip && !ctrl.pipForcedFullscreen && !ctrl.fullscreenByRotation,
    )
    val windowPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val multiWindow = remember(configuration) { activity?.isInMultiWindowMode == true }
    val large = rememberLargeScreenFullscreen(
        activity = activity,
        want = !phone && fullscreen && !portraitVideo && !inPip && !ctrl.pipForcedFullscreen && !multiWindow && !ctrl.fullscreenByRotation,
    )
    val turned = large == LargeFullscreen.Turn && windowPortrait
    val turn = rememberLandscapeTurn(turned)
    val turnAngle = remember { Animatable(0f) }
    val turnTarget = if (turned) turn else 0f
    val turnSpec = YoinMotion.defaultSpatialSpec<Float>()
    val lastWindowPortrait = remember { booleanArrayOf(windowPortrait) }
    LaunchedEffect(turnTarget, windowPortrait) {
        // The display itself turned (auto-rotate): the picture is already where the user sees
        // it, so jump; a fullscreen tap or the device turning the other way springs.
        val displayTurned = lastWindowPortrait[0] != windowPortrait
        lastWindowPortrait[0] = windowPortrait
        if (displayTurned) turnAngle.snapTo(turnTarget) else turnAngle.animateTo(turnTarget, turnSpec)
    }
    // A SurfaceView cannot be rotated, scaled or clipped (its surface is composited outside
    // the window). The turned picture (and its spring back) and the fullscreen picture, which
    // shrinks into a rounded card while the controls show, draw through a TextureView.
    val turning by remember { derivedStateOf { turnAngle.value != 0f } }
    val full = chrome == PlayerChrome.Full
    val cardFrame = fullscreen && full
    val surfaceType = if (turned || turning || cardFrame) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW

    BackHandler(enabled = fullscreen && !inPip) { onFullscreenChange(false) }
    LaunchedEffect(fullscreen) { if (!fullscreen) ctrl.fullscreenByRotation = false }
    val autoFs by AppSettings.autoFullscreenLandscape.state.collectAsState()
    var lastLandscape by remember { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape != lastLandscape && phone && autoFs && !latestInPip && !ctrl.pipForcedFullscreen) {
            if (landscape && !latestFullscreen && ctrl.videoAspect >= 1f && target != null) {
                ctrl.fullscreenByRotation = true
                latestFullscreenChange(true)
            } else if (!landscape && latestFullscreen && ctrl.fullscreenByRotation) {
                ctrl.fullscreenByRotation = false
                latestFullscreenChange(false)
            }
        }
        lastLandscape = landscape
    }

    // ── Layout.
    val density = LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(density)
    var yInWindow by remember { mutableFloatStateOf(0f) }
    val topInset = if (fullscreen || inPip) {
        0.dp
    } else {
        with(density) { (statusTopPx - yInWindow).coerceIn(0f, statusTopPx.toFloat()).toDp() }
    }
    val pageScheme = MaterialTheme.colorScheme
    // The page is still flying in (container transform): a SurfaceView would punch a square
    // hole through its rounded corners, so only the cover shows until it lands.
    val settled = LocalPageSettled.current

    // With auto-play the page opens on a clean picture (the strip would only fold away
    // again seconds later, moving the page); otherwise the controls offer play at once.
    val ui = remember(ctrl) { PlayerChromeState().apply { visible = !AppSettings.autoPlay.value } }
    ChromeAutoHide(ctrl, ui, fullscreen)
    val controls = full && !inPip
    val inline = controls && !fullscreen
    val fill = !inline
    val shown = controls && ui.visible && !ui.locked
    // One spring drives the strip under the picture, the bars and the fullscreen picture card.
    val revealState = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(YoinMotionRole.Expressive),
        label = "chromeReveal",
    )
    val reveal: () -> Float = remember(revealState) { { revealState.value } }
    val hairline: () -> Float = remember(revealState) { { 1f - revealState.value.coerceIn(0f, 1f) } }
    val revealing by remember(revealState) { derivedStateOf { revealState.value > 0.004f } }
    // Fullscreen bands the shrunken picture leaves free (shorter on a phone held sideways).
    val frameHeightDp = if (turned) configuration.screenWidthDp else configuration.screenHeightDp
    val shortFrame = frameHeightDp < 480
    val topBand = if (shortFrame) 48.dp else 56.dp
    val bottomBand = if (shortFrame) 56.dp else 64.dp

    val phase = ctrl.phase
    val showCover = target == null || !settled || (!ctrl.firstFrameRendered && phase !is PlayerPhase.Error)

    Box(
        modifier
            .background(Color.Black)
            .clipToBounds()
            .onGloballyPositioned { yInWindow = it.positionInWindow().y },
    ) {
        PlayerChromeTheme {
            val overlayScheme = MaterialTheme.colorScheme
            val actions = rememberChromeActions(
                ctrl = ctrl,
                ui = ui,
                fullscreen = fullscreen,
                turned = turned,
                onBack = onBack,
                onFullscreenChange = { fs ->
                    if (!fs) ctrl.fullscreenByRotation = false
                    onFullscreenChange(fs)
                },
                onPip = { enterPip(activity, ctrl.videoAspect) },
            )
            PlayerFrame(
                fill = fill,
                modifier = Modifier
                    .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .frameTurn(turned, fill) { turnAngle.value }
                    .onPlaced { ui.frame = it },
                picture = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (cardFrame) {
                                    Modifier.pictureCard(
                                        reveal = reveal,
                                        aspect = { ctrl.videoAspect },
                                        topBand = topBand,
                                        bottomBand = bottomBand,
                                        minScale = if (shortFrame) 0.76f else 0.8f,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        if (settled) {
                            // The video view is rebuilt whenever its frame changes size (inline ⇄
                            // fullscreen, window resize). On some devices a resized SurfaceView keeps its
                            // old size and the picture sits small in a corner of the fullscreen frame; a
                            // new view is laid out at the right size from its first frame.
                            key(surfaceType, fullscreen, configuration.screenWidthDp, configuration.screenHeightDp) {
                                ContentFrame(
                                    player = ctrl.player,
                                    modifier = Modifier.fillMaxSize(),
                                    surfaceType = surfaceType,
                                    contentScale = ContentScale.Fit,
                                    shutter = {},
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = showCover,
                            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                            exit = fadeOut(YoinMotion.slowEffectsSpec()),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            CoverLoading(ctrl = ctrl, cover = target?.cover.orEmpty(), big = fullscreen)
                        }
                        if (!inPip) {
                            DanmakuLayer(ctrl = ctrl, big = fullscreen)
                        }
                        // Nothing covers the picture any more, so subtitles stay put.
                        SubtitleOverlay(ctrl = ctrl, controlsVisible = false, big = fullscreen)
                        if (controls) {
                            // The hairline sits on the picture's own bottom edge (inside any letterbox).
                            val aspect = ctrl.videoAspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
                            Box(
                                if (fill) Modifier.align(Alignment.Center).aspectRatio(aspect) else Modifier.fillMaxSize(),
                            ) {
                                ProgressHairline(ctrl, alpha = hairline, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                },
                gestures = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (inPip) {
                                    Modifier
                                } else {
                                    Modifier.playerGestures(
                                        ctrl = ctrl,
                                        ui = ui,
                                        activity = activity,
                                        fullscreen = fullscreen,
                                        minimal = !full,
                                        onDoubleTap = onDoubleTap,
                                    )
                                },
                            ),
                    )
                },
                strip = {
                    if (controls && revealing) {
                        if (inline) {
                            InlineStrip(
                                ctrl = ctrl,
                                ui = ui,
                                actions = actions,
                                running = true,
                                // Slides out from under the picture, taking real space as it grows.
                                modifier = Modifier
                                    .clipToBounds()
                                    .layout { m, c ->
                                        val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                        val h = (p.height * reveal().coerceIn(0f, 1f)).roundToInt()
                                        layout(p.width, h) { p.place(0, h - p.height) }
                                    },
                            )
                        } else {
                            FullscreenBottomBar(
                                ctrl = ctrl,
                                ui = ui,
                                actions = actions,
                                turned = turned,
                                height = bottomBand,
                                running = true,
                                reveal = reveal,
                            )
                        }
                    }
                },
                overlay = {
                    Box(Modifier.fillMaxSize()) {
                        if (controls) {
                            if (revealing) {
                                if (inline) {
                                    InlineCorners(
                                        actions = actions,
                                        canPip = canPip && activity != null,
                                        topInset = topInset,
                                        reveal = reveal,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                } else {
                                    FullscreenTopBar(
                                        title = target?.title.orEmpty(),
                                        actions = actions,
                                        canPip = canPip && activity != null,
                                        turned = turned,
                                        height = topBand,
                                        reveal = reveal,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                            }
                            if (fullscreen) {
                                LockButton(ui, visible = ui.visible, turned = turned, modifier = Modifier.align(Alignment.CenterStart))
                            }
                            CentreButton(
                                ctrl = ctrl,
                                ui = ui,
                                shown = shown,
                                big = fullscreen,
                                onPlay = actions.onPlay,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else if (!inPip) {
                            TapFlash(ui.feedback, Modifier.align(Alignment.Center))
                            CentreButton(ctrl = ctrl, ui = ui, shown = false, big = fullscreen, onPlay = actions.onPlay, modifier = Modifier.align(Alignment.Center))
                        }
                        if (!inPip) GestureFeedbackLayer(ctrl, ui.feedback, big = fullscreen)

                        val error = phase as? PlayerPhase.Error
                        AnimatedVisibility(
                            visible = error != null && !inPip,
                            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                            exit = fadeOut(YoinMotion.defaultEffectsSpec()),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                PlayerErrorPanel(
                                    message = error?.message ?: "",
                                    onRetry = { ctrl.retry() },
                                )
                                if (full) {
                                    IconButton(
                                        onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                                        shapes = IconButtonDefaults.shapes(),
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = OnVideo),
                                        modifier = Modifier.padding(top = topInset).padding(4.dp),
                                    ) {
                                        Icon(YoinSymbols.Back, contentDescription = "返回")
                                    }
                                }
                            }
                        }
                        if (controls) {
                            PlayerSheets(ctrl, ui, fullscreen, sheetScheme = if (fullscreen) overlayScheme else pageScheme)
                        }
                    }
                },
                floating = {
                    if (controls) SeekBubbleLayer(ctrl, ui.scrub, big = fullscreen)
                },
            )
        }
    }
}

/**
 * The player's layers. [fill]: everything takes the whole frame and the strip (the
 * fullscreen bottom bar) sits on its bottom edge. Otherwise the picture, gestures and
 * overlay take a 16:9 box and the strip goes under it, growing the frame by its height.
 * The floating layer (the seek bubble) covers the whole frame on top. Draw / touch order:
 * picture, gestures, strip, overlay, floating.
 */
@Composable
private fun PlayerFrame(
    fill: Boolean,
    modifier: Modifier,
    picture: @Composable () -> Unit,
    gestures: @Composable () -> Unit,
    strip: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
    floating: @Composable () -> Unit,
) {
    Layout(contents = listOf(picture, gestures, strip, overlay, floating), modifier = modifier) { slots, c ->
        val pic = slots[0]
        val ges = slots[1]
        val st = slots[2]
        val ov = slots[3]
        val fl = slots[4]
        val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
        if (fill) {
            val h = if (c.hasBoundedHeight) c.maxHeight else (w * 9f / 16f).roundToInt()
            val fixed = Constraints.fixed(w, h)
            val picP = pic.map { it.measure(fixed) }
            val gesP = ges.map { it.measure(fixed) }
            val stP = st.map { it.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h)) }
            val ovP = ov.map { it.measure(fixed) }
            val flP = fl.map { it.measure(fixed) }
            layout(w, h) {
                picP.forEach { it.place(0, 0) }
                gesP.forEach { it.place(0, 0) }
                stP.forEach { it.place(0, h - it.height) }
                ovP.forEach { it.place(0, 0) }
                flP.forEach { it.place(0, 0) }
            }
        } else {
            val ph = (w * 9f / 16f).roundToInt()
            val fixed = Constraints.fixed(w, ph)
            val picP = pic.map { it.measure(fixed) }
            val gesP = ges.map { it.measure(fixed) }
            val stP = st.map { it.measure(Constraints(minWidth = w, maxWidth = w)) }
            val total = ph + (stP.maxOfOrNull { it.height } ?: 0)
            val ovP = ov.map { it.measure(fixed) }
            val flP = fl.map { it.measure(Constraints.fixed(w, total)) }
            layout(w, total) {
                picP.forEach { it.place(0, 0) }
                gesP.forEach { it.place(0, 0) }
                stP.forEach { it.place(0, ph) }
                ovP.forEach { it.place(0, 0) }
                flP.forEach { it.place(0, 0) }
            }
        }
    }
}

/**
 * [fill]: the large-screen turned frame ([turnedContent]). Inline: the frame keeps its own
 * size and only draws rotated while the turn springs back from fullscreen.
 */
private fun Modifier.frameTurn(turned: Boolean, fill: Boolean, angle: () -> Float): Modifier =
    if (fill) {
        turnedContent(turned, angle)
    } else {
        layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            layout(p.width, p.height) {
                p.placeWithLayer(0, 0) {
                    val a = angle()
                    if (a != 0f) {
                        rotationZ = a
                        val rad = Math.toRadians(a.toDouble())
                        val c = abs(cos(rad)).toFloat()
                        val sn = abs(sin(rad)).toFloat()
                        val bw = p.width * c + p.height * sn
                        val bh = p.width * sn + p.height * c
                        val fit = if (bw > 0f && bh > 0f) minOf(1f, p.width / bw, p.height / bh) else 1f
                        scaleX = fit
                        scaleY = fit
                    }
                }
            }
        }
    }

/**
 * Fullscreen picture card: while the controls show ([reveal] → 1) the picture shrinks
 * (keeping its aspect) into the band between the top and bottom bars, lifts to that
 * band's centre and rounds its corners; hidden, it is the plain full-size picture. The
 * clip morphs from the whole frame to the picture's own rect, so the letterbox (black on
 * black) never shows as a card edge.
 */
private fun Modifier.pictureCard(
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
    val top = topBand.toPx()
    val bottom = bottomBand.toPx()
    val gap = 6.dp.toPx()
    val fit = ((h - top - bottom - gap * 2f) / ph).coerceIn(minScale, 1f)
    val s = 1f - (1f - fit) * p
    scaleX = s
    scaleY = s
    translationY = (top - bottom) / 2f * p
    val pc = p.coerceIn(0f, 1f)
    val insetX = (w - pw) / 2f * pc
    val insetY = (h - ph) / 2f * pc
    shape = PictureCardShape(
        Rect(insetX, insetY, w - insetX, h - insetY),
        CardRadius.toPx() * pc / s.coerceAtLeast(0.1f),
    )
    clip = true
}

private val CardRadius = 20.dp

private class PictureCardShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius, radius)))
}

/** Cover art + loader until the first frame (the overlay owns the play button). */
@Composable
private fun CoverLoading(ctrl: PiliPlayerController, cover: String, big: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (cover.isNotBlank()) {
            NetImage(
                url = cover,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                thumbWidth = 720.dp,
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        val waitingForUser = ctrl.phase is PlayerPhase.Ready && !ctrl.playWhenReady && !ctrl.isBuffering
        // 仅听声音 (play queue): the cover stays up while only the sound plays.
        val listening = ctrl.audioOnly && ctrl.phase is PlayerPhase.Ready && !ctrl.isBuffering
        val loading = !waitingForUser && !listening && ctrl.phase !is PlayerPhase.Error
        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.defaultSpatialSpec(), initialScale = 0.8f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            ContainedLoadingIndicator(Modifier.size(if (big) 72.dp else 56.dp))
        }
    }
}

/**
 * Controls sit on a dark scrim over video, so they always use a dark scheme
 * derived from the page's (cover-tinted) primary: its light primary carries the
 * accents (centre button, seek progress) and its surfaces the fullscreen panel,
 * while icons and text over the picture are plain white ([OnVideo]).
 */
@Composable
private fun PlayerChromeTheme(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalPiliDark.current
    val seed = cs.primary.toArgb()
    val scheme = if (dark) cs else remember(seed) { ExpressiveColorSchemeFactory.fromSeed(seed, isDark = true) }
    MaterialTheme(
        colorScheme = scheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
