package com.gpo.pilipala.feature.player

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The one slot between a 单列 card's preview and the video page it opens: the card puts
 * its playing controller here, the page's [rememberBiliPlayerState] takes it and keeps
 * playing from the same frame. A newer put releases a controller no page took.
 */
internal object PreviewHandoff {
    private var slot: Pair<String, PiliPlayerController>? = null

    fun put(bvid: String, controller: PiliPlayerController) {
        slot?.second?.takeIf { it !== controller }?.dispose()
        slot = bvid to controller
    }

    fun take(bvid: String): PiliPlayerController? {
        val s = slot ?: return null
        if (bvid.isEmpty() || s.first != bvid) return null
        slot = null
        return s.second
    }
}

/**
 * YouTube's feed preview for the 单列 home feeds: the card that settles in view plays,
 * muted, in its cover; opening it hands the playing player to the video page
 * ([PreviewHandoff]), so the picture flies into the page without reloading. One
 * controller per feed, created on demand; previews run on unmetered networks only.
 */
@Stable
class FeedPreview internal constructor(private val context: Context) : RememberObserver {
    internal var controller by mutableStateOf<PiliPlayerController?>(null)
        private set

    /** bvid of the card playing (or loading), null when none is. */
    var playingKey by mutableStateOf<String?>(null)
        private set

    fun play(item: VideoItem) {
        if (item.bvid.isEmpty() || item.cid <= 0 || item.target != null) return
        if (playingKey == item.bvid) return
        if (!unmetered()) {
            stop()
            return
        }
        val c = controller ?: PiliPlayerController(context, preview = true).also { controller = it }
        playingKey = item.bvid
        c.load(PlayTarget(bvid = item.bvid, aid = item.aid, cid = item.cid, title = item.title, cover = item.cover))
    }

    fun stop() {
        controller?.dispose()
        controller = null
        playingKey = null
    }

    /**
     * The card [bvid] was opened: its playing preview goes to the page. Anything else
     * stops (the page loads its own player).
     */
    fun open(bvid: String) {
        val c = controller
        if (c != null && playingKey == bvid && c.phase !is PlayerPhase.Error) {
            controller = null
            playingKey = null
            PreviewHandoff.put(bvid, c)
        } else {
            stop()
        }
    }

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = stop()
    override fun onAbandoned() = stop()
}

@Composable
fun rememberFeedPreview(): FeedPreview {
    val context = LocalContext.current.applicationContext
    return remember { FeedPreview(context) }
}

/** The feed's preview, for the 单列 cards inside it; null elsewhere. */
val LocalFeedPreview = staticCompositionLocalOf<FeedPreview?> { null }

/**
 * Picks the card to preview: once [state] stops scrolling and stays still a moment, the
 * first card almost wholly on screen (cover included) (top row first). The playing card stops
 * as soon as it is mostly scrolled away, and everything stops while the feed is off
 * screen (another home tab) or the app is not resumed. [itemFor] maps a grid key to its
 * video (null for headers and footers). Returns the modifier for the grid, which reports
 * whether the feed is on screen.
 */
@Composable
fun FeedPreviewEffect(preview: FeedPreview, state: LazyGridState, itemFor: (Any) -> VideoItem?): Modifier {
    var onScreen by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val latestItemFor by rememberUpdatedState(itemFor)
    LaunchedEffect(preview, state) {
        snapshotFlow {
            val info = state.layoutInfo
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            fun shown(it: androidx.compose.foundation.lazy.grid.LazyGridItemInfo): Float {
                val h = it.size.height.coerceAtLeast(1)
                val top = it.offset.y.coerceAtLeast(start)
                val bottom = (it.offset.y + h).coerceAtMost(end)
                return (bottom - top).coerceAtLeast(0) / h.toFloat()
            }
            val candidate = info.visibleItemsInfo
                .filter { shown(it) >= 0.9f && latestItemFor(it.key) != null }
                .minWithOrNull(compareBy({ it.offset.y }, { it.offset.x }))
                ?.key
            val playingVisible = preview.playingKey?.let { key ->
                info.visibleItemsInfo.any { latestItemFor(it.key)?.bvid == key && shown(it) >= 0.5f }
            } ?: false
            PickState(onScreen && resumed, state.isScrollInProgress, candidate, playingVisible)
        }.collectLatest { p ->
            if (!p.active) {
                preview.stop()
                return@collectLatest
            }
            if (preview.playingKey != null && !p.playingVisible) preview.stop()
            if (p.scrolling) return@collectLatest
            delay(PreviewSettleMs)
            val item = p.candidate?.let { latestItemFor(it) }
            if (item == null) preview.stop() else preview.play(item)
        }
    }
    return Modifier.onGloballyPositioned { c ->
        val w = c.size.width
        val visible = w > 0 && c.boundsInWindow().width >= w / 2f
        if (visible != onScreen) onScreen = visible
    }
}

private data class PickState(val active: Boolean, val scrolling: Boolean, val candidate: Any?, val playingVisible: Boolean)

private const val PreviewSettleMs = 600L

/**
 * The preview inside a 单列 card's cover: the playing picture fades in over the cover once
 * its first frame is out, with a thin progress line along the bottom. Nothing when this
 * card is not the one playing. Give it the cover's bounds and clip.
 */
@Composable
fun FeedPreviewLayer(bvid: String, modifier: Modifier = Modifier) {
    val preview = LocalFeedPreview.current ?: return
    val c = preview.controller
    val on = c != null && preview.playingKey == bvid
    val shown = on && c?.firstFrameRendered == true
    val alpha by animateFloatAsState(if (shown) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "previewIn")
    if (!on || c == null) return
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(Color.Black)) {
        ContentFrame(
            player = c.player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            // Fills the cover like the cover image does (a portrait video shows its middle).
            contentScale = ContentScale.Crop,
            shutter = {},
        )
        val duration = c.durationMs
        if (duration > 0) {
            val fraction = (c.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
