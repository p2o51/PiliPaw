package com.gpo.pilipala.feature.player

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.ContentFrame
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

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
    FullscreenWindowEffect(
        activity = activity,
        fullscreen = fullscreen,
        lockLandscape = fullscreen && !portraitVideo && !inPip && !ctrl.pipForcedFullscreen && !ctrl.fullscreenByRotation,
    )
    BackHandler(enabled = fullscreen && !inPip) { onFullscreenChange(false) }
    LaunchedEffect(fullscreen) { if (!fullscreen) ctrl.fullscreenByRotation = false }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val phone = configuration.smallestScreenWidthDp < 600
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
    var controlsVisible by remember { mutableStateOf(true) }
    val pageScheme = MaterialTheme.colorScheme

    Box(
        modifier
            .background(Color.Black)
            .clipToBounds()
            .onGloballyPositioned { yInWindow = it.positionInWindow().y },
    ) {
        ContentFrame(
            player = ctrl.player,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            shutter = {},
        )

        PlayerChromeTheme {
            val phase = ctrl.phase
            val showCover = target == null || (!ctrl.firstFrameRendered && phase !is PlayerPhase.Error)
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
            SubtitleOverlay(ctrl = ctrl, controlsVisible = controlsVisible && !inPip, big = fullscreen)

            if (!inPip) {
                val overlayScheme = MaterialTheme.colorScheme
                PlayerOverlay(
                    ctrl = ctrl,
                    title = target?.title.orEmpty(),
                    fullscreen = fullscreen,
                    topInset = topInset,
                    canPip = canPip && activity != null,
                    activity = activity,
                    sheetScheme = if (fullscreen) overlayScheme else pageScheme,
                    onBack = onBack,
                    onFullscreenChange = { fs ->
                        if (!fs) ctrl.fullscreenByRotation = false
                        onFullscreenChange(fs)
                    },
                    onPip = { enterPip(activity, ctrl.videoAspect) },
                    onControlsVisibleChange = { controlsVisible = it },
                )
            }

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
                    IconButton(
                        onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.padding(top = topInset).padding(4.dp),
                    ) {
                        Icon(YoinSymbols.Back, contentDescription = "返回")
                    }
                }
            }
        }
    }
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
        val loading = !waitingForUser && ctrl.phase !is PlayerPhase.Error
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
 * derived from the page's (cover-tinted) primary.
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
