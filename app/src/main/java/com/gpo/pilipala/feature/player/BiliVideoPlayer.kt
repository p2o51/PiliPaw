package com.gpo.pilipala.feature.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gpo.pilipala.ui.components.rememberToast
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
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
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.gpo.pilipala.ui.theme.rememberRichHaptics
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Back on a page left paused: load where it was and stay paused, whatever 自动播放 says. */
    val startPaused: Boolean = false,
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

    /** Playing, or about to (buffering with play pressed): not paused by hand. */
    open val wantsToPlay: Boolean get() = isPlaying
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
fun rememberBiliPlayerState(adoptBvid: String = ""): BiliPlayerState {
    val context = LocalContext.current.applicationContext
    // Opened from a playing 单列 card: its preview carries on as this page's player (FeedPreview.kt).
    return remember {
        // Back from the mini bar: the kept player carries on as this page's (KeepPlaying.kt).
        com.gpo.pilipala.feature.sources.BiliKeeper.take(adoptBvid)
            ?: PreviewHandoff.take(adoptBvid)?.also { it.adopt() }
            ?: PiliPlayerController(context)
    }.also { ctrl ->
        // Opening any video: the one docked in the mini bar (another video, or YouTube's) goes,
        // exactly once per player this page holds (PlayerHost.kt).
        remember(ctrl) { com.gpo.pilipala.feature.sources.PlayerHost.opened(com.gpo.pilipala.feature.sources.PlayerSlot.Bili(ctrl)) }
    }
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
    SubtitleAutoSelect(ctrl)

    val activity = LocalActivity.current as? ComponentActivity
    val inPip = rememberIsInPip(activity)
    val latestInPip by rememberUpdatedState(inPip)
    val canPip = remember(context) { pipSupported(context) }
    // On its way into PiP (Android 15+ says so as the flight starts; a home swipe with 自动
    // 画中画 on is taken as the start too): the controls and danmaku leave at once, so only
    // the clean picture is carried into the floating window.
    val pipEntering = PipSignals.entering && !inPip
    val rich = rememberRichHaptics()
    val pipScope = rememberCoroutineScope()
    // Where the picture sits in the window, for the PiP flight (see pipHintRect).
    var pipBox by remember { mutableStateOf<Rect?>(null) }
    val latestTarget by rememberUpdatedState(target)

    // ── Lifecycle: pause on leave (unless background play), drop video in background.
    // Navigation leaving the detail page is not the app entering the background.
    // Disabling/re-enabling the video track here recreates the decoder during docking.
    val lifecycleOwner = activity ?: LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ctrl) {
        var resumeOnReturn = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val inPipNow = activity?.isInPictureInPictureMode == true
                    // 仅听声音 is listening: it carries on in the background whatever 后台播放 says.
                    if (!AppSettings.backgroundPlay.value && !ctrl.audioOnly && !inPipNow && ctrl.isPlaying) {
                        ctrl.pause()
                        resumeOnReturn = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Back in the full window: any PiP flight has landed or was called off.
                    if (activity?.isInPictureInPictureMode != true) PipSignals.entering = false
                    if (resumeOnReturn) {
                        resumeOnReturn = false
                        ctrl.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (latestInPip) {
                        // The PiP window was dismissed.
                        ctrl.pause()
                    } else if (AppSettings.backgroundPlay.value || ctrl.audioOnly) {
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
        if (inPip) PipSignals.entering = false
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
        // The params ride along with the picture: aspect, auto-enter while playing, and the
        // picture's rect so the system flies exactly the video out of the page. Debounced, so
        // a page settling or the pager scrolling doesn't turn into a binder call per frame.
        LaunchedEffect(activity, autoPip) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
            snapshotFlow {
                PipSpec(
                    aspect = ctrl.videoAspect,
                    autoEnter = autoPip && ctrl.isPlaying,
                    hint = pipBox?.let { pipHintRect(it, ctrl.videoAspect) },
                    title = latestTarget?.title,
                    subtitle = state.artist,
                )
            }
                .distinctUntilChanged()
                .collectLatest { s ->
                    delay(PipParamsDebounceMs)
                    runCatching { activity.setPictureInPictureParams(pipParams(s.aspect, s.autoEnter, s.hint, s.title, s.subtitle)) }
                }
        }
        DisposableEffect(activity) {
            val leaveHint = Runnable {
                if (AppSettings.autoPip.value && ctrl.isPlaying && !latestInPip) {
                    PipSignals.entering = true
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        enterPip(activity, ctrl.videoAspect, pipBox?.let { pipHintRect(it, ctrl.videoAspect) }, latestTarget?.title, state.artist)
                    }
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
    // Fullscreen predictive back (FullscreenBack.kt): the picture follows the finger home,
    // then lands in its inline slot; the landing scales a TextureView too.
    val back = remember { FullscreenBackState() }
    // Into fullscreen in the same window (no turn of the display or the picture ahead): the
    // picture grows out of its inline slot, the back's landing run the other way. Decided as
    // the fullscreen layout composes, so its first frame already draws the picture in the slot.
    val enterSeen = remember { booleanArrayOf(fullscreen) }
    if (enterSeen[0] != fullscreen) {
        enterSeen[0] = fullscreen
        val sameWindow = !windowPortrait || portraitVideo
        if (fullscreen && full && !inPip && !ctrl.pipForcedFullscreen && !back.busy && sameWindow) {
            val root = LocalView.current.rootView
            back.beginEnter(IntSize(root.width, root.height), ctrl.videoAspect)
        }
    }
    val landing by remember { derivedStateOf { back.landing } }
    // 画面 (PictureZoom.kt). Anything but 适应 lays the video view out bigger than its box
    // (填充) or stretches it, which only a TextureView clips and scales cleanly.
    val fitSetting by PictureFitSetting.state.collectAsState()
    val pictureFit = pictureFitOf(fitSetting)
    // A player handed over from a card's preview draws through a TextureView from the start, so
    // its picture flies in with the page (a SurfaceView only after landing) and never swaps view.
    // Once a fullscreen exit has landed on a TextureView it stays one: swapping back to a
    // SurfaceView as the landing ended left the inline picture black (the new SurfaceView never
    // got its surface while the window settled, the decoder sat on a placeholder) and, after a
    // pause in fullscreen, frozen on a still while the sound played on.
    var landedOnTexture by remember { mutableStateOf(false) }
    if (landing && !landedOnTexture) landedOnTexture = true
    val surfaceType = if (ctrl.handedOver || turned || turning || cardFrame || landing || landedOnTexture || pictureFit != PictureFit.Fit) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW
    val view = LocalView.current
    val windowSize = remember(view) { { IntSize(view.rootView.width, view.rootView.height) } }
    // Entering or leaving PiP resizes the window, which rebuilds the video view (the key below),
    // blank until the decoder's next frame lands on it: the landed PiP window and the expanding
    // one would flash black. The picture, copied before its view goes, covers the new view until
    // its first frame and then fades into it, so the still doesn't cut to the moving picture.
    // It is copied at the size the new window shows it (the video's own pixels, so the small PiP
    // view still hands the full window a sharp one, but no more): a bigger copy lands too late.
    var pipBridge by remember { mutableStateOf<ImageBitmap?>(null) }
    // The video view's shutter is up: its surface has no frame yet.
    var surfaceCovered by remember { mutableStateOf(false) }
    val bridgeAlpha = remember { Animatable(0f) }
    val bridgeFade = YoinMotion.defaultEffectsSpec<Float>()
    val bridgeScope = rememberCoroutineScope()
    val copyBridge = remember(ctrl, view) {
        { windowWidthDp: Int ->
            findVideoView(view.rootView, null, ctrl.player)?.let { video ->
                val source = ctrl.player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
                val windowPx = windowWidthDp * view.resources.displayMetrics.density
                val scale = source?.let { minOf(1f, windowPx / it.width) } ?: 0f
                val w = source?.let { (it.width * scale).roundToInt() } ?: video.width
                val h = source?.let { (it.height * scale).roundToInt() } ?: video.height
                // Undispatched: the copy is requested now, while the old view still has its surface.
                bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    captureVideoFrame(video, w, h)?.asImageBitmap()?.let { pipBridge = it }
                }
            }
        }
    }
    DisposableEffect(activity, copyBridge) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { copyBridge(it.newConfig.screenWidthDp) }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    // Into PiP the copy at the switch can land after the new view's blank start (the window is
    // busy flying), so one is taken as the flight starts too, for the switch's copy to replace.
    LaunchedEffect(copyBridge) {
        snapshotFlow { PipSignals.entering && !latestInPip }.collect { if (it) copyBridge(configuration.screenWidthDp) }
    }
    LaunchedEffect(pipBridge) {
        pipBridge ?: return@LaunchedEffect
        // The switch can rebuild the view more than once (window size, then the layout): each
        // blank start shows the picture whole again, and the fade follows the last one.
        withTimeoutOrNull(PipBridgeMs) {
            snapshotFlow { surfaceCovered }.collectLatest { covered ->
                if (covered) bridgeAlpha.snapTo(1f) else bridgeAlpha.animateTo(0f, bridgeFade)
            }
        }
        bridgeAlpha.snapTo(0f)
        pipBridge = null
    }

    LaunchedEffect(fullscreen) { if (!fullscreen) ctrl.fullscreenByRotation = false }
    val enterSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Standard)
    LaunchedEffect(fullscreen) {
        if (fullscreen && back.enterPending) {
            // Grown out of the inline slot (see beginEnter above).
            back.settle.snapTo(1f)
            back.enterPending = false
            back.settle.animateTo(0f, enterSpec)
            back.release = null
        } else if (fullscreen && back.settle.value != 0f) {
            // Back into fullscreen mid-landing: the landing is over.
            back.settle.snapTo(0f)
            back.release = null
        }
    }
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
    // The handed-over preview keeps playing through the flight: no cover over it.
    val liveFlight = !settled && ctrl.handedOver

    // With auto-play the page opens on a clean picture (the strip would only fold away
    // again seconds later, moving the page); otherwise the controls offer play at once.
    val ui = remember(ctrl) { PlayerChromeState().apply { visible = !AppSettings.autoPlay.value } }
    ChromeAutoHide(ctrl, ui, fullscreen)
    val controls = full && !inPip
    val inline = controls && !fullscreen
    val fill = !inline
    // Nothing of the chrome is shown while the picture lands (the inline strip and corners are
    // not even composed, so the player keeps the picture's height under the landing).
    val shown = controls && ui.visible && !ui.locked && !pipEntering && !landing
    // Whether the controls were up when an animated exit let go (see the restore below).
    var chromeAfterLanding by remember { mutableStateOf(false) }

    // Back exits fullscreen. With the full chrome the picture follows the finger home, the
    // chrome fading with it, and the chrome stays hidden until the picture rests in its slot.
    FullscreenBackHandler(
        state = back,
        enabled = fullscreen && !inPip,
        animate = full && cardFrame,
        turnAngle = turnAngle,
        turnTarget = turnTarget,
        windowSize = windowSize,
        onExit = { animated ->
            if (animated) {
                chromeAfterLanding = ui.visible
                ui.visible = false
            }
            onFullscreenChange(false)
        },
    )
    // Raised by the commit; lowered once the reveal has had a frame to snap shut.
    LaunchedEffect(back.snapChrome) {
        if (!back.snapChrome) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        back.snapChrome = false
    }
    // The controls that were up when the finger let go come back once the picture rests, so
    // the strip unfolds under the landed picture rather than under the flying one. Only on a
    // picture that is not playing (ChromeAutoHide keeps them up there and nothing else would
    // bring them back); a playing one would fold them away again moments later.
    LaunchedEffect(chromeAfterLanding, landing, back.snapChrome) {
        if (!chromeAfterLanding || landing || back.snapChrome) return@LaunchedEffect
        chromeAfterLanding = false
        if (!ctrl.isPlaying) ui.visible = true
    }
    LaunchedEffect(pipEntering) {
        if (!pipEntering) return@LaunchedEffect
        ui.visible = false
        // A flight that never lands (PiP refused, gesture called off without a callback).
        delay(PipEnteringTimeoutMs)
        if (!latestInPip && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) PipSignals.entering = false
    }
    // One spring drives the strip under the picture, the bars and the fullscreen picture card.
    // Into PiP it snaps: the system is already flying the picture, the chrome must not ride along.
    val revealState = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (pipEntering || back.snapChrome) snap() else YoinMotion.defaultSpatialSpec(YoinMotionRole.Expressive),
        label = "chromeReveal",
    )
    val reveal: () -> Float = remember(revealState) { { revealState.value } }
    val hairline: () -> Float = remember(revealState) { { 1f - revealState.value.coerceIn(0f, 1f) } }
    val revealing by remember(revealState) { derivedStateOf { revealState.value > 0.004f && !back.landing } }
    // Fullscreen bands the shrunken picture leaves free (shorter on a phone held sideways).
    val frameHeightDp = if (turned) configuration.screenWidthDp else configuration.screenHeightDp
    val shortFrame = frameHeightDp < 480
    val topBand = if (shortFrame) 48.dp else 56.dp
    val bottomBand = if (shortFrame) 56.dp else 64.dp

    val phase = ctrl.phase
    // 仅听声音 shows the subtitles as lyrics where the picture was (not in the PiP window).
    val lyricsShown = ctrl.audioOnly && !inPip && !pipEntering && target != null
    val showCover = target == null || (!settled && !liveFlight) || (!ctrl.firstFrameRendered && phase !is PlayerPhase.Error)

    // ── Pinch zoom (fullscreen picture card only) and 截图.
    val zoom = remember { PictureZoom() }
    val zoomSpec = YoinMotion.defaultSpatialSpec<Float>()
    val zoomAllowed = cardFrame && !inPip && !pipEntering && !lyricsShown
    // Out of fullscreen, into PiP, under the lyrics: the plain picture at once, so the PiP
    // flight and the inline slot measure exactly what they show.
    LaunchedEffect(zoomAllowed) { if (!zoomAllowed) zoom.snapHome() }
    // The back gesture flies the plain picture home; another 画面 starts from it too.
    LaunchedEffect(zoom) {
        snapshotFlow { back.busy }.collect { busy -> if (busy) zoom.springHome(pipScope, zoomSpec) }
    }
    LaunchedEffect(pictureFit) { zoom.springHome(pipScope, zoomSpec) }
    val pinchAllowed by rememberUpdatedState(zoomAllowed && !ui.locked && !landing)
    val latestFit by rememberUpdatedState(pictureFit)
    val haptics = LocalHapticFeedback.current
    val toast = rememberToast()
    // 跳过片段: one quiet line with 撤销 after a skip (the bar closes the segment up); a
    // 提示我 segment offers 跳过 while the playhead is in it.
    val snackbar = com.gpo.pilipala.ui.components.LocalSnackbarHost.current
    val lastSkip = ctrl.lastSkip
    LaunchedEffect(lastSkip) {
        val s = lastSkip ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar("已跳过${s.kind.label}", actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) ctrl.undoSkip()
    }
    val askSkip = ctrl.askSkip
    LaunchedEffect(askSkip) {
        val s = askSkip ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar("${s.kind.label}片段", actionLabel = "跳过", duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) ctrl.acceptSkip()
    }
    val shot = remember { ShotFeedback() }
    // The picture's window rect, for finding the view the video is drawn into.
    val shotBox = remember { arrayOfNulls<Rect>(1) }
    val flashSpec = YoinMotion.slowEffectsSpec<Float>()
    val kickSpec = YoinMotion.fastSpatialSpec<Float>(YoinMotionRole.Expressive)
    val canShot = target != null && !ctrl.audioOnly && ctrl.firstFrameRendered && !inPip
    val takeShot: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        pipScope.launch {
            val video = findVideoView(view.rootView, shotBox[0])
            val size = ctrl.player?.videoSize
            val frame = video?.let { captureVideoFrame(it, size?.width ?: 0, size?.height ?: 0) }
            if (frame == null) {
                toast("截图失败")
                return@launch
            }
            launch {
                shot.flash.snapTo(0.6f)
                shot.flash.animateTo(0f, flashSpec)
            }
            launch {
                shot.kick.snapTo(0.96f)
                shot.kick.animateTo(1f, kickSpec)
            }
            val saved = runCatching { saveScreenshot(context, frame, latestTarget?.title.orEmpty()) }
            frame.recycle()
            toast(if (saved.isSuccess) "已保存截图" else "保存失败")
        }
    }

    // The inline strip sits on the page, in the page's own colours (captured before the
    // dark chrome theme takes over), so only the picture itself is black.
    val stripInk = pageInk(MaterialTheme.colorScheme)
    // The back pose's view of the frame, and the inline slot it flies home to.
    val minCardScale = if (shortFrame) 0.76f else 0.8f
    // The picture's shape as drawn: the video's own aspect when fitted, the box's when 填充 /
    // 拉伸 cover it (inline the box is 16:9), so the card and the back pose clip what shows.
    var fillBoxAspect by remember { mutableFloatStateOf(16f / 9f) }
    val pictureAspect: () -> Float = when {
        pictureFit == PictureFit.Fit -> { { ctrl.videoAspect } }
        fill -> { { fillBoxAspect } }
        else -> { { 16f / 9f } }
    }
    val frameSpec = BackFrameSpec(
        fill = fill,
        swapped = fill && turned,
        angle = { turnAngle.value },
        aspect = pictureAspect,
        reveal = if (cardFrame) reveal else null,
        topBand = topBand,
        bottomBand = bottomBand,
        minScale = minCardScale,
    )
    SideEffect {
        back.spec = frameSpec
        back.density = density
    }
    val pictureShape = LocalPlayerPictureShape.current
    val pictureRadius = remember(pictureShape, density) {
        (pictureShape as? CornerBasedShape)?.topStart?.toPx(Size(1600f, 900f), density) ?: 0f
    }
    // The controls fade with the finger while the picture flies home and stay invisible from
    // the release until it rests (the commit raises snapChrome before the progress drops to 0,
    // and settle is already at 1 by then, so no frame shows them back at full strength).
    val chromeAlpha: () -> Float = remember(back) {
        {
            val g = back.progress.value
            when {
                back.snapChrome || back.landing -> 0f
                g > 0f -> (1f - 3f * g).coerceIn(0f, 1f)
                else -> 1f
            }
        }
    }
    Box(
        modifier
            // Landing from where the finger let go, the picture flies over the page around it.
            .then(if (landing) Modifier.zIndex(1f) else Modifier.clipToBounds())
            .then(if (fill) Modifier.background(Color.Black) else Modifier)
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
                onPip = {
                    // Lift-off: drop the chrome (and the fullscreen card's inset) first, give that
                    // two frames to draw, then hand the clean picture's rect to the system.
                    rich.liftOff()
                    PipSignals.entering = true
                    pipScope.launch {
                        withFrameNanos { }
                        withFrameNanos { }
                        val ok = enterPip(activity, ctrl.videoAspect, pipBox?.let { pipHintRect(it, ctrl.videoAspect) }, latestTarget?.title, state.artist)
                        if (!ok) PipSignals.entering = false
                    }
                },
                onShot = if (canShot) takeShot else null,
            )
            PlayerFrame(
                fill = fill,
                chromeAlpha = chromeAlpha,
                modifier = Modifier
                    .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .fullscreenBackFrame(back, frameSpec)
                    .frameTurn(turned, fill) { turnAngle.value }
                    .fullscreenBackClip(back, frameSpec)
                    .onPlaced { ui.frame = it },
                picture = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // The picture's resting rect (before the card's shrink below), for the PiP
                            // flight. Not from inside the PiP window, and not while it is turned by hand.
                            .onGloballyPositioned { c ->
                                if (fill && c.size.width > 0 && c.size.height > 0) fillBoxAspect = c.size.width.toFloat() / c.size.height
                                shotBox[0] = c.boundsInWindow()
                                val resting = turnAngle.value == 0f && !back.busy
                                if (!latestInPip) pipBox = if (turned || !resting) null else c.boundsInWindow()
                                // The inline slot, for the fullscreen back gesture to fly home to.
                                if (inline && resting && !latestInPip) back.captureInline(c, pictureRadius, windowSize())
                            }
                            .then(
                                if (cardFrame) {
                                    Modifier.pictureCard(
                                        reveal = reveal,
                                        aspect = pictureAspect,
                                        topBand = topBand,
                                        bottomBand = bottomBand,
                                        minScale = minCardScale,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .then(if (fill) Modifier else Modifier.clip(LocalPlayerPictureShape.current).background(Color.Black)),
                    ) {
                        if (settled || liveFlight) {
                            // The video view is rebuilt whenever its frame changes size (inline ⇄
                            // fullscreen, window resize). On some devices a resized SurfaceView keeps its
                            // old size and the picture sits small in a corner of the fullscreen frame; a
                            // new view is laid out at the right size from its first frame. A TextureView
                            // resizes fine, so it is kept across the back gesture's landing (no blank frame
                            // as the picture flies into its slot).
                            val sizeKey = if (surfaceType == SURFACE_TYPE_SURFACE_VIEW) fullscreen else null
                            key(surfaceType, sizeKey, configuration.screenWidthDp, configuration.screenHeightDp) {
                                ContentFrame(
                                    player = ctrl.player,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            // Pinch zoom and the capture's kick: on the video view only (a
                                            // SurfaceView is never scaled, see surfaceType).
                                            if (surfaceType == SURFACE_TYPE_TEXTURE_VIEW) {
                                                Modifier.graphicsLayer {
                                                    val k = shot.kick.value * zoom.scale
                                                    scaleX = k
                                                    scaleY = k
                                                    translationX = zoom.offset.x
                                                    translationY = zoom.offset.y
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    surfaceType = surfaceType,
                                    contentScale = pictureFit.scale,
                                    shutter = {
                                        DisposableEffect(Unit) {
                                            surfaceCovered = true
                                            onDispose { surfaceCovered = false }
                                        }
                                    },
                                )
                            }
                            pipBridge?.let {
                                Image(
                                    it,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = if (surfaceCovered) 1f else bridgeAlpha.value },
                                    contentScale = pictureFit.scale,
                                )
                            }
                            // 截图: a white flash over the picture.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .drawBehind {
                                        val a = shot.flash.value
                                        if (a > 0.004f) drawRect(Color.White, alpha = a)
                                    },
                            )
                        }
                        AnimatedVisibility(
                            visible = showCover,
                            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                            exit = fadeOut(YoinMotion.slowEffectsSpec()),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            CoverLoading(ctrl = ctrl, cover = target?.cover.orEmpty(), big = fullscreen)
                        }
                        if (!inPip && !pipEntering && !lyricsShown) {
                            DanmakuLayer(ctrl = ctrl, big = fullscreen)
                        }
                        // Nothing covers the picture any more, so subtitles stay put.
                        if (!lyricsShown) {
                            SubtitleOverlay(ctrl = ctrl, controlsVisible = false, big = fullscreen, minimal = !full)
                        }
                        if (controls) {
                            // The hairline sits on the picture's own bottom edge (inside any letterbox).
                            val aspect = pictureAspect().takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
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
                                    Modifier
                                        .pointerInput(zoom) {
                                            detectPicturePinch(
                                                zoom = zoom,
                                                enabled = { pinchAllowed && ui.feedback.holdIndex < 0 && !back.busy },
                                                // The card opens up to the whole picture while it is zoomed.
                                                onStart = { ui.visible = false },
                                                onRelease = onRelease@{ box ->
                                                    // Left fullscreen (or the like) mid-pinch: the plain picture.
                                                    if (!pinchAllowed) {
                                                        zoom.snapHome()
                                                        return@onRelease
                                                    }
                                                    val (s, o) = pinchRest(zoom.scale, zoom.offset, box, ctrl.videoAspect, latestFit)
                                                    if (s != zoom.scale) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                    zoom.springTo(pipScope, zoomSpec, s, o)
                                                },
                                            )
                                        }
                                        .playerGestures(
                                        ctrl = ctrl,
                                        ui = ui,
                                        activity = activity,
                                        fullscreen = fullscreen,
                                        minimal = !full,
                                        onDoubleTap = onDoubleTap,
                                        onSwipeExit = actions.onBack,
                                        onSwipeEnter = actions.onFullscreen,
                                        swipeBack = back,
                                    )
                                },
                            ),
                    )
                    // 仅听声音: the subtitles as lyrics take the picture's place (LyricsPane.kt).
                    // Over the picture's gestures: the lines scroll and seek, a tap off them
                    // does what a tap on the picture does.
                    AnimatedVisibility(
                        visible = lyricsShown,
                        enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                        exit = fadeOut(YoinMotion.defaultEffectsSpec()),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LyricsPane(
                            ctrl = ctrl,
                            cover = target?.cover.orEmpty(),
                            // Fullscreen: the docked transport rides up over the bottom bar's band.
                            bottomLift = if (controls && !inline) {
                                { reveal().coerceIn(0f, 1f) * with(density) { bottomBand.toPx() } }
                            } else {
                                { 0f }
                            },
                            onBackgroundTap = {
                                if (full) {
                                    ui.visible = !ui.visible
                                    ui.poke()
                                } else {
                                    ctrl.togglePlay()
                                }
                            },
                        )
                    }
                },
                strip = {
                    // Audio-only: the lyrics pane docks its own transport and seek bar, so the
                    // strip (a second seek bar) stays away; the corners still come up.
                    if (controls && revealing && !lyricsShown) {
                        if (inline) {
                            InlineStrip(
                                ctrl = ctrl,
                                ui = ui,
                                actions = actions,
                                running = true,
                                ink = stripInk,
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
                                        ctrl = ctrl,
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
                            val nav = com.gpo.pilipala.navigation.LocalNavigator.current
                            val reportAid = ctrl.target?.aid?.takeIf { it > 0 && com.gpo.pilipala.core.net.BiliClient.isLoggedIn }
                            val report: (() -> Unit)? = reportAid?.let { aid ->
                                {
                                    // The appeal form is bilibili's own page (its categories and
                                    // fields change server-side); the page is pushed over the
                                    // video, so fullscreen folds back first.
                                    if (fullscreen) onFullscreenChange(false)
                                    nav.push(com.gpo.pilipala.navigation.Route.Web(videoReportUrl(aid), "举报视频"))
                                }
                            }
                            CompositionLocalProvider(
                                LocalPlayerShot provides if (canShot) takeShot else null,
                                LocalPlayerReport provides report,
                            ) {
                                PlayerSheets(ctrl, ui, fullscreen, sheetScheme = if (fullscreen) overlayScheme else pageScheme)
                            }
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

/** What the PiP params are built from; compared as a whole so only real changes reach the system. */
@Immutable
private data class PipSpec(
    val aspect: Float,
    val autoEnter: Boolean,
    val hint: android.graphics.Rect?,
    val title: String?,
    val subtitle: String?,
)

private const val PipParamsDebounceMs = 120L

/** How long the chrome stays down for a PiP flight that never reports landing. */
private const val PipEnteringTimeoutMs = 2500L

/** At most this long for the picture copied at a PiP switch to cover the rebuilt video view and fade out. */
private const val PipBridgeMs = 1000L

/**
 * The player's layers. [fill]: everything takes the whole frame and the strip (the
 * fullscreen bottom bar) sits on its bottom edge. Otherwise the picture, gestures and
 * overlay take a 16:9 box and the strip goes under it, growing the frame by its height.
 * The floating layer (the seek bubble) covers the whole frame on top. Draw / touch order:
 * picture, gestures, strip, overlay, floating. [chromeAlpha] fades everything but the
 * picture (the fullscreen back gesture).
 */
@Composable
private fun PlayerFrame(
    fill: Boolean,
    chromeAlpha: () -> Float,
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
                stP.forEach { it.placeWithLayer(0, h - it.height) { alpha = chromeAlpha() } }
                ovP.forEach { it.placeWithLayer(0, 0) { alpha = chromeAlpha() } }
                flP.forEach { it.placeWithLayer(0, 0) { alpha = chromeAlpha() } }
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
                stP.forEach { it.placeWithLayer(0, ph) { alpha = chromeAlpha() } }
                ovP.forEach { it.placeWithLayer(0, 0) { alpha = chromeAlpha() } }
                flP.forEach { it.placeWithLayer(0, 0) { alpha = chromeAlpha() } }
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
    val (s, dy) = cardPose(p, a, w, h, topBand.toPx(), bottomBand.toPx(), CardGap.toPx(), minScale)
    scaleX = s
    scaleY = s
    translationY = dy
    val pc = p.coerceIn(0f, 1f)
    val insetX = (w - pw) / 2f * pc
    val insetY = (h - ph) / 2f * pc
    shape = PictureCardShape(
        Rect(insetX, insetY, w - insetX, h - insetY),
        CardRadius.toPx() * pc / s.coerceAtLeast(0.1f),
    )
    clip = true
}

/**
 * The card's scale and lift at reveal [p] for a [w] x [h] picture box showing an [aspect]
 * picture: it shrinks into the band between the bars (never below [minScale]) and lifts
 * to that band's centre. Shared with the fullscreen back pose.
 */
internal fun cardPose(p: Float, aspect: Float, w: Float, h: Float, top: Float, bottom: Float, gap: Float, minScale: Float): Pair<Float, Float> {
    if (p <= 0.0005f) return 1f to 0f
    val pw = minOf(w, h * aspect)
    val ph = pw / aspect
    val fit = ((h - top - bottom - gap * 2f) / ph).coerceIn(minScale, 1f)
    return (1f - (1f - fit) * p) to (top - bottom) / 2f * p
}

internal val CardRadius = 20.dp
internal val CardGap = 6.dp

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
/**
 * The inline picture's own shape (the host's card corners, for example). Only the picture
 * is clipped and black: the control strip under it sits on the page.
 */
internal val LocalPlayerPictureShape = staticCompositionLocalOf<Shape> { RectangleShape }

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
    ) {
        // A nested scheme needs its own default content colour; the outer one may be light.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
