package com.gpo.pilipala.feature.sources

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 继续播放 (the "like YouTube" choice): leaving a video keeps it playing in a mini bar instead of
 * stopping it. Asked once, the first time a playing video is left; changeable in 设置 › 播放.
 */
object KeepPlaying {
    /** 0: not asked yet, 1: stop when the video is left (PiliPaw as it was), 2: keep playing. */
    val mode = AppSettings.register("keep_playing_mode", 0)

    /** The question is up (a video was just left for the first time); its player waits in [BiliKeeper]. */
    var asking by mutableStateOf(false)
        internal set

    val on: Boolean get() = mode.value == 2

    fun choose(keep: Boolean) {
        AppSettings.set(mode, if (keep) 2 else 1)
        asking = false
        if (!keep) BiliKeeper.close()
    }

    /** Whether the bar has the screen to itself (scrolling down folds it into the nav bar's circle). */
    var compact by mutableStateOf(false)
        internal set

    /**
     * For the shell's content: scrolling down folds the bar into a circle beside the nav bar,
     * scrolling up opens it again.
     */
    val scroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (abs(available.y) > 2f) compact = available.y < 0
            return Offset.Zero
        }
    }
}

/**
 * Where the shell's nav bar sits on screen (window px), reported by the shell on screen; null
 * on a pushed page. The mini bar sits above it, or beside it as a circle.
 */
object NavSpot {
    var bounds by mutableStateOf<Rect?>(null)
        private set
    private var owner: Any? = null

    /** A shell's nav pill moved (or appeared); the newest shell owns the spot. */
    fun place(token: Any, rect: Rect?) {
        if (rect == null && owner !== token) return
        owner = if (rect == null) null else token
        bounds = rect
    }

    /** A shell left: only its own spot goes (the next shell may already have placed its own). */
    fun clear(token: Any) {
        if (owner !== token) return
        owner = null
        bounds = null
    }
}

/**
 * The one Bilibili player kept after its page went ([PiliPlayerController.onForgotten] offers
 * itself). The page that opens the same video next takes it back and carries on without
 * reloading; a newer kept player, or the bar's swipe, releases it.
 */
internal object BiliKeeper {
    internal var held by mutableStateOf<PiliPlayerController?>(null)
        private set

    internal fun offer(ctrl: PiliPlayerController): Boolean {
        val t = ctrl.target ?: return false
        // Plain videos only (an episode or an offline file reopens through other pages).
        if (!ctrl.keepable || t.isPgc || t.bvid.isEmpty()) return false
        when (KeepPlaying.mode.value) {
            1 -> return false
            0 -> KeepPlaying.asking = true
        }
        held?.takeIf { it !== ctrl }?.dispose()
        // The picture carries on in the mini bar. The page's own lifecycle stopped as it left, which
        // rested the video track (BiliVideoPlayer): wake it, the app is still on screen. MiniBarHost
        // rests it again whenever the app itself goes away.
        ctrl.setBackground(false)
        held = ctrl
        return true
    }

    internal fun take(bvid: String): PiliPlayerController? {
        val c = held ?: return null
        if (bvid.isEmpty() || c.target?.bvid != bvid) return null
        held = null
        c.setBackground(false)
        return c
    }

    fun close() {
        held?.dispose()
        held = null
    }

    internal fun route(c: PiliPlayerController): Route? = c.target?.let { t ->
        Route.Video(bvid = t.bvid, aid = t.aid, cid = t.cid, cover = t.cover, title = t.title, audioOnly = c.audioOnly)
    }
}

/** What the bar draws and does, whichever part is playing. */
private class Playing(
    val source: VideoSource,
    val title: String,
    val cover: String,
    val isPlaying: Boolean,
    val progress: Float,
    val toggle: () -> Unit,
    val seekBy: (Long) -> Unit,
    val open: () -> Unit,
    val close: () -> Unit,
    /** The live picture for the bar, when there is one (not in 仅听声音, not before it loads). */
    val player: androidx.media3.common.Player? = null,
)

@Composable
private fun currentPlaying(): Playing? {
    // Both kept: the one playing (starting either pauses the other).
    val ytUp = Player.session != null && !Player.expanded
    val bili = BiliKeeper.held?.takeIf { it.isPlaying || !ytUp || !Player.isPlaying }
    if (bili != null) {
        val t = bili.target
        return Playing(
            source = VideoSource.Bilibili,
            title = t?.title.orEmpty(),
            cover = MixItems.secure(t?.cover.orEmpty()),
            isPlaying = bili.isPlaying,
            progress = if (bili.durationMs > 0) bili.positionMs.toFloat() / bili.durationMs else 0f,
            toggle = { bili.togglePlay() },
            seekBy = { d -> bili.seekTo((bili.positionMs + d).coerceAtLeast(0)) },
            open = { BiliKeeper.route(bili)?.let(BiliLinks::open) },
            close = { BiliKeeper.close() },
            player = bili.player.takeIf { !bili.audioOnly },
        )
    }
    // The YouTube player collapsed: this bar is its mini player.
    // A video that failed (or a live stream that ended) leaves the bar instead of sitting there blank.
    val yt = Player.session?.takeIf { !Player.expanded && it.error == null }
    if (yt != null) {
        var position by remember { mutableStateOf(0L) }
        LaunchedEffect(yt) {
            while (true) {
                position = Player.exo.currentPosition
                kotlinx.coroutines.delay(500)
            }
        }
        return Playing(
            source = VideoSource.YouTube,
            title = yt.title,
            cover = yt.thumbnail,
            isPlaying = Player.isPlaying,
            progress = if (Player.durationMs > 0) position.toFloat() / Player.durationMs else 0f,
            toggle = { Player.toggle() },
            seekBy = { d -> Player.exo.seekTo((Player.exo.currentPosition + d).coerceAtLeast(0)) },
            open = { Player.expanded = true },
            close = { Player.close() },
            player = Player.exo.takeIf { yt.info != null && !Player.audioOnly },
        )
    }
    return null
}

private val BarHeight = 56.dp
private val CircleSize = 48.dp
private val Gap = 8.dp
private val SideMargin = 16.dp
private val BarMaxWidth = 440.dp

/** Extra bottom room a shell list needs while the bar sits above the nav bar (phones). */
@Composable
fun miniBarInset(): androidx.compose.ui.unit.Dp {
    val showing = BiliKeeper.held != null || Player.session != null
    return if (showing) BarHeight + Gap else 0.dp
}

/**
 * The mini bar, over both parts of the app: [−15s][picture with a progress line][pause][+15s],
 * joined blocks with no title and no ×; a sideways swipe closes it. Above the nav bar on a
 * phone, beside it on a tablet; scrolling down folds it into a 48dp circle with a progress ring
 * next to the nav bar. Tapping the picture (or the circle) reopens the video.
 */
@Composable
fun MiniBarHost() {
    val playing = currentPlaying()
    // The app goes away: a kept Bilibili player drops its picture (sound only), and takes it back on return.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> BiliKeeper.held?.setBackground(true)
                androidx.lifecycle.Lifecycle.Event.ON_START -> BiliKeeper.held?.setBackground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (KeepPlaying.asking) KeepAskDialog()
    val nav = NavSpot.bounds
    val density = LocalDensity.current
    val fold by animateFloatAsState(
        if (playing != null && KeepPlaying.compact && nav != null) 1f else 0f,
        YoinMotion.defaultSpatialSpec(),
        label = "miniFold",
    )
    val shown by animateFloatAsState(if (playing != null) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "miniShown")
    var last by remember { mutableStateOf<Playing?>(null) }
    if (playing != null) last = playing
    val p = playing ?: last ?: return
    if (shown <= 0.001f && playing == null) return

    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val barH = with(density) { BarHeight.toPx() }
        val gap = with(density) { Gap.toPx() }
        val side = with(density) { SideMargin.toPx() }
        val circle = with(density) { CircleSize.toPx() }
        val maxBar = with(density) { BarMaxWidth.toPx() }
        // The nav bar in this box's coordinates (the box starts at the window's top).
        val navRect = nav
        val barRect: Rect
        val circleRect: Rect
        if (navRect != null) {
            val wide = navRect.left - side - gap >= maxBar * 0.8f
            barRect = if (wide) {
                // Tablet: the bar to the left of the nav bar, in its row.
                val bw = minOf(maxBar, navRect.left - side - gap)
                val top = navRect.center.y - barH / 2
                Rect(navRect.left - gap - bw, top, navRect.left - gap, top + barH)
            } else {
                val bw = minOf(maxBar, w - side * 2)
                val left = (w - bw) / 2
                Rect(left, navRect.top - gap - barH, left + bw, navRect.top - gap)
            }
            val cy = navRect.center.y
            circleRect = Rect(navRect.left - gap - circle, cy - circle / 2, navRect.left - gap, cy + circle / 2)
        } else {
            val bw = minOf(maxBar, w - side * 2)
            val left = (w - bw) / 2
            barRect = Rect(left, h - gap - barH, left + bw, h - gap)
            circleRect = barRect
        }
        val rect = Rect(
            lerp(barRect.left, circleRect.left, fold),
            lerp(barRect.top, circleRect.top, fold),
            lerp(barRect.right, circleRect.right, fold),
            lerp(barRect.bottom, circleRect.bottom, fold),
        )
        val scope = rememberCoroutineScope()
        val swipe = remember { Animatable(0f) }
        val springBack = YoinMotion.defaultSpatialSpec<Float>()
        Box(
            Modifier
                .offset { IntOffset((rect.left + swipe.value).roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .graphicsLayer {
                    alpha = shown * (1f - (abs(swipe.value) / (barRect.width * 0.6f)).coerceIn(0f, 1f))
                    translationY = (1f - shown) * barH
                }
                .pointerInput(p.close) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(swipe.value) > size.width * 0.35f) {
                                    swipe.animateTo(if (swipe.value > 0) size.width.toFloat() else -size.width.toFloat())
                                    p.close()
                                    swipe.snapTo(0f)
                                } else {
                                    swipe.animateTo(0f, springBack)
                                }
                            }
                        },
                        onHorizontalDrag = { change, d ->
                            change.consume()
                            scope.launch { swipe.snapTo(swipe.value + d) }
                        },
                    )
                },
        ) {
            if (fold < 0.999f) {
                BarContent(p, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fold })
            }
            if (fold > 0.001f) {
                CircleContent(p, Modifier.fillMaxSize().graphicsLayer { alpha = fold })
            }
        }
    }
}

@Composable
private fun BarContent(p: Playing, modifier: Modifier) {
    val outer = 28.dp
    val inner = 6.dp
    val tone = MaterialTheme.colorScheme.secondaryContainer
    val onTone = MaterialTheme.colorScheme.onSecondaryContainer
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner))
                .background(tone)
                .clickable { p.seekBy(-15_000) },
            contentAlignment = Alignment.Center,
        ) { SeekGlyph(forward = false, tint = onTone) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(inner))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = p.open),
        ) {
            NetImage(p.cover, Modifier.fillMaxSize(), contentDescription = p.title, thumbWidth = 320.dp)
            // The video itself over its cover (a TextureView: it follows the bar's fold and swipe).
            // Attached a beat after the bar appears: the page that let the player go detaches its own
            // video view as it leaves, and that must not land after ours.
            var ready by remember(p.player) { mutableStateOf(false) }
            LaunchedEffect(p.player) {
                kotlinx.coroutines.delay(400)
                ready = true
            }
            p.player?.takeIf { ready }?.let { player ->
                androidx.media3.ui.compose.ContentFrame(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    shutter = {},
                )
            }
            // The progress line on the picture's inner (bottom) edge.
            val primary = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val y = size.height - 1.5.dp.toPx()
                        drawLine(primary.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
                        drawLine(primary, Offset(0f, y), Offset(size.width * p.progress.coerceIn(0f, 1f), y), 3.dp.toPx())
                    },
            )
        }
        // Paused, the block rounds into a circle (56 × 56): the play key stands apart.
        val playCorner by androidx.compose.animation.core.animateDpAsState(
            if (p.isPlaying) inner else outer,
            YoinMotion.defaultSpatialSpec(),
            label = "playCorner",
        )
        Box(
            Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(playCorner))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = p.toggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (p.isPlaying) YoinSymbols.PauseFilled else YoinSymbols.PlayFilled,
                contentDescription = if (p.isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Box(
            Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer))
                .background(tone)
                .clickable { p.seekBy(15_000) },
            contentAlignment = Alignment.Center,
        ) { SeekGlyph(forward = true, tint = onTone) }
    }
}

/** ±15 s: the circular arrow (mirrored for forward) with the 15 inside it. */
@Composable
private fun SeekGlyph(forward: Boolean, tint: androidx.compose.ui.graphics.Color) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            com.gpo.yoin.symbols.PiliSymbols.Replay,
            contentDescription = if (forward) "前进 15 秒" else "后退 15 秒",
            tint = tint,
            modifier = Modifier.size(30.dp).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            "15",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CircleContent(p: Playing, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .clip(CircleShape)
            .clickable(onClick = p.open)
            .drawWithContent {
                drawContent()
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(ring, -90f, 360f * p.progress.coerceIn(0f, 1f), false, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            },
        contentAlignment = Alignment.Center,
    ) {
        NetImage(p.cover, Modifier.fillMaxSize().padding(5.dp).clip(CircleShape), contentDescription = p.title, shape = CircleShape, thumbWidth = 96.dp)
    }
}

/** Asked once: the first time a playing video is left. */
@Composable
private fun KeepAskDialog() {
    AlertDialog(
        onDismissRequest = { KeepPlaying.choose(keep = true) },
        title = { Text("回到首页时，要停止播放吗？") },
        text = {
            Text(
                "不停的话，视频会收在底部的迷你条里接着放，像 YouTube 那样。可以随时在 设置 › 播放 里更改。",
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = { TextButton(onClick = { KeepPlaying.choose(keep = true) }) { Text("继续播放") } },
        dismissButton = { TextButton(onClick = { KeepPlaying.choose(keep = false) }) { Text("停止播放") } },
    )
}
