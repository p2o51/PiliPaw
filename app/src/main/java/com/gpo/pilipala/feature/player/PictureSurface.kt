package com.gpo.pilipala.feature.player

import android.util.Log
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay

private const val TAG = "PiliSurface"

/**
 * The video page's picture: Media3's ContentFrame with a TextureView, plus a guard that keeps
 * the player drawing into it.
 *
 * Media3's PlayerSurface hands the player its view once (when the view or the player changes)
 * and never again. Whatever else takes the player's output while the page is still up (the mini
 * bar's tile, a window that was resized, a SurfaceView that never got its surface) leaves the
 * page on a still picture while the sound and the progress run on, until the view is rebuilt
 * (entering fullscreen did that). This view takes the picture back:
 *  - when the player says its output went away (surface size 0×0) while this view can draw;
 *  - when the window comes back to the front;
 *  - when, playing, the decoder keeps throwing its frames away (nowhere to draw them).
 * [active] is false while something else should own the picture (the mini bar's tile, PiP entry).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PictureSurface(
    ctrl: PiliPlayerController,
    active: Boolean,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val player = ctrl.player
    val presentation = rememberPresentationState(player, false)
    var view by remember { mutableStateOf<TextureView?>(null) }
    AndroidView(
        modifier = modifier.resizeWithContentScale(contentScale, presentation.videoSizeDp),
        factory = { TextureView(it) },
        onReset = {},
        update = { view = it },
    )
    val v = view ?: return
    val latestActive by rememberUpdatedState(active)

    fun attach(p: Player, why: String) {
        if (!p.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return
        if (why != "bind") Log.i(TAG, "re-attach picture: $why")
        p.setVideoTextureView(v)
        // Screenshot.findVideoView picks the page's view by this tag (as Media3's own does).
        v.tag = p
    }

    LaunchedEffect(v, player) {
        if (player != null) {
            (v.tag as? Player)?.takeIf { it !== player }?.let { old ->
                if (old.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) old.clearVideoTextureView(v)
            }
            attach(player, "bind")
        }
    }

    // Output gone (the view that had it was torn down): ours can draw, so take it.
    DisposableEffect(v, player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val l = object : Player.Listener {
            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                if (width == 0 && height == 0 && latestActive && v.isAttachedToWindow && v.isAvailable) {
                    v.post { if (latestActive && v.isAttachedToWindow && v.isAvailable) attach(p, "output lost") }
                }
            }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }

    // Back in front: whatever drew while we were away (the bar's tile) gives the picture back.
    val owner = (LocalActivity.current as? ComponentActivity) ?: LocalLifecycleOwner.current
    DisposableEffect(owner, v, player) {
        val p = player ?: return@DisposableEffect onDispose { }
        var stopped = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> stopped = true
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    if (latestActive && v.isAvailable) attach(p, "back in front")
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Watchdog: playing with its picture on, frames decoded and thrown away (no surface to
    // draw into), none rendered for a few seconds. A video that simply has no new frame (a
    // still picture with music) decodes nothing either, so it is left alone.
    val exo = player as? ExoPlayer
    LaunchedEffect(v, exo, active, ctrl.audioOnly) {
        if (exo == null || !active || ctrl.audioOnly) return@LaunchedEffect
        var lastRendered = -1
        var lastThrown = -1
        var stalledFor = 0
        while (true) {
            delay(1000)
            val running = exo.isPlaying && exo.playbackState == Player.STATE_READY &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!running) {
                lastRendered = -1
                stalledFor = 0
                continue
            }
            val counters = exo.videoDecoderCounters
            if (counters == null) {
                // The video renderer is off while the page is on screen (dropped for the
                // background and never taken back): turn it back on.
                Log.i(TAG, "video track off while on screen, turning it back on")
                ctrl.setBackground(false)
                continue
            }
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount
            val thrown = counters.skippedOutputBufferCount + counters.droppedBufferCount
            val stalled = lastRendered >= 0 && rendered == lastRendered && thrown > lastThrown
            lastRendered = rendered
            lastThrown = thrown
            stalledFor = if (stalled) stalledFor + 1 else 0
            if (stalledFor >= 3 && v.isAttachedToWindow && v.isAvailable) {
                stalledFor = 0
                attach(exo, "frames thrown away for 3s")
            }
        }
    }
}
